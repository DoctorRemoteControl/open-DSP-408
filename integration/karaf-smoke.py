#!/usr/bin/env python3
"""Provision a disposable Karaf and validate the public DSP408 features.

Only loopback DSP peers are supported. All Karaf control goes through the
vendored codex-karaf.py wrapper. Runtime files and diagnostic evidence remain
in the supplied empty state directory after the owned JVM has stopped.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
KARAF_SHA256 = {"4.4.11": "fee48696c2b6473d3d1e6b50ed5156f5a61d4445a118b28c3c70462b52685879"}
ERROR_OUTPUT = re.compile(r"Error executing command|Unable to resolve|ResolutionException|Command not found", re.I)


def prepare_state(path):
    path = Path(path).resolve()
    if path.exists() and any(path.iterdir()):
        raise ValueError("State directory must be empty; existing state is preserved")
    path.mkdir(parents=True, exist_ok=True)
    return path


def extract_distribution(archive, state, version):
    state = Path(state).resolve()
    expected = "apache-karaf-" + version
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle.getmembers():
            parts = Path(member.name).parts
            if (not parts or parts[0] != expected or ".." in parts
                    or member.name.startswith("/") or not (member.isfile() or member.isdir())):
                raise ValueError("Unsafe archive member: " + member.name)
        bundle.extractall(state, filter="data")
    home = state / "karaf"
    (state / expected).rename(home)
    return home


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def encode_frame(payload):
    frame = b"\x10\x02" + payload + b"\x10\x03"
    checksum = 0
    for byte in frame:
        checksum ^= byte
    return frame + bytes([checksum])


def read_exact(client, count):
    data = bytearray()
    while len(data) < count:
        chunk = client.recv(count - len(data))
        if not chunk:
            raise EOFError("DSP connection closed")
        data.extend(chunk)
    return bytes(data)


def read_frame(client):
    if read_exact(client, 2) != b"\x10\x02":
        raise ValueError("Invalid frame prefix")
    header = read_exact(client, 3)
    body = read_exact(client, header[2])
    trailer = read_exact(client, 3)
    payload = header + body
    if encode_frame(payload)[-3:] != trailer:
        raise ValueError("Invalid frame trailer or checksum")
    return payload


class FakeDsp:
    """Length/checksum checked read-only FIR408 fixture, never a device proxy."""
    def __init__(self, mode="healthy", fragment=False, delay=0.0):
        self.mode, self.fragment, self.delay = mode, fragment, delay
        self.requests, self.forbidden, self.errors = [], [], []
        self.connections = 0
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.port = self.server.getsockname()[1]
        self.server.listen(8)
        self.server.settimeout(0.2)
        self.stopped = threading.Event()
        self.clients = set()
        self.lock = threading.Lock()
        self.workers = []

    def __enter__(self):
        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stopped.set()
        self.server.close()
        self.drop_connections()
        self.thread.join(3)
        for worker in self.workers:
            worker.join(3)

    def drop_connections(self):
        with self.lock:
            for client in list(self.clients):
                try:
                    client.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                client.close()

    def _accept(self):
        while not self.stopped.is_set():
            try:
                client, _ = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            self.connections += 1
            with self.lock:
                self.clients.add(client)
            worker = threading.Thread(target=self._serve, args=(client,), daemon=True)
            self.workers.append(worker)
            worker.start()

    def _send(self, client, payload):
        if self.delay:
            self.stopped.wait(self.delay)
        frame = encode_frame(payload)
        if self.fragment:
            for index in range(0, len(frame), 3):
                client.sendall(frame[index:index + 3])
                self.stopped.wait(0.001)
        else:
            client.sendall(frame)

    def _serve(self, client):
        try:
            client.settimeout(30)
            self._send(client, bytes.fromhex("010001be"))
            while not self.stopped.is_set():
                payload = read_frame(client)
                self.requests.append(payload.hex())
                command = payload[3]
                lengths = {0x10: 4, 0x13: 4, 0x2C: 4, 0x22: 4, 0x14: 4,
                           0x29: 5, 0x56: 7, 0x27: 5, 0x12: 4, 0x40: 4}
                if (payload[:2] != b"\x00\x01" or len(payload) != lengths.get(command)
                        or payload[2] != len(payload) - 3):
                    self.forbidden.append(payload.hex())
                    break
                if self.mode == "drop":
                    break
                if self.mode == "silent":
                    continue
                if command == 0x27:
                    reply = bytes([1, 0, 0x34, 0x24, payload[4]]) + bytes(50)
                elif command == 0x29:
                    reply = bytes([1, 0, 2, command, payload[4]])
                elif command == 0x40:
                    reply = bytes([1, 0, 43, command]) + bytes(42)
                elif command == 0x12:
                    reply = bytes.fromhex("01000101")
                else:
                    reply = bytes([1, 0, 1, command])
                self._send(client, reply)
        except (OSError, EOFError):
            pass
        except Exception as error:
            self.errors.append(str(error))
        finally:
            with self.lock:
                self.clients.discard(client)
            client.close()

    def evidence(self):
        return {"connections": self.connections, "requestCount": len(self.requests),
                "commands": sorted(set(request[6:8] for request in self.requests)),
                "forbidden": self.forbidden, "errors": self.errors,
                "acceptThreadStopped": not self.thread.is_alive(),
                "workersStopped": all(not worker.is_alive() for worker in self.workers)}


def properties(path, values):
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\n# Isolated DSP408 integration configuration\n")
        for key, value in values.items():
            handle.write(f"{key} = {value}\n")


def process_running(pid):
    try:
        stat = Path(f"/proc/{pid}/stat")
        if stat.exists() and stat.read_text().split(") ", 1)[1].startswith("Z"):
            return False
        os.kill(pid, 0)
        return True
    except (OSError, ValueError):
        return False


class Validation:
    def __init__(self, args, state):
        self.args, self.state = args, state
        self.home, self.pid = None, None
        self.result = {"ok": False, "schemaVersion": 1, "stateDirectory": str(state),
                       "karafVersion": args.karaf_version, "commands": [], "http": []}
        self.env = os.environ.copy()
        for key in ("KARAF_HOME", "KARAF_BASE", "KARAF_DATA", "KARAF_ETC", "KARAF_OPTS",
                    "KARAF_DEBUG", "KARAF_USER", "KARAF_PASSWORD", "JAVA_OPTS",
                    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            self.env.pop(key, None)
        self.env["KARAF_USER"] = "dsp408-smoke"
        self.env["KARAF_PASSWORD"] = secrets.token_urlsafe(24)
        self.env["KARAF_OPTS"] = "-Xms128m -Xmx512m"

    def control(self, action, *arguments, check=True, timeout=None):
        command = [sys.executable, str(self.home / "bin/codex-karaf.py"), action,
                   *map(str, arguments), "--port", str(self.ports["ssh"]), "--json",
                   "--timeout-sec", str(timeout or self.args.timeout)]
        started = time.monotonic()
        process = subprocess.run(command, cwd=self.home, env=self.env, text=True,
                                 capture_output=True, timeout=(timeout or self.args.timeout) + 15)
        try:
            report = json.loads(process.stdout)
        except json.JSONDecodeError:
            report = {"ok": False, "stdout": process.stdout, "stderr": process.stderr}
        entry = {"action": action, "arguments": list(map(str, arguments)),
                 "seconds": round(time.monotonic() - started, 3),
                 "returnCode": process.returncode, "report": report}
        self.result["commands"].append(entry)
        print(f"Karaf {action}: {entry['seconds']}s", file=sys.stderr, flush=True)
        if check and (process.returncode or not report.get("ok")
                      or (action == "exec" and ERROR_OUTPUT.search(str(report.get("data", ""))))):
            raise RuntimeError(f"Karaf {action} failed: {report}")
        return report

    def http(self, path, method="GET", timeout=10):
        started = time.monotonic()
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.ports['http']}/api/v1" + path,
            data=b"{}" if method == "POST" else None, method=method,
            headers={"Content-Type": "application/json"})
        try:
            response = urllib.request.urlopen(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = json.loads(response.read())
            item = {"path": path, "method": method, "status": response.status,
                    "seconds": round(time.monotonic() - started, 3), "body": data}
        self.result["http"].append(item)
        return item

    def doctor(self):
        # Historical log errors can remain after an intentionally failed connect.
        return self.control("doctor", check=False, timeout=60)

    def set_meter_interval(self, milliseconds):
        path = self.home / "etc/de.drremote.dsp408controller.config"
        configured = re.sub(r"(?m)^meter\.poll\.interval\.ms=.*$",
                            f'meter.poll.interval.ms=L"{milliseconds}"', path.read_text())
        temporary = path.with_suffix(".integration-tmp")
        temporary.write_text(configured)
        temporary.replace(path)
        self.doctor()
        deadline = time.monotonic() + 8
        while self.http("/traffic")["body"].get("meterPollIntervalMs") != milliseconds:
            if time.monotonic() >= deadline:
                raise AssertionError("Meter interval configuration was not applied")
            time.sleep(0.1)

    def provision(self, peers):
        archive = Path(self.args.karaf_archive).resolve() if self.args.karaf_archive else self.state / "karaf.tar.gz"
        url = ("https://repo.maven.apache.org/maven2/org/apache/karaf/apache-karaf/"
               f"{self.args.karaf_version}/apache-karaf-{self.args.karaf_version}.tar.gz")
        if not self.args.karaf_archive:
            with urllib.request.urlopen(url, timeout=60) as source, archive.open("wb") as dest:
                shutil.copyfileobj(source, dest)
        actual = digest(archive)
        if actual != KARAF_SHA256[self.args.karaf_version]:
            raise ValueError("Karaf archive SHA256 does not match the pinned Apache distribution")
        self.result["distribution"] = {"url": url, "sha256": actual}
        self.home = extract_distribution(archive, self.state, self.args.karaf_version)
        shutil.copy2(HERE / "codex-karaf.py", self.home / "bin/codex-karaf.py")
        self.result["controlWrapperSha256"] = digest(self.home / "bin/codex-karaf.py")
        sockets = [socket.socket() for _ in range(4)]
        try:
            for sock in sockets:
                sock.bind(("127.0.0.1", 0))
            self.ports = dict(zip(("ssh", "http", "rmiRegistry", "rmiServer"),
                                  (sock.getsockname()[1] for sock in sockets)))
            self.result["ports"] = self.ports
            self.control("doctor", check=False, timeout=60)
            etc = self.home / "etc"
            properties(etc / "org.apache.karaf.shell.cfg", {
                "sshHost": "127.0.0.1", "sshPort": self.ports["ssh"]})
            # Apache ships with every login disabled. Generate this instance's
            # own disposable credential; never read a user's installed login.
            properties(etc / "users.properties", {
                self.env["KARAF_USER"]: self.env["KARAF_PASSWORD"] + ",admin,manager,viewer,systembundles,ssh"})
            properties(etc / "org.ops4j.pax.web.cfg", {
                "org.osgi.service.http.port": self.ports["http"],
                "org.ops4j.pax.web.listening.addresses": "127.0.0.1"})
            properties(etc / "org.apache.karaf.management.cfg", {
                "rmiRegistryHost": "127.0.0.1", "rmiServerHost": "127.0.0.1",
                "rmiRegistryPort": self.ports["rmiRegistry"], "rmiServerPort": self.ports["rmiServer"]})
            properties(etc / "org.ops4j.pax.url.mvn.cfg", {
                "org.ops4j.pax.url.mvn.localRepository": str(Path(self.args.maven_repo).resolve()),
                "org.ops4j.pax.url.mvn.settings": str(self.state / "empty-settings.xml"),
                "org.ops4j.pax.url.mvn.repositories": "https://repo.maven.apache.org/maven2@id=central"})
            (self.state / "empty-settings.xml").write_text("<settings/>\n")
            # FileInstall's .config format retains String[] and scalar types;
            # comma-separated .cfg text is one endpoint, not an array.
            definitions = [f"{name}=127.0.0.1:{peer.port}" for name, peer in peers.items() if name != "healthy"]
            (etc / "de.drremote.dsp408controller.config").write_text(
                'dsp.id="main"\ndsp.ip="127.0.0.1"\ndsp.library="fir408"\n'
                + f'dsp.port=I"{peers["healthy"].port}"\nauto.connect=B"false"\n'
                + 'meter.poll.interval.ms=L"200"\ndsps=['
                + ",".join(json.dumps(value) for value in definitions) + "]\n")
            properties(etc / "de.drremote.dsp408controller.matrix.cfg", {"enabled": "false"})
            self.doctor()
        finally:
            for sock in sockets:
                sock.close()

    def inventory(self):
        version = self.args.controller_version
        repository = Path(self.args.maven_repo).resolve()
        descriptor = repository / f"de/drremote/dsp408-features/{version}/dsp408-features-{version}-features.xml"
        if not descriptor.is_file():
            raise ValueError(f"Install Controller artifacts first; missing {descriptor}")
        items = []
        for url in sorted(set(node.text.strip() for node in ET.parse(descriptor).iter()
                              if node.tag.endswith("}bundle") or node.tag == "bundle")):
            group, artifact, artifact_version = url.removeprefix("mvn:").split("/")[:3]
            path = repository / group.replace(".", "/") / artifact / artifact_version / f"{artifact}-{artifact_version}.jar"
            if not path.is_file():
                raise ValueError(f"Missing feature bundle: {path}")
            with zipfile.ZipFile(path) as bundle:
                manifest = bundle.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
            headers = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
            items.append({"url": url, "path": str(path), "sha256": digest(path),
                          "symbolicName": headers.get("Bundle-SymbolicName"),
                          "version": headers.get("Bundle-Version"),
                          "sourceRevision": headers.get("DSP408-Source-Revision"),
                          "sourceDirty": headers.get("DSP408-Source-Dirty"),
                          "protocolRevision": headers.get("DSP408-Protocol-Revision"),
                          "protocolDirty": headers.get("DSP408-Protocol-Dirty")})
        self.result["artifacts"] = items
        self.result["featureDescriptor"] = {"path": str(descriptor), "sha256": digest(descriptor)}
        return descriptor

    def require_service(self):
        services = self.control("services")["data"]
        matches = [item for item in services.get("items", [])
                   if "de.drremote.dsp408.api.DspService" in item.get("interfaces", [])]
        if len(matches) != 1:
            raise AssertionError(f"Expected one published DspService, got {len(matches)}")
        if matches[0]["properties"].get("dsp.ip") != "127.0.0.1":
            raise AssertionError("Runtime did not accept its loopback DSP configuration")
        self.result["dspService"] = matches[0]

    def run(self, peers):
        descriptor = self.inventory()
        self.control("start")
        self.control("wait-ssh")
        self.pid = int((self.home / "karaf.pid").read_text().strip())
        self.result["pid"] = self.pid
        self.doctor()
        self.control("deploy", str(descriptor), "--dry-run")
        self.control("exec", "feature:repo-add " + descriptor.as_uri())
        self.doctor()
        # Runtime must resolve on its own: installing all first would mask missing
        # core dependencies supplied accidentally by HTTP or Matrix.
        self.control("exec", "feature:install dsp408-runtime")
        self.doctor()
        self.require_service()
        if any(peer.connections for peer in peers.values()):
            raise AssertionError("auto.connect=false initiated DSP traffic")
        self.result["autoConnectDisabled"] = True
        self.control("exec", "feature:install dsp408-all")
        self.doctor()
        self.require_service()
        bundles = self.control("bundles")["data"]
        self.result["bundles"] = bundles
        for name in ("DSP408 API", "DSP408 Core", "DSP408 Runtime", "DSP408 Shell",
                     "DSP408 HTTP", "DSP408 Matrix Bot", "DSP408 wire protocol"):
            matches = [item for item in bundles["items"] if item.get("name") == name]
            if len(matches) != 1 or matches[0].get("state", "").lower() != "active":
                raise AssertionError(f"{name} bundle is not active: {matches}")
        cache_hashes = {}
        for path in (self.home / "data/cache").glob("bundle*/version*/bundle.jar"):
            cache_hashes.setdefault(digest(path), []).append(str(path))
        for artifact in self.result["artifacts"]:
            matches = cache_hashes.get(artifact["sha256"], [])
            if len(matches) != 1:
                raise AssertionError(f"Installed bundle bytes do not match {artifact['url']}: {matches}")
            artifact["deployedPath"] = matches[0]
            artifact["deployedSha256"] = artifact["sha256"]
        services = self.control("services")["data"]["items"]
        matrix = [item for item in services
                  if "de.drremote.dsp408controller.matrix.MatrixBotComponent" in item.get("interfaces", [])]
        if len(matrix) != 1:
            raise AssertionError("Matrix DS component did not publish its service")
        if matrix[0]["properties"].get("enabled") != "false":
            raise AssertionError("Matrix must remain disabled without homeserver credentials")
        self.result["matrixService"] = matrix[0]
        self.result["componentActivation"] = {
            "DspRuntimeComponent": "DspService published",
            "MatrixBotComponent": "MatrixBotComponent service published (enabled=false)"}
        deadline = time.monotonic() + self.args.timeout
        while True:
            try:
                if self.http("/health")["status"] == 200:
                    self.result["componentActivation"]["DspApiServlet"] = "HTTP servlet registered and health returned 200"
                    break
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise AssertionError("HTTP API did not become available")
            time.sleep(0.25)
        for path in ("/state", "/dsps", "/settings-cache", "/meters", "/dsps/main/state", "/openapi.json"):
            item = self.http(path)
            if item["status"] != 200:
                raise AssertionError(f"HTTP route failed: {item}")
        # Batch sessions do not render a Gogo command's return value. Echo it
        # explicitly so a successful SSH exit cannot hide a missing shell path.
        shell = self.control("exec", "shell:echo (dsp408:connected)").get("data", "")
        if str(shell).strip() != "no":
            raise AssertionError(f"Unexpected initial shell connection state: {shell}")
        self.control("exec", "shell:echo (dsp408:dsps)")
        self.control("exec", "shell:echo (dsp408:dsp main state)")
        # A silent peer holds bootstrap while state/service access and cancellation
        # must remain responsive on the actual HTTP/DS boundary.
        with ThreadPoolExecutor(max_workers=1) as pool:
            connecting = pool.submit(self.http, "/dsps/silent/connection/connect", "POST", 15)
            deadline = time.monotonic() + 5
            while not peers["silent"].requests and time.monotonic() < deadline:
                time.sleep(0.01)
            if not peers["silent"].requests:
                raise AssertionError("Silent bootstrap was not exercised")
            status = self.http("/dsps/silent/state", timeout=2)
            if status["status"] != 200 or status["seconds"] > 1.5:
                raise AssertionError(f"Blocked bootstrap blocks status: {status}")
            self.http("/dsps/silent/connection/disconnect", "POST", 2)
            self.result["cancelledBootstrap"] = connecting.result(timeout=5)
            if self.result["cancelledBootstrap"]["status"] < 400:
                raise AssertionError("Cancelled bootstrap incorrectly succeeded")
            self.require_service()
        dropped = self.http("/dsps/drop/connection/connect", "POST", 10)
        if dropped["status"] < 400:
            raise AssertionError("Dropped bootstrap incorrectly succeeded")
        self.http("/dsps/drop/connection/disconnect", "POST")
        self.require_service()
        for index in range(self.args.reconnects):
            connected = self.http("/connection/connect", "POST", 60)
            if connected["status"] != 200:
                raise AssertionError(f"Fragmented/delayed fake bootstrap failed: {connected}")
            cache = self.http("/settings-cache")
            if not cache["body"].get("ready") or cache["body"].get("cachedBlockCount") != 32:
                raise AssertionError(f"Settings dump was not verified: {cache}")
            state = self.http("/state")
            if not state["body"].get("connected"):
                raise AssertionError(f"Connected state is unavailable: {state}")
            deadline = time.monotonic() + 3
            while True:
                meters = self.http("/meters")
                if meters["body"].get("meters", {}).get("rawPayloadConfirmedFromDevice"):
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError("Central meter polling did not observe a fake DSP response")
                time.sleep(0.05)
            self.http("/traffic")
            if index == 0:
                # Keep this session long enough to prove two real keepalives;
                # frequent meters otherwise suppress them by design.
                self.set_meter_interval(60_000)
                # TrafficGate requires 5 s idle and spaces keepalives by 10 s;
                # allow scheduler jitter around both scheduled exchanges.
                deadline = time.monotonic() + 25
                while sum(request[6:8] == "12" for request in peers["healthy"].requests) < 2:
                    if not self.http("/traffic")["body"].get("connected"):
                        raise AssertionError("Session failed before two keepalives completed")
                    if time.monotonic() >= deadline:
                        raise AssertionError("Keepalive scheduling was not exercised")
                    time.sleep(0.1)
                self.result["keepAliveRequests"] = sum(
                    request[6:8] == "12" for request in peers["healthy"].requests)
                self.set_meter_interval(200)
                peers["healthy"].drop_connections()
                deadline = time.monotonic() + 5
                while self.http("/state")["body"].get("connected") and time.monotonic() < deadline:
                    time.sleep(0.1)
                self.result["transportDropObserved"] = not self.http("/state")["body"].get("connected")
                if not self.result["transportDropObserved"]:
                    raise AssertionError("Dropped transport remained connected")
            disconnected = self.http("/connection/disconnect", "POST")
            if disconnected["status"] != 200:
                raise AssertionError(f"Disconnect failed: {disconnected}")
        self.control("exec", "shell:echo (dsp408:connected)")
        self.control("features")
        self.control("memory")
        self.control("threads")
        self.doctor()
        self.result["ok"] = True

    def shutdown(self):
        if not self.home:
            self.result["shutdown"] = {"stopped": True, "started": False}
            return
        pidfile = self.home / "karaf.pid"
        if self.pid is None and pidfile.exists():
            self.pid = int(pidfile.read_text().strip())
        shutdown = {"pid": self.pid, "terminationSignals": []}

        def owned_process():
            try:
                command_line = Path(f"/proc/{self.pid}/cmdline").read_bytes().split(b"\x00")
                return ("-Dkaraf.home=" + str(self.home)).encode() in command_line
            except OSError:
                return False

        if self.pid:
            if process_running(self.pid) and not owned_process():
                raise RuntimeError("Refusing to stop a PID not owned by this isolated Karaf")
            if process_running(self.pid):
                stop_ok = False
                try:
                    stopped = self.control("stop", check=False, timeout=30)
                    stop_ok = bool(stopped.get("ok"))
                    if not stop_ok:
                        shutdown["wrapperStopError"] = stopped.get("message", "Wrapper stop failed")
                except (Exception, KeyboardInterrupt) as error:
                    # Wrapper startup or timeout must never bypass owned-process
                    # cleanup. A failed stop request gets immediate fallback.
                    shutdown["wrapperStopError"] = f"{type(error).__name__}: {error}"
                deadline = time.monotonic() + (20 if stop_ok else 0)
                while process_running(self.pid) and time.monotonic() < deadline:
                    time.sleep(0.1)
                for termination, grace in ((signal.SIGTERM, 5), (signal.SIGKILL, 2)):
                    if not process_running(self.pid):
                        break
                    if not owned_process():
                        raise RuntimeError("Refusing to signal a PID not owned by this isolated Karaf")
                    try:
                        os.kill(self.pid, termination)
                        shutdown["terminationSignals"].append(termination.name)
                    except ProcessLookupError:
                        pass
                    deadline = time.monotonic() + grace
                    while process_running(self.pid) and time.monotonic() < deadline:
                        time.sleep(0.1)
        shutdown["stopped"] = not self.pid or not process_running(self.pid)
        self.result["shutdown"] = shutdown
        closed_ports = {}
        for name, port in self.ports.items():
            with socket.socket() as probe:
                probe.settimeout(0.2)
                closed_ports[name] = probe.connect_ex(("127.0.0.1", port)) != 0
        self.result["shutdown"]["closedPorts"] = closed_ports
        try:
            self.doctor()
        except (Exception, KeyboardInterrupt) as error:
            shutdown["doctorError"] = f"{type(error).__name__}: {error}"
        if not self.result["shutdown"]["stopped"] or not all(closed_ports.values()):
            raise RuntimeError("Owned Karaf process or listener did not stop")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repo", required=True, help="Repository containing mvn install outputs")
    parser.add_argument("--state-dir", required=True, help="New or empty owned runtime/evidence directory")
    parser.add_argument("--karaf-archive", help="Cached pristine tar.gz (still SHA256 verified)")
    parser.add_argument("--karaf-version", choices=sorted(KARAF_SHA256), default="4.4.11")
    parser.add_argument("--controller-version", default="0.0.1-SNAPSHOT")
    parser.add_argument("--result-json", help="Additional destination for the complete JSON report")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--reconnects", type=int, default=3)
    args = parser.parse_args(argv)
    if args.reconnects < 2:
        parser.error("--reconnects must be at least 2")
    state = prepare_state(args.state_dir)
    validation = Validation(args, state)
    started = time.monotonic()
    peers = {}
    try:
        with ExitStack() as stack:
            peers = {"healthy": stack.enter_context(FakeDsp(fragment=True, delay=0.002)),
                     "silent": stack.enter_context(FakeDsp(mode="silent")),
                     "drop": stack.enter_context(FakeDsp(mode="drop"))}
            try:
                validation.provision(peers)
                validation.run(peers)
            except (Exception, KeyboardInterrupt) as error:
                validation.result["error"] = f"{type(error).__name__}: {error}"
                validation.result["ok"] = False
                if validation.home:
                    for action in ("bundles", "services", "errors"):
                        try:
                            validation.control(action, check=False, timeout=30)
                        except Exception:
                            pass
            finally:
                try:
                    validation.shutdown()
                except Exception as error:
                    validation.result["shutdownError"] = str(error)
                    validation.result["ok"] = False
    finally:
        validation.result["peers"] = {name: peer.evidence() for name, peer in peers.items()}
        if any(item["forbidden"] or item["errors"] or not item["workersStopped"]
               or not item["acceptThreadStopped"] for item in validation.result["peers"].values()):
            validation.result["ok"] = False
        validation.result["seconds"] = round(time.monotonic() - started, 3)
        result = json.dumps(validation.result, indent=2) + "\n"
        (state / "result.json").write_text(result)
        if args.result_json:
            Path(args.result_json).write_text(result)
        print(result)
    return 0 if validation.result["ok"] else 1


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"Signal {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())

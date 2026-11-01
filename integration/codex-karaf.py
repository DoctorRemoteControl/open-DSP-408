#!/usr/bin/env python3
import argparse
import csv
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
WINDOWS = platform.system().lower().startswith("win")


def script_dir() -> Path:
    return Path(__file__).resolve().parent


KARAF_HOME = script_dir().parent
BIN_DIR = KARAF_HOME / "bin"
DATA_DIR = KARAF_HOME / "data"
LOG_FILE = DATA_DIR / "log" / "karaf.log"
PID_FILE = KARAF_HOME / "karaf.pid"
DEPLOY_DIR = KARAF_HOME / "deploy"
SNAPSHOT_DIR = DATA_DIR / "codex-snapshots"
SHELL_CFG = KARAF_HOME / "etc" / "org.apache.karaf.shell.cfg"
USERS_CFG = KARAF_HOME / "etc" / "users.properties"
LOGGING_CFG = KARAF_HOME / "etc" / "org.ops4j.pax.logging.cfg"
PAX_WEB_CFG = KARAF_HOME / "etc" / "org.ops4j.pax.web.cfg"
COMPLETION_SCRIPT = KARAF_HOME / "etc" / "scripts" / "shell.completion.script"
SECRET_KEY_RE = re.compile(r"(password|passwd|secret|credential|apikey|api\.key|access\.key|private\.key|token|jwt|keystore|truststore)", re.I)


def remove_ansi(text: str) -> str:
    return ANSI_RE.sub("", text or "")


def script_name(base: str) -> Path:
    if WINDOWS:
        bat = BIN_DIR / f"{base}.bat"
        if bat.exists():
            return bat
    return BIN_DIR / base


def run_external(path: Path, args=None, timeout=30, input_text=None):
    args = args or []
    path = str(path)

    if WINDOWS and path.lower().endswith(".bat"):
        popen_args = ["cmd.exe", "/d", "/c", path] + [str(a) for a in args]
        use_shell = False
    else:
        popen_args = [path] + [str(a) for a in args]
        use_shell = False

    try:
        proc = subprocess.run(
            popen_args,
            input=input_text,
            cwd=str(KARAF_HOME),
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
            shell=use_shell,
        )
        return {
            "exitCode": proc.returncode,
            "stdout": proc.stdout or "",
            "stderr": proc.stderr or "",
        }
    except subprocess.TimeoutExpired as exc:
        return {
            "exitCode": 124,
            "stdout": exc.stdout or "",
            "stderr": f"Timed out after {timeout} seconds.",
        }


def start_karaf_detached(timeout=30):
    path = script_name("start")
    if WINDOWS and str(path).lower().endswith(".bat"):
        flags = 0
        if hasattr(subprocess, "CREATE_NEW_PROCESS_GROUP"):
            flags |= subprocess.CREATE_NEW_PROCESS_GROUP
        if hasattr(subprocess, "DETACHED_PROCESS"):
            flags |= subprocess.DETACHED_PROCESS
        subprocess.Popen(
            ["cmd.exe", "/d", "/c", str(path)],
            cwd=str(KARAF_HOME),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=flags,
        )
        return {"exitCode": 0, "stdout": "", "stderr": ""}
    return run_external(path, timeout=timeout)


def socket_probe(host: str, port: int, timeout=1.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def windows_port_listening(port: int) -> bool:
    try:
        proc = subprocess.run(
            ["netstat", "-ano", "-p", "tcp"],
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
            check=False,
        )
    except Exception:
        return False

    port_suffix = f":{port}"
    for line in proc.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].upper() == "TCP":
            local = parts[1]
            pid = parts[-1]
            if local.endswith(port_suffix) and pid.isdigit():
                return True
    return False


def linux_port_listening(port: int) -> bool:
    port_hex = f"{port:04X}"
    for name in ("tcp", "tcp6"):
        table = Path("/proc/net") / name
        if not table.exists():
            continue
        try:
            for line in table.read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
                parts = line.split()
                if len(parts) > 3 and parts[3] == "0A":
                    local = parts[1]
                    if ":" in local and local.rsplit(":", 1)[1].upper() == port_hex:
                        return True
        except OSError:
            continue
    return False


def test_ssh_port(host: str, port: int, timeout=1.5) -> bool:
    if WINDOWS:
        detected = windows_port_listening(port)
        return detected or socket_probe(host, port, timeout)
    if Path("/proc/net/tcp").exists():
        detected = linux_port_listening(port)
        return detected or socket_probe(host, port, timeout)
    return socket_probe(host, port, timeout)


def read_pid():
    if not PID_FILE.exists():
        return None
    text = PID_FILE.read_text(encoding="utf-8", errors="replace").strip()
    try:
        return int(text)
    except ValueError:
        return None


def process_exists(pid: int | None) -> bool:
    if not pid:
        return False
    if WINDOWS:
        try:
            proc = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=5,
                check=False,
            )
            rows = list(csv.reader(proc.stdout.splitlines()))
            return any(len(row) > 1 and row[1].strip() == str(pid) for row in rows)
        except Exception:
            return False
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except OSError:
        return False


def process_name(pid: int | None):
    if not pid or not process_exists(pid):
        return None
    if WINDOWS:
        try:
            proc = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=5,
                check=False,
            )
            rows = list(csv.reader(proc.stdout.splitlines()))
            for row in rows:
                if len(row) > 1 and row[1].strip() == str(pid):
                    return Path(row[0]).stem
        except Exception:
            return None
    else:
        comm = Path("/proc") / str(pid) / "comm"
        if comm.exists():
            return comm.read_text(encoding="utf-8", errors="replace").strip()
    return None


def process_started(pid: int | None):
    if not pid or not process_exists(pid):
        return None
    if not WINDOWS:
        try:
            proc = subprocess.run(
                ["ps", "-p", str(pid), "-o", "lstart="],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=5,
                check=False,
            )
            value = proc.stdout.strip()
            return value or None
        except Exception:
            return None
    return None


def get_status(args):
    pid = read_pid()
    running = process_exists(pid)
    return {
        "karafHome": str(KARAF_HOME),
        "pid": pid,
        "stalePid": bool(pid and not running),
        "processRunning": running,
        "processName": process_name(pid),
        "started": process_started(pid),
        "sshHost": args.host,
        "sshPort": args.port,
        "sshOpen": test_ssh_port(args.host, args.port),
        "logFile": str(LOG_FILE),
    }


def tail_lines(path: Path, lines: int):
    if not path.exists():
        return None
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        all_lines = handle.readlines()
    return "".join(all_lines[-lines:])


def recent_errors(lines=400):
    text = tail_lines(LOG_FILE, lines)
    if text is None:
        return []
    matches = []
    for line in text.splitlines():
        if re.search(r"ERROR|Exception|Caused by:", line):
            matches.append(line)
    return matches[-25:]


def read_properties(path: Path):
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def boolish(value):
    return str(value or "").strip().lower() in ("1", "true", "yes", "on")


def redact_pair(key, value):
    if SECRET_KEY_RE.search(str(key)):
        if value in (None, ""):
            return value
        return "********"
    return value


def redact_mapping(values):
    return {key: redact_pair(key, value) for key, value in values.items()}


def redact_text_line(line: str) -> str:
    if "=" not in line:
        return line
    key, value = line.split("=", 1)
    return f"{key}=********" if SECRET_KEY_RE.search(key) and value.strip() else line


def normalized_key(name: str) -> str:
    compact = re.sub(r"[^A-Za-z0-9]+", " ", name).strip().lower()
    if not compact:
        return "value"
    parts = compact.split()
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


def coerce_value(key: str, value: str):
    text = value.strip()
    if SECRET_KEY_RE.search(key):
        return "********" if text else text
    if key in ("id", "lvl", "level") and re.fullmatch(r"-?\d+", text):
        return int(text)
    if key == "required":
        return text.lower() in ("x", "true", "yes", "1")
    return text


def parse_pipe_table(output: str):
    lines = remove_ansi(output).splitlines()
    header_index = None
    for index, line in enumerate(lines[:-1]):
        if "|" in line and re.search(r"-{2,}\+", lines[index + 1]):
            header_index = index
            break
    if header_index is None:
        return []

    headers = [normalized_key(part) for part in lines[header_index].split("|")]
    rows = []
    for line in lines[header_index + 2:]:
        if not line.strip() or "|" not in line:
            continue
        if re.fullmatch(r"[-+\s]+", line):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) < len(headers):
            continue
        if len(parts) > len(headers):
            parts = parts[:len(headers) - 1] + [" | ".join(parts[len(headers) - 1:])]
        row = {}
        for key, value in zip(headers, parts):
            row[key] = coerce_value(key, value)
        rows.append(row)
    return rows


def capture_karaf_command(args, command_text: str):
    if not test_ssh_port(args.host, args.port):
        status = get_status(args)
        state = "Karaf process is running" if status["processRunning"] else "Karaf process is not running"
        return {
            "ok": False,
            "exitCode": 2,
            "output": "",
            "error": f"{state}, and SSH shell {args.host}:{args.port} is not reachable.",
        }
    result = run_external(
        script_name("client"),
        karaf_client_args(args),
        timeout=args.timeout_sec,
        input_text=command_text + "\n",
    )
    return {
        "ok": result["exitCode"] == 0,
        "exitCode": result["exitCode"],
        "output": remove_ansi((result["stdout"] + result["stderr"]).strip()),
        "error": "" if result["exitCode"] == 0 else (result["stderr"] or result["stdout"]).strip(),
    }


def bundle_snapshot(args):
    captured = capture_karaf_command(args, "bundle:list -t 0")
    rows = parse_pipe_table(captured["output"]) if captured["ok"] else []
    problematic = [
        row for row in rows
        if str(row.get("state", "")).lower() not in ("active", "resolved")
    ]
    return {
        "ok": captured["ok"],
        "exitCode": captured["exitCode"],
        "count": len(rows),
        "problematicCount": len(problematic),
        "problematic": problematic,
        "items": rows,
        "raw": captured["output"] if not rows else None,
        "error": captured["error"],
    }


def feature_snapshot(args):
    captured = capture_karaf_command(args, "feature:list -i")
    rows = parse_pipe_table(captured["output"]) if captured["ok"] else []
    not_started = [
        row for row in rows
        if str(row.get("state", "")).lower() != "started"
    ]
    return {
        "ok": captured["ok"],
        "exitCode": captured["exitCode"],
        "count": len(rows),
        "notStartedCount": len(not_started),
        "notStarted": not_started,
        "items": rows,
        "raw": captured["output"] if not rows else None,
        "error": captured["error"],
    }


def parse_service_list(output: str):
    services = []
    current = None
    section = None
    for raw in remove_ansi(output).splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("client.bat:"):
            continue
        if stripped.startswith("[") and stripped.endswith("]"):
            if current:
                services.append(current)
            interfaces = [part.strip() for part in stripped[1:-1].split(",")]
            current = {"interfaces": interfaces, "properties": {}, "providedBy": [], "usedBy": []}
            section = "properties"
            continue
        if current is None:
            continue
        if re.fullmatch(r"-+", stripped):
            continue
        if stripped == "Provided by :":
            section = "providedBy"
            continue
        if stripped == "Used by:":
            section = "usedBy"
            continue
        if section == "properties" and " = " in stripped:
            key, value = stripped.split(" = ", 1)
            current["properties"][key.strip()] = redact_pair(key.strip(), value.strip())
        elif section == "providedBy":
            current["providedBy"].append(stripped)
        elif section == "usedBy":
            current["usedBy"].append(stripped)
    if current:
        services.append(current)
    return services


def service_snapshot(args):
    captured = capture_karaf_command(args, "service:list")
    services = parse_service_list(captured["output"]) if captured["ok"] else []
    by_bundle = {}
    for service in services:
        bundle_id = service["properties"].get("service.bundleid")
        if bundle_id is not None:
            by_bundle[str(bundle_id)] = by_bundle.get(str(bundle_id), 0) + 1
    return {
        "ok": captured["ok"],
        "exitCode": captured["exitCode"],
        "count": len(services),
        "byBundle": by_bundle,
        "items": services,
        "raw": captured["output"] if captured["ok"] and not services else None,
        "error": captured["error"],
    }


def config_snapshot():
    files = []
    etc = KARAF_HOME / "etc"
    for path in sorted(etc.glob("*.cfg")):
        props = read_properties(path)
        files.append({
            "pid": path.stem,
            "path": str(path),
            "properties": redact_mapping(props),
        })

    users = []
    if USERS_CFG.exists():
        for raw in USERS_CFG.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                first = value.split(",", 1)[0].strip()
                users.append({
                    "user": key.strip(),
                    "password": "********" if first else "",
                    "roles": value.split(",", 1)[1].strip() if "," in value else "",
                })

    return {
        "count": len(files),
        "files": files,
        "users": users,
    }


def command_catalog(args):
    commands = {}
    if COMPLETION_SCRIPT.exists():
        for line in COMPLETION_SCRIPT.read_text(encoding="utf-8", errors="replace").splitlines():
            match = re.search(r"complete\s+-c\s+([^\s]+)(?:.*?\s-d\s+\"([^\"]*)\")?", line)
            if match:
                name = match.group(1)
                description = match.group(2) or commands.get(name, {}).get("description", "")
                commands[name] = {"name": name, "description": description, "source": "completion-script"}

    common = {
        "bundle:list": "Lists all installed bundles.",
        "bundle:start": "Starts bundles.",
        "bundle:stop": "Stops bundles.",
        "bundle:restart": "Restarts bundles.",
        "bundle:install": "Installs a bundle.",
        "bundle:uninstall": "Uninstalls bundles.",
        "feature:list": "Lists features.",
        "feature:install": "Installs a feature.",
        "feature:uninstall": "Uninstalls a feature.",
        "config:list": "Lists ConfigAdmin configurations.",
        "config:property-set": "Sets a configuration property.",
        "config:property-delete": "Deletes a configuration property.",
        "service:list": "Lists OSGi services.",
        "log:tail": "Tails the Karaf log.",
        "log:display": "Displays recent log entries.",
        "shell:info": "Displays JVM and Karaf information.",
        "system:shutdown": "Shuts down Karaf.",
        "http:proxy-list": "Lists configured HTTP proxies.",
    }
    for name, description in common.items():
        commands.setdefault(name, {"name": name, "description": description, "source": "known-karaf"})

    if test_ssh_port(args.host, args.port):
        services = service_snapshot(args)
        if services["ok"]:
            for service in services["items"]:
                props = service.get("properties", {})
                scope = props.get("osgi.command.scope")
                functions = props.get("osgi.command.function")
                if not scope or not functions:
                    continue
                text = str(functions).strip()
                if text.startswith("[") and text.endswith("]"):
                    text = text[1:-1]
                for function in re.split(r"\s*,\s*", text):
                    function = function.strip()
                    if function:
                        name = f"{scope}:{function}"
                        commands.setdefault(name, {
                            "name": name,
                            "description": "Dynamically registered OSGi shell command.",
                            "source": "osgi-service",
                        })

    return {"count": len(commands), "items": sorted(commands.values(), key=lambda item: item["name"])}


def parse_shell_info(output: str):
    info = {"sections": {}}
    current = None
    for raw in remove_ansi(output).splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("client.bat:"):
            continue
        if stripped in ("Karaf", "JVM", "Threads", "Memory", "Classes", "Operating system"):
            current = stripped
            info["sections"].setdefault(current, {})
            continue
        if current and "  " in line:
            match = re.match(r"\s{2,}(.+?)\s{2,}(.+)$", line)
            if match:
                key = normalized_key(match.group(1))
                value = match.group(2).strip()
                existing = info["sections"][current].get(key)
                if existing is None:
                    info["sections"][current][key] = value
                elif isinstance(existing, list):
                    existing.append(value)
                else:
                    info["sections"][current][key] = [existing, value]
    return info


def jvm_snapshot(args):
    captured = capture_karaf_command(args, "shell:info")
    parsed = parse_shell_info(captured["output"]) if captured["ok"] else {}
    return {
        "ok": captured["ok"],
        "exitCode": captured["exitCode"],
        "info": parsed,
        "raw": captured["output"] if not parsed else None,
        "error": captured["error"],
    }


def http_snapshot(args):
    cfg = redact_mapping(read_properties(PAX_WEB_CFG))
    services = service_snapshot(args) if test_ssh_port(args.host, args.port) else None
    endpoints = []
    http_services = []
    if services and services["ok"]:
        for service in services["items"]:
            props = service.get("properties", {})
            if "osgi.http.endpoint" in props:
                endpoints.append(props.get("osgi.http.endpoint"))
            if any("HttpService" in iface or "WebContainer" in iface or "HttpServiceRuntime" in iface for iface in service.get("interfaces", [])):
                http_services.append(service)
    return {
        "config": cfg,
        "endpoints": endpoints,
        "serviceCount": len(http_services),
        "services": http_services,
    }


def memory_snapshot(args):
    jvm = jvm_snapshot(args)
    sections = jvm.get("info", {}).get("sections", {})
    return {
        "ok": jvm["ok"],
        "exitCode": jvm["exitCode"],
        "memory": sections.get("Memory", {}),
        "classes": sections.get("Classes", {}),
        "jvm": sections.get("JVM", {}),
        "error": jvm["error"],
    }


def thread_snapshot(args):
    jvm = jvm_snapshot(args)
    sections = jvm.get("info", {}).get("sections", {})
    return {
        "ok": jvm["ok"],
        "exitCode": jvm["exitCode"],
        "threads": sections.get("Threads", {}),
        "jvm": sections.get("JVM", {}),
        "error": jvm["error"],
    }


def log_events(lines=400):
    text = tail_lines(LOG_FILE, lines)
    if text is None:
        return {
            "logFile": str(LOG_FILE),
            "linesScanned": 0,
            "matches": [],
            "counts": {},
        }

    matches = []
    counts = {"ERROR": 0, "WARN": 0, "Exception": 0, "Caused by": 0, "smtpLocalhost25": 0}
    split = text.splitlines()
    for offset, line in enumerate(split, 1):
        selected = False
        if "ERROR" in line:
            counts["ERROR"] += 1
            selected = True
        if "WARN" in line:
            counts["WARN"] += 1
            selected = True
        if "Exception" in line:
            counts["Exception"] += 1
            selected = True
        if "Caused by:" in line:
            counts["Caused by"] += 1
            selected = True
        if "localhost, 25" in line or "localhost:25" in line:
            counts["smtpLocalhost25"] += 1
            selected = True
        if selected:
            matches.append({"offset": offset, "line": line})

    return {
        "logFile": str(LOG_FILE),
        "linesScanned": len(split),
        "matches": matches[-100:],
        "counts": counts,
    }


def add_check(checks, level, check_id, message, details=None):
    checks.append({
        "level": level,
        "id": check_id,
        "message": message,
        "details": details or {},
    })


def doctor_report(args):
    status = get_status(args)
    shell_cfg = read_properties(SHELL_CFG)
    users_cfg = read_properties(USERS_CFG)
    logging_cfg = read_properties(LOGGING_CFG)
    events = log_events(max(args.lines, 400))
    bundles = bundle_snapshot(args) if status["sshOpen"] else None
    features = feature_snapshot(args) if status["sshOpen"] else None
    checks = []

    add_check(checks, "ok" if status["processRunning"] else "error", "process.running",
              "Karaf process is running." if status["processRunning"] else "Karaf process is not running.",
              {"pid": status["pid"], "stalePid": status["stalePid"]})
    add_check(checks, "ok" if status["sshOpen"] else "error", "ssh.reachable",
              f"SSH listener is reachable at {args.host}:{args.port}." if status["sshOpen"] else f"SSH listener is not reachable at {args.host}:{args.port}.")

    ssh_host = shell_cfg.get("sshHost")
    if ssh_host in ("0.0.0.0", "::", ""):
        add_check(checks, "warn", "security.sshHost", "Karaf SSH is configured to bind broadly; prefer 127.0.0.1 for local Codex control.", {"sshHost": ssh_host})
    else:
        add_check(checks, "ok", "security.sshHost", "Karaf SSH bind address is restricted.", {"sshHost": ssh_host})

    if boolish(shell_cfg.get("sftpEnabled")):
        add_check(checks, "warn", "security.sftp", "SFTP is enabled on the Karaf SSH server; disable it if Codex only needs shell commands.")
    else:
        add_check(checks, "ok", "security.sftp", "SFTP is disabled.")

    karaf_user = users_cfg.get("karaf", "")
    if karaf_user.split(",", 1)[0].strip() == "karaf":
        add_check(checks, "warn", "security.defaultPassword", "Default karaf/karaf credentials are still configured.")
    else:
        add_check(checks, "ok", "security.defaultPassword", "Default karaf/karaf credentials were not detected.")

    if not boolish(shell_cfg.get("disableLogout")) or not boolish(shell_cfg.get("disableEofExit")):
        add_check(checks, "info", "codex.shellExit", "Consider disableLogout=true and disableEofExit=true to avoid accidental interactive shell exits.")

    if events["counts"].get("smtpLocalhost25", 0):
        add_check(checks, "warn", "app.smtp", "Recent logs show SMTP connection failures to localhost:25.", {"count": events["counts"]["smtpLocalhost25"]})

    if bundles and bundles["ok"]:
        level = "ok" if bundles["problematicCount"] == 0 else "warn"
        add_check(checks, level, "osgi.bundles", f"{bundles['count']} bundles parsed; {bundles['problematicCount']} problematic states.", {"problematic": bundles["problematic"][:10]})
    elif status["sshOpen"]:
        add_check(checks, "warn", "osgi.bundles", "Could not parse bundle state.", {"error": bundles["error"] if bundles else ""})

    if features and features["ok"]:
        level = "ok" if features["notStartedCount"] == 0 else "warn"
        add_check(checks, level, "karaf.features", f"{features['count']} installed features parsed; {features['notStartedCount']} not started.", {"notStarted": features["notStarted"][:10]})
    elif status["sshOpen"]:
        add_check(checks, "warn", "karaf.features", "Could not parse feature state.", {"error": features["error"] if features else ""})

    rolling_size = logging_cfg.get("log4j2.appender.rolling.policies.size.size")
    add_check(checks, "ok" if rolling_size else "info", "logging.rotation",
              "Karaf log rotation is configured." if rolling_size else "Karaf log rotation size was not detected.",
              {"size": rolling_size})

    summary = {"ok": 0, "info": 0, "warn": 0, "error": 0}
    for check in checks:
        summary[check["level"]] = summary.get(check["level"], 0) + 1

    return {
        "summary": summary,
        "status": status,
        "checks": checks,
        "log": {
            "counts": events["counts"],
            "recentMatches": events["matches"][-25:],
        },
        "bundles": None if bundles is None else {
            "ok": bundles["ok"],
            "count": bundles["count"],
            "problematicCount": bundles["problematicCount"],
            "problematic": bundles["problematic"][:25],
        },
        "features": None if features is None else {
            "ok": features["ok"],
            "count": features["count"],
            "notStartedCount": features["notStartedCount"],
            "notStarted": features["notStarted"][:25],
        },
        "config": {
            "shell": {
                "sshHost": shell_cfg.get("sshHost"),
                "sshPort": shell_cfg.get("sshPort"),
                "sshIdleTimeout": shell_cfg.get("sshIdleTimeout"),
                "sftpEnabled": shell_cfg.get("sftpEnabled"),
                "disableLogout": shell_cfg.get("disableLogout"),
                "disableEofExit": shell_cfg.get("disableEofExit"),
            },
            "logging": {
                "rootLevel": logging_cfg.get("log4j2.rootLogger.level"),
                "rollingSize": logging_cfg.get("log4j2.appender.rolling.policies.size.size"),
            },
        },
    }


def timestamp():
    return time.strftime("%Y%m%d-%H%M%S")


def backup_file(path: Path):
    if not path.exists():
        return None
    backup = path.with_name(f"{path.name}.codex-backup-{timestamp()}")
    shutil.copy2(path, backup)
    return str(backup)


def update_properties_file(path: Path, updates: dict, dry_run=False):
    original = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
    lines = original.splitlines()
    seen = set()
    changed = False
    new_lines = []

    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key, value = line.split("=", 1)
            normalized = key.strip()
            if normalized in updates:
                prefix = key.rstrip()
                replacement = f"{prefix} = {updates[normalized]}" if " = " in line else f"{prefix}={updates[normalized]}"
                new_lines.append(replacement)
                seen.add(normalized)
                changed = changed or replacement != line
                continue
        new_lines.append(line)

    for key, value in updates.items():
        if key not in seen:
            new_lines.append(f"{key} = {value}")
            changed = True

    backup = None
    if changed and not dry_run:
        backup = backup_file(path)
        path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    return {
        "path": str(path),
        "changed": changed,
        "backup": backup,
        "updates": updates,
        "dryRun": dry_run,
    }


def apply_dev_safe(args):
    shell_updates = {
        "sshHost": "127.0.0.1",
        "sftpEnabled": "false",
        "disableLogout": "true",
        "disableEofExit": "true",
    }
    result = {
        "shell": update_properties_file(SHELL_CFG, shell_updates, dry_run=args.dry_run),
        "notes": [
            "Default karaf/karaf credentials are reported by doctor but not changed automatically.",
            "Application mail/scheduler behavior is reported by doctor but not changed automatically.",
            "Restart Karaf or let ConfigAdmin reload the file before relying on changed SSH settings.",
        ],
    }
    return result


def bundle_matches(bundle, needle: str):
    needle = needle.lower()
    candidates = [
        str(bundle.get("id", "")),
        str(bundle.get("name", "")),
        str(bundle.get("symbolicName", "")),
        str(bundle.get("location", "")),
    ]
    return any(needle in value.lower() for value in candidates)


def wait_bundle(args, needle: str, desired_state: str):
    deadline = time.monotonic() + args.timeout_sec
    last_matches = []
    while time.monotonic() < deadline:
        snapshot = bundle_snapshot(args)
        if not snapshot["ok"]:
            return {"ok": False, "message": "Could not read bundles.", "snapshot": snapshot}
        last_matches = [bundle for bundle in snapshot["items"] if bundle_matches(bundle, needle)]
        for bundle in last_matches:
            if str(bundle.get("state", "")).lower() == desired_state.lower():
                return {"ok": True, "message": f"Bundle matched state {desired_state}.", "bundle": bundle}
        time.sleep(2)
    return {
        "ok": False,
        "message": f"Timed out waiting for bundle '{needle}' to reach state {desired_state}.",
        "matches": last_matches,
    }


def deploy_artifact(args):
    if not args.command:
        return {"ok": False, "message": "deploy needs a path to a jar/kar/war file.", "exitCode": 64}
    source = Path(args.command[0]).resolve()
    if not source.exists() or not source.is_file():
        return {"ok": False, "message": f"Artifact not found: {source}", "exitCode": 66}
    DEPLOY_DIR.mkdir(parents=True, exist_ok=True)
    target = DEPLOY_DIR / source.name
    if target.exists() and not args.force:
        return {"ok": False, "message": f"Deploy target already exists: {target}. Use --force to overwrite.", "exitCode": 73}
    if not args.dry_run:
        shutil.copy2(source, target)
    result = {"ok": True, "source": str(source), "target": str(target), "dryRun": args.dry_run}
    if args.wait_active:
        result["wait"] = wait_bundle(args, source.stem, args.state)
        result["ok"] = result["wait"]["ok"]
    return result


def undeploy_artifact(args):
    if not args.command:
        return {"ok": False, "message": "undeploy needs a deploy file name or name fragment.", "exitCode": 64}
    needle = " ".join(args.command).lower()
    matches = []
    if DEPLOY_DIR.exists():
        for path in DEPLOY_DIR.iterdir():
            if path.is_file() and needle in path.name.lower():
                matches.append(path)
    if not matches:
        return {"ok": False, "message": f"No deploy artifact matched: {needle}", "exitCode": 66}
    if len(matches) > 1 and not args.force:
        return {"ok": False, "message": "Multiple artifacts matched. Use --force or a more specific name.", "matches": [str(path) for path in matches], "exitCode": 64}
    removed = []
    for path in matches:
        removed.append(str(path))
        if not args.dry_run:
            path.unlink()
    return {"ok": True, "removed": removed, "dryRun": args.dry_run}


def create_snapshot(args):
    if args.output:
        target = Path(args.output).resolve()
    else:
        SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
        target = SNAPSHOT_DIR / f"snapshot-{timestamp()}"
    target.mkdir(parents=True)

    artifacts = {
        "status.json": {"ok": True, "data": get_status(args)},
        "doctor.json": {"ok": True, "data": doctor_report(args)},
        "bundles.json": {"ok": True, "data": bundle_snapshot(args)},
        "features.json": {"ok": True, "data": feature_snapshot(args)},
        "configs.json": {"ok": True, "data": config_snapshot()},
        "services.json": {"ok": True, "data": service_snapshot(args)},
        "http.json": {"ok": True, "data": http_snapshot(args)},
        "memory.json": {"ok": True, "data": memory_snapshot(args)},
        "threads.json": {"ok": True, "data": thread_snapshot(args)},
        "errors.json": {"ok": True, "data": log_events(args.lines)},
    }
    for name, payload in artifacts.items():
        (target / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    log_tail = tail_lines(LOG_FILE, args.lines)
    if log_tail is not None:
        (target / "karaf-log-tail.txt").write_text(log_tail, encoding="utf-8")

    readme = [
        "# Karaf Codex Snapshot",
        "",
        f"Created: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"Karaf home: {KARAF_HOME}",
        "",
        "Open `doctor.json` first, then drill into bundles/features/services/configs/errors.",
    ]
    (target / "README.md").write_text("\n".join(readme) + "\n", encoding="utf-8")

    archive = None
    if not args.no_zip:
        archive = shutil.make_archive(str(target), "zip", root_dir=target)
    return {"directory": str(target), "zip": archive}


def print_map(value, prefix=""):
    if isinstance(value, dict):
        for key, item in value.items():
            if isinstance(item, (dict, list)):
                print(f"{prefix}{key}:")
                print_map(item, prefix + "  ")
            else:
                print(f"{prefix}{key}: {item if item is not None else ''}")
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, dict) and {"level", "id", "message"}.issubset(item.keys()):
                print(f"{prefix}- {item['level'].upper()} {item['id']}: {item['message']}")
                details = item.get("details") or {}
                if details:
                    print_map(details, prefix + "    ")
            elif isinstance(item, dict):
                print(f"{prefix}-")
                print_map(item, prefix + "  ")
            else:
                print(f"{prefix}- {item}")
    else:
        print(f"{prefix}{value}")


def write_result(args, ok, message="", data=None, exit_code=0):
    if args.json:
        print(json.dumps({
            "ok": ok,
            "action": args.action,
            "message": message,
            "data": data,
            "exitCode": exit_code,
        }, ensure_ascii=False, indent=2))
    else:
        if message:
            print(message)
        if data is not None:
            if isinstance(data, str):
                print(data)
            else:
                print_map(data)
    if not ok:
        sys.exit(exit_code or 1)


def karaf_client_args(args):
    return [
        "-h", args.host,
        "-a", str(args.port),
        "-u", args.user,
        "-p", args.password,
        "-t", str(args.timeout_sec * 1000),
        "-b",
    ]


def invoke_karaf_command(args, command_text: str):
    if not test_ssh_port(args.host, args.port):
        status = get_status(args)
        state = "Karaf process is running" if status["processRunning"] else "Karaf process is not running"
        write_result(
            args,
            False,
            f"{state}, and SSH shell {args.host}:{args.port} is not reachable. Use 'health' or 'logs' for diagnostics.",
            status,
            2,
        )

    result = run_external(
        script_name("client"),
        karaf_client_args(args),
        timeout=args.timeout_sec,
        input_text=command_text + "\n",
    )
    text = remove_ansi((result["stdout"] + result["stderr"]).strip())
    write_result(args, result["exitCode"] == 0, "" if result["exitCode"] == 0 else "Karaf command failed.", text, result["exitCode"])


def action_help():
    print("""Codex-friendly Karaf control

Usage:
  python bin/codex-karaf.py status [--json]
  python bin/codex-karaf.py health [--json]
  python bin/codex-karaf.py doctor [--json]
  python bin/codex-karaf.py bundles --json
  python bin/codex-karaf.py features --json
  python bin/codex-karaf.py configs --json
  python bin/codex-karaf.py services --json
  python bin/codex-karaf.py commands --json
  python bin/codex-karaf.py http --json
  python bin/codex-karaf.py memory --json
  python bin/codex-karaf.py threads --json
  python bin/codex-karaf.py errors --lines 400 [--json]
  python bin/codex-karaf.py exec "bundle:list -t 0"
  python bin/codex-karaf.py batch --file ./commands.txt
  python bin/codex-karaf.py deploy ./target/app.jar --wait-active
  python bin/codex-karaf.py undeploy app.jar
  python bin/codex-karaf.py wait-bundle "IAM Application" --state Active
  python bin/codex-karaf.py snapshot --lines 800
  python bin/codex-karaf.py apply-dev-safe --dry-run
  python bin/codex-karaf.py logs --lines 200
  python bin/codex-karaf.py tail --lines 80
  python bin/codex-karaf.py start
  python bin/codex-karaf.py stop
  python bin/codex-karaf.py restart
  python bin/codex-karaf.py safe-restart --timeout-sec 90 --json
  python bin/codex-karaf.py wait-ssh --timeout-sec 60

Environment:
  KARAF_USER and KARAF_PASSWORD override the default karaf/karaf login.
""")


def follow_log(path: Path, lines: int):
    text = tail_lines(path, lines)
    if text:
        print(text, end="")
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        handle.seek(0, os.SEEK_END)
        while True:
            line = handle.readline()
            if line:
                print(line, end="")
            else:
                time.sleep(0.5)


def parse_args(argv):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("action", nargs="?", default="help", choices=[
        "help", "status", "health", "doctor", "bundles", "features", "configs",
        "services", "commands", "http", "memory", "threads", "errors",
        "exec", "batch", "deploy", "undeploy", "wait-bundle", "snapshot", "apply-dev-safe",
        "logs", "tail", "start", "stop", "restart", "safe-restart", "wait-ssh",
    ])
    parser.add_argument("command", nargs="*")
    parser.add_argument("--file", "-f", "-File", dest="file")
    parser.add_argument("--host", "--host-name", "-HostName", dest="host", default="127.0.0.1")
    parser.add_argument("--port", "-Port", dest="port", type=int, default=8101)
    parser.add_argument("--user", "-User", dest="user", default=os.environ.get("KARAF_USER", "karaf"))
    parser.add_argument("--password", "-Password", dest="password", default=os.environ.get("KARAF_PASSWORD", "karaf"))
    parser.add_argument("--timeout-sec", "-TimeoutSec", dest="timeout_sec", type=int, default=30)
    parser.add_argument("--lines", "-Lines", dest="lines", type=int, default=120)
    parser.add_argument("--state", "-State", dest="state", default="Active")
    parser.add_argument("--output", "-Output", dest="output")
    parser.add_argument("--wait-active", "-WaitActive", dest="wait_active", action="store_true")
    parser.add_argument("--dry-run", "-DryRun", dest="dry_run", action="store_true")
    parser.add_argument("--force", "-Force", dest="force", action="store_true")
    parser.add_argument("--no-zip", "-NoZip", dest="no_zip", action="store_true")
    parser.add_argument("--json", "-Json", dest="json", action="store_true")
    parser.add_argument("--help", "-h", action="store_true", dest="show_help")
    args, unknown = parser.parse_known_args(argv)
    args.command.extend(unknown)
    if args.show_help:
        args.action = "help"
    return args


def main(argv=None):
    args = parse_args(argv or sys.argv[1:])

    if args.action == "help":
        action_help()
    elif args.action == "status":
        status = get_status(args)
        write_result(args, True, "Running." if status["processRunning"] else "Not running.", status)
    elif args.action == "health":
        write_result(args, True, "Health snapshot.", {
            "status": get_status(args),
            "recentErrors": recent_errors(),
        })
    elif args.action == "doctor":
        report = doctor_report(args)
        ok = report["summary"].get("error", 0) == 0
        write_result(args, ok, "Doctor report.", report, 1 if not ok else 0)
    elif args.action == "bundles":
        snapshot = bundle_snapshot(args)
        if args.json:
            write_result(args, snapshot["ok"], "Bundle snapshot.", snapshot, snapshot["exitCode"])
        else:
            captured = capture_karaf_command(args, "bundle:list -t 0")
            write_result(args, captured["ok"], "" if captured["ok"] else "Bundle command failed.", captured["output"] or captured["error"], captured["exitCode"])
    elif args.action == "features":
        snapshot = feature_snapshot(args)
        if args.json:
            write_result(args, snapshot["ok"], "Feature snapshot.", snapshot, snapshot["exitCode"])
        else:
            captured = capture_karaf_command(args, "feature:list -i")
            write_result(args, captured["ok"], "" if captured["ok"] else "Feature command failed.", captured["output"] or captured["error"], captured["exitCode"])
    elif args.action == "configs":
        write_result(args, True, "Config snapshot.", config_snapshot())
    elif args.action == "services":
        snapshot = service_snapshot(args)
        write_result(args, snapshot["ok"], "Service snapshot.", snapshot, snapshot["exitCode"])
    elif args.action == "commands":
        write_result(args, True, "Command catalog.", command_catalog(args))
    elif args.action == "http":
        write_result(args, True, "HTTP snapshot.", http_snapshot(args))
    elif args.action == "memory":
        snapshot = memory_snapshot(args)
        write_result(args, snapshot["ok"], "Memory snapshot.", snapshot, snapshot["exitCode"])
    elif args.action == "threads":
        snapshot = thread_snapshot(args)
        write_result(args, snapshot["ok"], "Thread snapshot.", snapshot, snapshot["exitCode"])
    elif args.action == "errors":
        events = log_events(args.lines)
        write_result(args, True, "Log error snapshot.", events)
    elif args.action == "exec":
        command = " ".join(args.command).strip()
        if not command:
            write_result(args, False, 'exec needs a Karaf command, for example: exec "bundle:list -t 0".', None, 64)
        invoke_karaf_command(args, command)
    elif args.action == "batch":
        if not args.file:
            write_result(args, False, "batch needs --file <commands.txt>.", None, 64)
        path = Path(args.file).resolve()
        if not path.exists():
            write_result(args, False, f"Command file not found: {path}", None, 66)
        invoke_karaf_command(args, path.read_text(encoding="utf-8", errors="replace"))
    elif args.action == "deploy":
        result = deploy_artifact(args)
        write_result(args, result["ok"], "Deploy result.", result, result.get("exitCode", 0 if result["ok"] else 1))
    elif args.action == "undeploy":
        result = undeploy_artifact(args)
        write_result(args, result["ok"], "Undeploy result.", result, result.get("exitCode", 0 if result["ok"] else 1))
    elif args.action == "wait-bundle":
        if not args.command:
            write_result(args, False, "wait-bundle needs a bundle id/name fragment.", None, 64)
        result = wait_bundle(args, " ".join(args.command), args.state)
        write_result(args, result["ok"], result["message"], result, 0 if result["ok"] else 124)
    elif args.action == "snapshot":
        result = create_snapshot(args)
        write_result(args, True, "Snapshot created.", result)
    elif args.action == "apply-dev-safe":
        result = apply_dev_safe(args)
        write_result(args, True, "Dev-safe settings prepared." if args.dry_run else "Dev-safe settings applied.", result)
    elif args.action == "logs":
        text = tail_lines(LOG_FILE, args.lines)
        if text is None:
            write_result(args, False, f"Log file not found: {LOG_FILE}", None, 66)
        write_result(args, True, "", text)
    elif args.action == "tail":
        if not LOG_FILE.exists():
            write_result(args, False, f"Log file not found: {LOG_FILE}", None, 66)
        follow_log(LOG_FILE, args.lines)
    elif args.action == "start":
        result = start_karaf_detached(timeout=args.timeout_sec)
        text = remove_ansi((result["stdout"] + result["stderr"]).strip())
        write_result(args, result["exitCode"] == 0, "Start requested.", text, result["exitCode"])
    elif args.action == "stop":
        result = run_external(script_name("stop"), timeout=args.timeout_sec)
        text = remove_ansi((result["stdout"] + result["stderr"]).strip())
        write_result(args, result["exitCode"] == 0, "Stop requested.", text, result["exitCode"])
    elif args.action == "restart":
        stop = run_external(script_name("stop"), timeout=args.timeout_sec)
        time.sleep(3)
        start = start_karaf_detached(timeout=args.timeout_sec)
        write_result(args, start["exitCode"] == 0, "Restart requested.", {"stop": stop, "start": start}, start["exitCode"])
    elif args.action == "safe-restart":
        stop = run_external(script_name("stop"), timeout=args.timeout_sec)
        deadline = time.monotonic() + args.timeout_sec
        while time.monotonic() < deadline and get_status(args)["processRunning"]:
            time.sleep(1)
        start = start_karaf_detached(timeout=args.timeout_sec)
        deadline = time.monotonic() + args.timeout_sec
        while time.monotonic() < deadline:
            if test_ssh_port(args.host, args.port):
                break
            time.sleep(1)
        report = doctor_report(args)
        while report["summary"].get("error", 0) > 0 and time.monotonic() < deadline:
            time.sleep(2)
            report = doctor_report(args)
        write_result(args, report["summary"].get("error", 0) == 0, "Safe restart completed.", {"stop": stop, "start": start, "doctor": report}, 0 if report["summary"].get("error", 0) == 0 else 1)
    elif args.action == "wait-ssh":
        deadline = time.monotonic() + args.timeout_sec
        while time.monotonic() < deadline:
            if test_ssh_port(args.host, args.port):
                write_result(args, True, f"Karaf SSH shell is reachable at {args.host}:{args.port}.", get_status(args))
                return
            time.sleep(1)
        write_result(args, False, f"Timed out waiting for Karaf SSH shell at {args.host}:{args.port}.", get_status(args), 124)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run the Controller against an explicit target through an exact read-only guard."""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import shutil
import signal
import os
import threading
import time
from connection_stress import run_owned

FIR_WINDOWS = {0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181}

def encode(payload: bytes) -> bytes:
    frame = b'\x10\x02' + payload + b'\x10\x03'
    checksum = 0
    for byte in frame:
        checksum ^= byte
    return frame + bytes([checksum])


def validate_request(payload: bytes) -> None:
    if len(payload) < 4 or payload[:2] != b'\x00\x01' or payload[2] + 3 != len(payload):
        raise ValueError('Invalid request header/length')
    command = payload[3]
    if command in {0x10, 0x13, 0x2C, 0x22, 0x14, 0x12, 0x40} and len(payload) == 4:
        return
    if command == 0x27 and len(payload) == 5 and payload[4] <= 0x1F:
        return
    if command == 0x29 and len(payload) == 5 and payload[4] <= 0x13:
        return
    if (command == 0x56 and len(payload) == 7 and payload[4] == 0
            and int.from_bytes(payload[5:7], 'little') in FIR_WINDOWS):
        return
    raise ValueError(f'Request is outside the read-only allowlist: {payload.hex()}')


class FrameStream:
    def __init__(self):
        self.pending = bytearray()

    def accept(self, chunk: bytes) -> list[bytes]:
        self.pending.extend(chunk)
        frames = []
        while self.pending:
            if self.pending[0] != 0x10 or len(self.pending) > 1 and self.pending[1] != 0x02:
                raise ValueError('Unexpected bytes outside a framed packet')
            if len(self.pending) < 5:
                break
            length = self.pending[4] + 8
            if length < 9:
                raise ValueError("A DSP response must contain a command byte")
            if len(self.pending) < length:
                break
            frame = bytes(self.pending[:length])
            if frame[-3:-1] != b'\x10\x03':
                raise ValueError('Invalid frame trailer')
            checksum = 0
            for byte in frame:
                checksum ^= byte
            if checksum:
                raise ValueError('Invalid frame checksum')
            del self.pending[:length]
            frames.append(frame)
        return frames


class GuardProxy:
    def __init__(self, host: str, port: int, output: Path):
        self.target = (host, port)
        self.server = socket.socket()
        self.server.bind(('127.0.0.1', 0))
        self.server.listen(4)
        self.server.settimeout(0.25)
        self.port = self.server.getsockname()[1]
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.sockets: set[socket.socket] = set()
        self.threads: list[threading.Thread] = []
        self.wire = (output / 'wire.ndjson').open('w')
        self.counts = collections.Counter()
        self.errors: list[str] = []
        self.response_ms: list[float] = []
        self.start = time.monotonic()

    def launch(self, function, *args):
        thread = threading.Thread(target=function, args=args, daemon=True)
        self.threads.append(thread)
        thread.start()

    def __enter__(self):
        self.launch(self.accept)
        return self

    def accept(self):
        while not self.stop.is_set():
            try:
                client, _ = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            with self.lock:
                self.sockets.add(client)
                self.counts['connections'] += 1
                connection_id = self.counts['connections']
            self.launch(self.connection, client, connection_id)

    def connection(self, client, connection_id):
        target = None
        try:
            target = socket.create_connection(self.target, timeout=3)
            target.settimeout(None)
            with self.lock:
                self.sockets.add(target)
            pending = {'request': None, 'sent': 0.0}
            self.launch(self.relay, target, client, 'rx', connection_id, pending)
            self.relay(client, target, 'tx', connection_id, pending)
        except OSError as error:
            if not self.stop.is_set():
                with self.lock:
                    self.errors.append(f'Connection {connection_id}: {error}')
        finally:
            self.close_socket(client)
            if target:
                self.close_socket(target)

    def close_socket(self, sock):
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        sock.close()
        with self.lock:
            self.sockets.discard(sock)

    def relay(self, source, destination, direction, connection_id, pending):
        stream = FrameStream()
        try:
            while not self.stop.is_set():
                chunk = source.recv(4096)
                if not chunk:
                    if stream.pending:
                        raise ValueError('EOF inside a frame')
                    break
                for frame in stream.accept(chunk):
                    payload = frame[2:-3]
                    if direction == 'tx':
                        validate_request(payload)
                    now = time.monotonic()
                    with self.lock:
                        self.counts[f'{direction}_frames'] += 1
                        self.counts[f'{direction}_command_{payload[3]:02x}'] += 1
                        event = {'seconds': round(now - self.start, 6), 'connection': connection_id,
                                 'direction': direction, 'payload': payload.hex()}
                        if direction == 'tx':
                            pending.update(request=payload, sent=now)
                        elif pending['request'] is not None and self.matches(pending['request'], payload):
                            elapsed = (now - pending['sent']) * 1000
                            event['response_ms'] = round(elapsed, 3)
                            self.response_ms.append(elapsed)
                            pending['request'] = None
                        self.wire.write(json.dumps(event) + '\n')
                        self.wire.flush()
                    destination.sendall(frame)
        except (OSError, ValueError) as error:
            # Normal local close/reset during an owned reconnect is not a protocol failure.
            if not self.stop.is_set() and isinstance(error, ValueError):
                with self.lock:
                    self.errors.append(f'{direction} connection {connection_id}: {error}')
        finally:
            self.close_socket(source)
            self.close_socket(destination)

    @staticmethod
    def matches(request, response):
        if request[3] == 0x27:
            return len(response) > 4 and response[3] == 0x24 and request[4] == response[4]
        if request[3] == 0x29:
            return len(response) > 4 and response[3] == 0x29 and request[4] == response[4]
        return response[3] == (0x01 if request[3] == 0x12 else request[3])

    def report(self):
        with self.lock:
            samples = sorted(self.response_ms)
            percentile = lambda fraction: round(samples[min(len(samples)-1, int((len(samples)-1)*fraction))], 3) if samples else None
            return {'counts': dict(self.counts), 'errors': list(self.errors),
                    'matched_responses': len(samples), 'response_ms': {'p50': percentile(.5),
                    'p95': percentile(.95), 'p99': percentile(.99), 'max': percentile(1)},
                    'open_owned_sockets': len(self.sockets),
                    'live_proxy_threads': sum(thread.is_alive() for thread in self.threads)}

    def __exit__(self, *args):
        self.stop.set()
        self.server.close()
        with self.lock:
            sockets = list(self.sockets)
        for sock in sockets:
            self.close_socket(sock)
        for thread in self.threads:
            thread.join(timeout=2)
        self.wire.close()


def snapshot_artifacts(sources, directory):
    directory.mkdir()
    copied, hashes = [], {}
    for index, source in enumerate(sources):
        destination = directory / f"{index}-{source.name}"
        shutil.copy2(source, destination)
        copied.append(destination)
        hashes[str(source)] = hashlib.sha256(destination.read_bytes()).hexdigest()
    return copied, hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, help='Explicit physical/test target; never inferred')
    parser.add_argument('--port', type=int, default=9761)
    parser.add_argument('--duration-seconds', type=int, default=1800)
    parser.add_argument('--reconnect-seconds', type=int, default=300)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--maven-repo', type=Path, default=Path.home()/'.m2/repository')
    args = parser.parse_args()
    if args.duration_seconds < 0 or args.reconnect_seconds < 1:
        parser.error('Duration must be nonnegative and reconnect interval positive')
    args.output.mkdir(parents=True, exist_ok=False)
    repo = Path(__file__).resolve().parent.parent
    protocol = repo.parent/'dsp408-protocol/target/dsp408-protocol-0.1.0-SNAPSHOT.jar'
    core = repo/'dsp408-parent/dsp408-core/target/dsp408-core-0.0.1-SNAPSHOT.jar'
    jars = [core, protocol] + [args.maven_repo/'com/fasterxml/jackson/core'/name/'2.17.2'/f'{name}-2.17.2.jar'
                              for name in ['jackson-annotations', 'jackson-core', 'jackson-databind']]
    if any(not jar.is_file() for jar in jars):
        parser.error('Build core/protocol first; required JAR is missing')
    jars, artifact_hashes = snapshot_artifacts(jars, args.output/'artifacts')
    classpath = os.pathsep.join(str(jar) for jar in jars)
    classes = args.output/'classes'
    classes.mkdir()
    compile_command = ['javac', '--release', '21', '-cp', classpath, '-d', str(classes),
                       str(repo/'integration/ReadOnlySoak.java')]
    compile_code = run_owned(compile_command, timeout=60)
    if compile_code:
        raise subprocess.CalledProcessError(compile_code, compile_command)
    (args.output/'artifacts.json').write_text(json.dumps(artifact_hashes, indent=2))
    with GuardProxy(args.host, args.port, args.output) as guard:
        command = ['java', '-cp', str(classes)+os.pathsep+classpath, 'ReadOnlySoak',
                   str(guard.port), str(args.duration_seconds), str(args.reconnect_seconds), str(args.output)]
        with (args.output/'controller.log').open('w') as log:
            child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try:
                code = child.wait(timeout=args.duration_seconds + 180)
            except BaseException:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                raise
    report = guard.report()
    report.update(exit_code=code, target=f'{args.host}:{args.port}', duration_requested_seconds=args.duration_seconds,
                  live_readonly=True, process_stopped=child.poll() is not None)
    (args.output/'guard-report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))
    return code or (1 if report['errors'] or report['open_owned_sockets'] or report['live_proxy_threads'] else 0)

if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f'Validation interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())

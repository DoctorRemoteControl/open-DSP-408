#!/usr/bin/env python3
"""Boot the packaged offline Java server in isolated install/state directories."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('package_karaf_fixture', HERE / 'karaf-smoke.py')
fixtures = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = fixtures
spec.loader.exec_module(fixtures)


def extract(archive, destination):
    with tarfile.open(archive, 'r:gz') as bundle:
        roots = set()
        for member in bundle.getmembers():
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError('Unsafe server archive member: ' + member.name)
            roots.add(path.parts[0])
        if len(roots) != 1:
            raise ValueError('Server archive must have one root')
        bundle.extractall(destination, filter='data')
    return destination / roots.pop()


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


class PackageCheck:
    def __init__(self, args):
        self.args = args
        self.state = fixtures.prepare_state(args.state_dir)
        self.process = None
        self.log = None
        self.result = {'ok': False, 'http': [], 'starts': [], 'shutdowns': []}

    def control(self, *arguments):
        run = subprocess.run([sys.executable, str(self.unpacked / 'server/dsp408ctl.py'),
                              *map(str, arguments)], text=True, capture_output=True, timeout=45)
        if run.returncode:
            raise AssertionError('Deployment command failed: ' + run.stderr)
        return run.stdout

    def request(self, path, method='GET', body=None, expected=200, timeout=10):
        request = urllib.request.Request('http://127.0.0.1:' + str(self.port) + path,
            data=json.dumps(body or {}).encode() if method == 'POST' else None,
            headers={'Content-Type': 'application/json'}, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            data = json.loads(payload) if response.headers.get('Content-Type', '').startswith('application/json') else None
            self.result['http'].append({'path': path, 'method': method, 'status': response.status})
            if response.status != expected:
                raise AssertionError(f'Expected HTTP {expected}, received {response.status}: {data}')
            return data

    def start(self):
        self.log = (self.state / ('java-' + str(len(self.result['starts'])) + '.log')).open('wb')
        env = os.environ.copy()
        env.update(KARAF_HOME='/never-use-inherited-karaf', KARAF_BASE='/never-use-inherited-base',
                   JAVA_TOOL_OPTIONS='-Xmxinvalid', _JAVA_OPTIONS='-Xmxinvalid', DISPLAY=':999')
        command = [sys.executable, str(self.install / 'current/server/run.py'), '--state-dir', str(self.runtime)]
        self.process = subprocess.Popen(command, env=env, stdout=self.log, stderr=subprocess.STDOUT,
                                        start_new_session=True)
        self.result['starts'].append({'pid': self.process.pid, 'httpPort': self.port})
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError('Packaged foreground server exited before health became available')
            try:
                self.request('/api/v1/health')
                return command
            except (OSError, ValueError, AssertionError):
                time.sleep(.2)
        raise AssertionError('Packaged API did not start with bundled offline dependencies')

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            if os.getpgid(self.process.pid) != self.process.pid:
                raise AssertionError('Refusing to signal a process group not created by this harness')
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(20)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(5)
                raise AssertionError('Packaged JVM required forced termination')
        self.result['shutdowns'].append({'pid': self.process.pid, 'returnCode': self.process.returncode})
        self.log.close()
        self.process = None
        with socket.socket() as probe:
            probe.settimeout(.2)
            if probe.connect_ex(('127.0.0.1', self.port)) == 0:
                raise AssertionError('Owned API listener remained open')

    def unchanged_payload(self):
        release = (self.install / 'current').resolve()
        manifest = json.loads((release / 'SHA256SUMS.json').read_text())['files']
        actual = {p.relative_to(release).as_posix() for p in release.rglob('*') if p.is_file()
                  and p.name != 'SHA256SUMS.json'}
        if actual != set(manifest):
            raise AssertionError('Runtime added files to its immutable release: ' + str(actual - set(manifest)))
        for rel, expected in manifest.items():
            if hashlib.sha256((release / rel).read_bytes()).hexdigest() != expected:
                raise AssertionError('Runtime changed release payload: ' + rel)

    def run(self):
        self.unpacked = extract(Path(self.args.archive), self.state)
        self.install = self.state / 'installed'
        self.runtime = self.state / 'persistent-state'
        install_args = ['install', '--install-dir', self.install, '--state-dir', self.runtime]
        if self.args.allow_development_build:
            install_args.append('--allow-development-build')
        self.control(*install_args)
        metadata = json.loads((self.install / 'current/release.json').read_text())
        self.result['release'] = metadata
        settings_file = self.runtime / 'config/server.json'
        settings = json.loads(settings_file.read_text())
        self.port = free_port()
        settings['httpPort'] = self.port
        settings_file.write_text(json.dumps(settings, indent=2) + '\n')
        with fixtures.FakeDsp() as peer:
            command = self.start()
            registry = self.request('/api/v1/devices')
            if registry != {'mode': 'managed', 'persistent': True, 'devices': []}:
                raise AssertionError('Package did not start with an empty managed registry')
            self.request('/app/', expected=404)
            self.request('/api/v1/state', expected=409)
            cmdline = Path(f'/proc/{self.process.pid}/cmdline').read_bytes().split(b'\0')
            if not any(arg.startswith(b'-Djava.awt.headless=true') for arg in cmdline):
                raise AssertionError('Foreground process is not the actual headless JVM')
            self.result['javaHeadless'] = True
            duplicate = subprocess.run(command, text=True, capture_output=True, timeout=5)
            if duplicate.returncode == 0 or 'already running' not in duplicate.stderr:
                raise AssertionError('Second server did not reject the shared state before startup')
            self.result['duplicateRejected'] = True
            definition = {'id': 'package', 'host': '127.0.0.1', 'port': peer.port,
                          'library': 'fir408', 'autoConnect': False}
            self.request('/api/v1/devices', 'POST', definition, expected=201)
            if peer.connections:
                raise AssertionError('Registration unexpectedly contacted the DSP')
            self.request('/api/v1/dsps/package/connection/connect', 'POST', timeout=60)
            cache = self.request('/api/v1/dsps/package/settings-cache')
            if not cache.get('ready') or cache.get('cachedBlockCount') != 32:
                raise AssertionError('Packaged client did not read a complete fixture configuration')
            self.request('/api/v1/dsps/package/connection/disconnect', 'POST')
            self.stop()
            self.unchanged_payload()
            registry_file = self.runtime / 'device-registry.json'
            stored = registry_file.read_bytes()
            self.port = free_port()
            settings['httpPort'] = self.port
            settings_file.write_text(json.dumps(settings, indent=2) + '\n')
            configured = settings_file.read_bytes()
            self.control(*install_args)
            if configured != settings_file.read_bytes() or stored != registry_file.read_bytes():
                raise AssertionError('Idempotent install changed operator state')
            self.start()
            if self.request('/api/v1/devices')['devices'] != [definition]:
                raise AssertionError('Registration did not survive complete JVM restart')
            if stored != registry_file.read_bytes() or self.request('/api/v1/dsps')['dsps'][0]['connected']:
                raise AssertionError('Restart changed persisted definition or enabled manual connection')
            if peer.connections != 1:
                raise AssertionError('Offline registry restoration reconnected automatically')
            self.request('/app/', expected=404)
            self.stop()
            self.unchanged_payload()
        self.result['peer'] = peer.evidence()
        if peer.forbidden or peer.errors or not self.result['peer']['workersStopped']:
            raise AssertionError('Fixture observed forbidden commands or leaked workers')
        if not (self.runtime / 'log/karaf.log').is_file():
            raise AssertionError('Persistent shared log directory was not used')
        self.result.update(ok=True, persistentRestart=True, changedListenPortApplied=True,
                           immutablePayload=True, idempotentInstall=True, offlineFreshBoot=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True)
    parser.add_argument('--state-dir', required=True)
    parser.add_argument('--allow-development-build', action='store_true')
    args = parser.parse_args()
    check = PackageCheck(args)
    started = time.monotonic()
    try:
        check.run()
    except (Exception, KeyboardInterrupt) as error:
        check.result.update(ok=False, error=type(error).__name__ + ': ' + str(error))
    finally:
        try:
            check.stop()
        except Exception as error:
            check.result.update(ok=False, shutdownError=str(error))
        check.result['seconds'] = round(time.monotonic() - started, 3)
        (check.state / 'result.json').write_text(json.dumps(check.result, indent=2) + '\n')
    print(json.dumps(check.result, indent=2))
    return 0 if check.result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())

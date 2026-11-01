#!/usr/bin/env python3
"""Exercise an isolated packaged server through the real user systemd manager.

Optional Linux integration check. It never enables a unit or changes lingering.
Only the uniquely named unit created by this invocation is controlled or removed.
"""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import time
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('service_package_fixture', HERE / 'server-package-smoke.py')
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)


class ServiceCheck(package.PackageCheck):
    def __init__(self, args):
        super().__init__(args)
        self.unit_name = 'dsp408-package-smoke-' + uuid.uuid4().hex + '.service'
        self.unit_file = Path.home() / '.config/systemd/user' / self.unit_name
        self.unit_bytes = None
        self.unit_created = False
        self.manager_loaded = False
        self.peer = None
        self.result.update(service=self.unit_name, unitPath=str(self.unit_file), serviceStates=[])

    def systemctl(self, *arguments, check=True):
        completed = subprocess.run(['systemctl', '--user', *map(str, arguments)],
                                   text=True, capture_output=True, timeout=45)
        if check and completed.returncode:
            raise AssertionError('systemctl ' + ' '.join(map(str, arguments)) + ': ' + completed.stderr)
        return completed

    def status(self):
        fields = ('LoadState', 'ActiveState', 'SubState', 'MainPID', 'NRestarts', 'FragmentPath', 'ControlGroup')
        output = self.systemctl('show', self.unit_name,
                                *('--property=' + field for field in fields), check=False).stdout
        return dict(line.split('=', 1) for line in output.splitlines() if '=' in line)

    def prove_unit_ownership(self):
        if (self.unit_file.name != self.unit_name or self.unit_file.is_symlink()
                or not self.unit_created or self.unit_file.read_bytes() != self.unit_bytes):
            raise AssertionError('Owned unit file changed; refusing service mutation or deletion')
        status = self.status()
        fragment = status.get('FragmentPath')
        if fragment and fragment != str(self.unit_file):
            raise AssertionError('Unit manager loaded an unexpected service file: ' + fragment)
        return status

    def write_unit(self):
        for path in (self.unit_file.parent, *self.unit_file.parent.parents):
            if path.is_symlink():
                raise AssertionError('Refusing symlink in user unit directory: ' + str(path))
        self.unit_file.parent.mkdir(parents=True, exist_ok=True)
        if self.status().get('LoadState') != 'not-found':
            raise AssertionError('Refusing to replace a unit already known to the manager')
        content = self.control('service-file', '--install-dir', self.install,
                               '--state-dir', self.runtime, '--scope', 'user')
        self.unit_bytes = content.encode()
        descriptor = os.open(self.unit_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        self.unit_created = True
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(self.unit_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        (self.state / 'generated.service').write_bytes(self.unit_bytes)
        self.result['unitSha256'] = hashlib.sha256(self.unit_bytes).hexdigest()
        self.result['ownership'] = {'exclusiveCreate': True, 'exactBytesRequired': True,
                                    'uniqueName': self.unit_name, 'path': str(self.unit_file)}
        self.systemctl('daemon-reload')
        self.manager_loaded = True
        self.prove_unit_ownership()

    def java_processes(self):
        expected = ('-Dkaraf.base=' + str(self.runtime / 'runtimes' / self.result['release']['releaseId'])).encode()
        found = []
        for entry in Path('/proc').iterdir():
            if not entry.name.isdecimal():
                continue
            try:
                arguments = (entry / 'cmdline').read_bytes().split(b'\0')
            except (OSError, ProcessLookupError):
                continue
            if expected in arguments and b'org.apache.karaf.main.Main' in arguments:
                found.append(int(entry.name))
        return found

    def assert_no_peer_connections(self):
        if select.select([self.peer], [], [], 0)[0]:
            raise AssertionError('Offline registry unexpectedly contacted its loopback DSP fixture')

    def wait_online(self, previous_pid=None):
        deadline = time.monotonic() + 60
        last = None
        while time.monotonic() < deadline:
            last = self.prove_unit_ownership()
            pid = int(last.get('MainPID', '0'))
            if last.get('ActiveState') == 'failed':
                raise AssertionError('Owned service failed: ' + str(last))
            if pid and pid != previous_pid:
                try:
                    self.request('/api/v1/health', timeout=1)
                except (OSError, ValueError, AssertionError):
                    # The HTTP listener precedes bundle/API activation.
                    time.sleep(.2)
                    continue
                arguments = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
                if b'org.apache.karaf.main.Main' not in arguments or b'-Djava.awt.headless=true' not in arguments:
                    raise AssertionError('systemd MainPID is not the actual headless Java server')
                if self.java_processes() != [pid]:
                    raise AssertionError('Owned service has unexpected Java processes')
                self.result['serviceStates'].append(last)
                self.result['starts'].append({'pid': pid, 'httpPort': self.port})
                self.assert_no_peer_connections()
                return pid
            time.sleep(.2)
        raise AssertionError('Owned service did not become healthy: ' + str(last))

    def start_service(self):
        self.prove_unit_ownership()
        self.systemctl('start', self.unit_name)
        return self.wait_online()

    def stop_service(self):
        before = self.prove_unit_ownership()
        self.systemctl('stop', self.unit_name)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            remaining = self.java_processes()
            with socket.socket() as probe:
                probe.settimeout(.1)
                listening = probe.connect_ex(('127.0.0.1', self.port)) == 0
            state = self.status()
            if not remaining and not listening and state.get('MainPID', '0') == '0':
                self.result['shutdowns'].append({'pid': int(before.get('MainPID', '0')),
                    'listenerClosed': True, 'remainingJavaProcesses': [], 'state': state})
                return
            time.sleep(.1)
        raise AssertionError('Owned service stop left its listener or Java process alive')

    def assert_definition(self, definition, stored):
        if self.request('/api/v1/devices')['devices'] != [definition]:
            raise AssertionError('Registry definition did not survive service lifecycle')
        if (self.runtime / 'device-registry.json').read_bytes() != stored:
            raise AssertionError('Service restart changed registry bytes')
        if self.request('/api/v1/dsps')['dsps'][0]['connected']:
            raise AssertionError('Offline registry definition connected on startup')
        self.assert_no_peer_connections()

    def run(self):
        archive = Path(self.args.archive).resolve()
        self.result['archive'] = str(archive)
        self.result['archiveSha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.unpacked = package.extract(archive, self.state)
        # Exercise actual systemd specifier, environment and whitespace escaping.
        self.install = self.state / 'installed % $ space'
        self.runtime = self.state / 'persistent % $ space'
        install_args = ['install', '--install-dir', self.install, '--state-dir', self.runtime]
        if self.args.allow_development_build:
            install_args.append('--allow-development-build')
        self.control(*install_args)
        self.result['release'] = json.loads((self.install / 'current/release.json').read_text())
        settings_path = self.runtime / 'config/server.json'
        settings = json.loads(settings_path.read_text())
        self.port = package.free_port()
        settings['httpPort'] = self.port
        settings_path.write_text(json.dumps(settings, indent=2) + '\n')
        self.peer = socket.socket()
        self.peer.bind(('127.0.0.1', 0))
        self.peer.listen(1)
        definition = {'id': 'service-offline', 'host': '127.0.0.1', 'port': self.peer.getsockname()[1],
                      'library': 'fir408', 'autoConnect': False}
        self.write_unit()
        first_pid = self.start_service()
        if self.request('/api/v1/devices') != {'mode': 'managed', 'persistent': True, 'devices': []}:
            raise AssertionError('Service did not start empty in managed mode')
        self.request('/app/', expected=404)
        self.request('/api/v1/devices', 'POST', definition, expected=201)
        self.assert_no_peer_connections()
        stored = (self.runtime / 'device-registry.json').read_bytes()
        self.stop_service()
        self.unchanged_payload()
        second_pid = self.start_service()
        if second_pid == first_pid:
            raise AssertionError('Service stop/start did not create a new Java process')
        self.assert_definition(definition, stored)
        self.prove_unit_ownership()
        self.systemctl('kill', '--kill-whom=main', '--signal=SIGKILL', self.unit_name)
        replacement_pid = self.wait_online(previous_pid=second_pid)
        restarted = self.status()
        if int(restarted.get('NRestarts', '0')) < 1:
            raise AssertionError('systemd did not record Restart=on-failure')
        if Path(f'/proc/{second_pid}').exists():
            raise AssertionError('Killed Java process remains present')
        self.assert_definition(definition, stored)
        self.stop_service()
        self.unchanged_payload()
        self.result.update(ok=True, serviceStopStart=True, restartOnFailure=True,
                           actualJavaMainPid=True, forcedKillPid=second_pid, replacementPid=replacement_pid,
                           persistentRegistry=True, offlineDefinition=True, escapedPaths=True,
                           immutablePayload=True, listenerClosed=True, remainingJavaProcesses=[])

    def cleanup(self):
        if self.unit_created:
            self.prove_unit_ownership()
            if self.manager_loaded:
                self.stop_service()
            self.prove_unit_ownership()
            self.unit_file.unlink()
            self.unit_created = False
            self.systemctl('daemon-reload')
            self.systemctl('reset-failed', self.unit_name, check=False)
            status = self.status()
            if self.unit_file.exists() or status.get('ActiveState') in ('active', 'activating', 'deactivating'):
                raise AssertionError('Owned unit cleanup left an active service or unit file')
            if self.java_processes():
                raise AssertionError('Owned Java process survived unit cleanup')
            self.result['cleanup'] = {'unitRemoved': True, 'daemonReloaded': True,
                                     'remainingJavaProcesses': [], 'state': status}
        if self.peer is not None:
            self.peer.close()
            self.peer = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True)
    parser.add_argument('--state-dir', required=True)
    parser.add_argument('--allow-development-build', action='store_true')
    args = parser.parse_args()
    check = ServiceCheck(args)
    started = time.monotonic()
    try:
        check.run()
    except (Exception, KeyboardInterrupt) as error:
        check.result.update(ok=False, error=type(error).__name__ + ': ' + str(error))
        if check.unit_created:
            journal = subprocess.run(['journalctl', '--user', '--unit', check.unit_name,
                                      '--no-pager', '-n', '100'], text=True, capture_output=True, timeout=15)
            (check.state / 'journal.log').write_text(journal.stdout + journal.stderr)
    finally:
        try:
            check.cleanup()
        except Exception as error:
            check.result.update(ok=False, cleanupError=type(error).__name__ + ': ' + str(error))
        check.result['seconds'] = round(time.monotonic() - started, 3)
        (check.state / 'result.json').write_text(json.dumps(check.result, indent=2) + '\n')
    print(json.dumps(check.result, indent=2))
    return 0 if check.result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())

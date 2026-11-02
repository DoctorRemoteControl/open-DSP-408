"""Real filesystem and foreground admission tests for the server distribution."""
import hashlib
import errno
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'dsp408-parent/dsp408-server/src/main/resources/assembly/server'


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.install = self.root / 'installation'
        self.state = self.root / 'state'
        self.package = self.make_package('1.0-aaaaaaaaaaaa')
        self.java_bin = self.root / 'java-bin'
        self.java_bin.mkdir()
        java = self.java_bin / 'java'
        java.write_text('#!/bin/sh\necho \'openjdk version "21.0.5"\' >&2\n')
        java.chmod(0o755)

    def make_package(self, release_id, revision='a' * 40, dirty='false'):
        package = self.root / release_id
        (package / 'bin').mkdir(parents=True)
        (package / 'etc').mkdir()
        (package / 'system').mkdir()
        (package / 'server').mkdir()
        for source in TOOLS.glob('*.py'):
            shutil.copy2(source, package / 'server' / source.name)
        karaf = package / 'bin/karaf'
        karaf.write_text('#!' + sys.executable + '\nimport os,json,sys,time,fcntl\n'
                         'fd=int(os.environ["DSP408_LOCK_FD"])\n'
                         'fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)\n'
                         'open(os.environ["CAPTURE"],"w").write(json.dumps({"env":dict(os.environ),"argv":sys.argv[1:]}))\n'
                         'time.sleep(float(os.environ.get("HOLD","0")))\n')
        karaf.chmod(0o755)
        (package / 'etc/config.properties').write_text('karaf.framework=equinox\n')
        (package / 'etc/org.apache.karaf.features.cfg').write_text('featuresBoot=log,config,dsp408-server\n')
        meta = dict(schemaVersion=1,releaseId=release_id,version='1.0',sourceRevision=revision,
                    sourceDirty=dirty,protocolRevision='b' * 40,protocolDirty='false',karafVersion='4.4.11',javaMajor=21)
        (package / 'release.json').write_text(json.dumps(meta))
        self.rehash(package)
        return package

    def rehash(self, package):
        files = {p.relative_to(package).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in package.rglob('*') if p.is_file() and p.name != 'SHA256SUMS.json'}
        (package / 'SHA256SUMS.json').write_text(json.dumps(dict(schemaVersion=1, files=files)))

    def ctl(self, command, package=None, extra=()):
        return subprocess.run([sys.executable, str((package or self.package) / 'server/dsp408ctl.py'),
                               command, '--install-dir', str(self.install), '--state-dir', str(self.state), *extra],
                              text=True,capture_output=True)

    def installed(self):
        result = self.ctl('install')
        self.assertEqual(0, result.returncode, result.stderr)
        return self.install / 'current'

    def launch(self, current=None, **variables):
        env = dict(os.environ, PATH=str(self.java_bin) + os.pathsep + os.environ['PATH'],
                   CAPTURE=str(self.root / 'capture.json'), **variables)
        return subprocess.Popen([sys.executable,str((current or self.install / 'current') / 'server/run.py'),
                                 '--state-dir',str(self.state)], env=env, text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)

    def run_launch(self, **variables):
        process = self.launch(**variables)
        stdout, stderr = process.communicate(timeout=10)
        return process.returncode, stdout, stderr

    def test_install_and_reinstall_preserve_operator_state(self):
        current = self.installed()
        settings = self.state / 'config/server.json'
        settings.write_text('{"operator":"unchanged bytes"}\n')
        registry = self.state / 'device-registry.json'
        registry.write_text('original registry')
        result = self.ctl('install')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(current))
        self.assertEqual('{"operator":"unchanged bytes"}\n',settings.read_text())
        self.assertEqual('original registry',registry.read_text())

    def test_update_and_rollback_select_intact_previous_release(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc',revision='c' * 40)
        result = self.ctl('install',second)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(self.install / 'previous'))
        result = self.ctl('rollback',second)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(self.install / 'current'))
        self.assertEqual('releases/1.0-cccccccccccc',os.readlink(self.install / 'previous'))

    def test_corruption_does_not_replace_current(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc',revision='c' * 40)
        (second / 'etc/config.properties').write_text('corrupt')
        result = self.ctl('install',second)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('integrity',result.stderr.lower())
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(self.install / 'current'))

    def test_extra_file_and_payload_symlinks_are_rejected(self):
        for name in ('extra', 'symlink'):
            with self.subTest(name=name):
                path = self.package / name
                if name == 'extra': path.write_text('unlisted')
                else: path.symlink_to('/etc/passwd')
                result = self.ctl('install')
                self.assertNotEqual(0,result.returncode)
                self.assertIn('integrity',result.stderr.lower())
                path.unlink()
        self.assertFalse((self.install / 'current').exists())

    def test_unknown_schema_and_unsafe_manifest_path_are_rejected(self):
        for manifest in ({'schemaVersion':2,'files':{}},{'schemaVersion':1,'files':{'../escape':'a' * 64}}):
            (self.package / 'SHA256SUMS.json').write_text(json.dumps(manifest))
            result = self.ctl('install')
            self.assertNotEqual(0,result.returncode)
            self.assertIn('integrity',result.stderr.lower())

    def test_development_build_needs_explicit_consent(self):
        package = self.make_package('1.0-unknown-dirty',revision='unknown',dirty='unknown')
        rejected = self.ctl('install',package)
        self.assertNotEqual(0,rejected.returncode)
        self.assertIn('allow-development-build',rejected.stderr)
        accepted = self.ctl('install',package,('--allow-development-build',))
        self.assertEqual(0,accepted.returncode,accepted.stderr)

    def test_hostile_state_symlink_is_rejected_without_target_write(self):
        outside = self.root / 'outside'
        outside.mkdir()
        self.state.symlink_to(outside,target_is_directory=True)
        result = self.ctl('install')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('symlink',result.stderr.lower())
        self.assertEqual([],list(outside.iterdir()))

    def test_overlapping_install_state_are_rejected(self):
        self.state = self.install / 'state'
        result = self.ctl('install')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('overlap',result.stderr.lower())

    def test_existing_release_is_never_overwritten(self):
        self.installed()
        target = self.install / 'releases/1.0-aaaaaaaaaaaa/etc/config.properties'
        target.write_text('operator tampered')
        result = self.ctl('install')
        self.assertNotEqual(0,result.returncode)
        self.assertEqual('operator tampered',target.read_text())

    def test_rollback_refuses_corrupt_previous_without_pointer_changes(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc',revision='c' * 40)
        self.assertEqual(0,self.ctl('install',second).returncode)
        (self.install / 'previous/etc/config.properties').write_text('corrupt')
        result = self.ctl('rollback',second)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual('releases/1.0-cccccccccccc',os.readlink(self.install / 'current'))

    def test_foreground_launcher_sets_external_paths_and_headless_environment(self):
        self.installed()
        result = self.run_launch(JAVA_OPTS='-Dunsafe=true',JDK_JAVA_OPTIONS='-Dbad=true',
                                 KARAF_HOME='/bad',DISPLAY=':99',WAYLAND_DISPLAY='wayland-1')
        self.assertEqual(0,result[0],result[2])
        captured = json.loads((self.root / 'capture.json').read_text())
        env = captured['env']
        self.assertEqual(['server'],captured['argv'])
        self.assertEqual(str(self.install / 'releases/1.0-aaaaaaaaaaaa'),env['KARAF_HOME'])
        self.assertEqual(str(self.state / 'runtimes/1.0-aaaaaaaaaaaa/data'),env['KARAF_DATA'])
        self.assertEqual(str(self.state / 'log'),env['KARAF_LOG'])
        self.assertIn('-Djava.awt.headless=true',env['JAVA_OPTS'])
        for key in ('DISPLAY','WAYLAND_DISPLAY','JDK_JAVA_OPTIONS'):
            self.assertNotIn(key,env)
        etc = Path(env['KARAF_ETC'])
        dsp = (etc / 'de.drremote.dsp408controller.cfg').read_text()
        self.assertIn('registry.mode=managed',dsp)
        self.assertIn('auto.connect=false',dsp)
        self.assertIn(str(self.state / 'device-registry.json'),dsp)
        resolver = (etc / 'org.ops4j.pax.url.mvn.cfg').read_text()
        self.assertIn('org.ops4j.pax.url.mvn.offline=true',resolver)
        self.assertNotIn('https:',resolver)

    def test_duplicate_process_is_rejected_while_lock_survives_exec(self):
        self.installed()
        first = self.launch(HOLD='20')
        self.addCleanup(lambda: first.poll() is None and first.kill())
        deadline = time.monotonic() + 5
        while not (self.root / 'capture.json').exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue((self.root / 'capture.json').exists())
        result = self.run_launch()
        self.assertNotEqual(0,result[0])
        self.assertIn('already running',result[2].lower())
        first.terminate()
        first.communicate(timeout=5)

    def test_invalid_settings_fail_without_fallback(self):
        self.installed()
        for value in ({'schemaVersion':1,'listenAddresses':['127.0.0.1'],'httpPort':True,'meterPollIntervalMs':200},
                      {'schemaVersion':1,'listenAddresses':['127.0.0.1\nunsafe=true'],'httpPort':8181,'meterPollIntervalMs':200}):
            (self.state / 'config/server.json').write_text(json.dumps(value))
            result = self.run_launch()
            self.assertNotEqual(0,result[0])
            self.assertIn('settings',result[2].lower())
        self.assertFalse((self.root / 'capture.json').exists())

    def test_java_older_than_21_is_rejected(self):
        self.installed()
        (self.java_bin / 'java').write_text('#!/bin/sh\necho \'openjdk version "17.0.12"\' >&2\n')
        result = self.run_launch()
        self.assertNotEqual(0,result[0])
        self.assertIn('21',result[2])
        self.assertFalse((self.root / 'capture.json').exists())

    def common_module(self):
        spec = importlib.util.spec_from_file_location('deployment_common_test', TOOLS / 'common.py')
        module = importlib.util.module_from_spec(spec)
        previous = sys.dont_write_bytecode
        try:
            sys.dont_write_bytecode = True
            spec.loader.exec_module(module)
        finally:
            sys.dont_write_bytecode = previous
        return module

    def test_copy_failure_preserves_current_settings_and_registry(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc',revision='c' * 40)
        registry = self.state / 'device-registry.json'
        registry.write_text('keep')
        module = self.common_module()
        with patch.object(module.shutil, 'copytree', side_effect=OSError('copy failed')):
            with self.assertRaises(OSError):
                module.install_release(second, self.install, self.state)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(self.install / 'current'))
        self.assertEqual('keep',registry.read_text())
        self.assertFalse((self.install / 'releases/1.0-cccccccccccc').exists())

    def test_pointer_failure_restores_both_old_selections(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc',revision='c' * 40)
        module = self.common_module()
        original = module.os.replace
        def fail_current(source, destination):
            if Path(destination) == self.install / 'current':
                raise OSError('pointer failed')
            return original(source, destination)
        with patch.object(module.os, 'replace', side_effect=fail_current):
            with self.assertRaises(OSError):
                module.install_release(second, self.install, self.state)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa',os.readlink(self.install / 'current'))
        self.assertFalse(os.path.lexists(self.install / 'previous'))

    def test_launcher_rejects_symlinks_within_runtime_data(self):
        self.installed()
        runtime = self.state / 'runtimes/1.0-aaaaaaaaaaaa'
        (runtime / 'data').mkdir(parents=True)
        outside = self.root / 'outside'
        outside.mkdir()
        (runtime / 'data/tmp').symlink_to(outside, target_is_directory=True)
        result = self.run_launch()
        self.assertNotEqual(0,result[0])
        self.assertIn('symlink',result[2].lower())
        self.assertFalse((self.root / 'capture.json').exists())

    def test_launcher_rejects_symlinks_within_shared_logs(self):
        self.installed()
        outside = self.root / 'outside.log'
        outside.write_text('keep')
        (self.state / 'log/karaf.log').symlink_to(outside)
        result = self.run_launch()
        self.assertNotEqual(0,result[0])
        self.assertIn('symlink',result[2].lower())
        self.assertEqual('keep',outside.read_text())

    def test_restart_reloads_settings_and_preserves_registry_and_payload(self):
        self.installed()
        registry = self.state / 'device-registry.json'
        registry.write_text('keep original bytes')
        settings = self.state / 'config/server.json'
        value = dict(schemaVersion=1, listenAddresses=['0.0.0.0'], httpPort=8182, meterPollIntervalMs=350)
        settings.write_text(json.dumps(value))
        for port in (8182, 8183):
            value['httpPort'] = port
            settings.write_text(json.dumps(value))
            result = self.run_launch()
            self.assertEqual(0,result[0],result[2])
            capture = json.loads((self.root / 'capture.json').read_text())
            etc = Path(capture['env']['KARAF_ETC'])
            self.assertIn('org.osgi.service.http.port=' + str(port),(etc / 'org.ops4j.pax.web.cfg').read_text())
            self.assertEqual('keep original bytes',registry.read_text())
            self.assertEqual(json.dumps(value),settings.read_text())
            self.assertIn('-Dkaraf.instances=instances',capture['env'].get('KARAF_OPTS', ''))
        self.common_module().verify_release(self.install / 'releases/1.0-aaaaaaaaaaaa')

    def test_metadata_release_id_must_match_provenance(self):
        meta = json.loads((self.package / 'release.json').read_text())
        meta['releaseId'] = 'unexpected-safe-id'
        (self.package / 'release.json').write_text(json.dumps(meta))
        self.rehash(self.package)
        result = self.ctl('install')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('release',result.stderr.lower())
        self.assertFalse((self.install / 'current').exists())

    def test_nonexecutable_new_launcher_cannot_replace_usable_selection(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc', revision='c' * 40)
        (second / 'bin/karaf').chmod(0o644)
        result = self.ctl('install', second)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa', os.readlink(self.install / 'current'))
        self.assertFalse(os.path.lexists(self.install / 'previous'))

    def test_rollback_rejects_previous_launcher_without_execute_permission(self):
        self.installed()
        second = self.make_package('1.0-cccccccccccc', revision='c' * 40)
        self.assertEqual(0, self.ctl('install', second).returncode)
        (self.install / 'releases/1.0-aaaaaaaaaaaa/bin/karaf').chmod(0o644)
        result = self.ctl('rollback', second)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('releases/1.0-cccccccccccc', os.readlink(self.install / 'current'))
        self.assertEqual('releases/1.0-aaaaaaaaaaaa', os.readlink(self.install / 'previous'))

    def test_manifest_fifo_or_symlink_is_rejected_before_reading(self):
        manifest = self.package / 'SHA256SUMS.json'
        original = manifest.read_bytes()
        fifo = self.root / 'manifest-fifo'
        os.mkfifo(fifo)
        for indirect in (False, True):
            with self.subTest(symlink=indirect):
                manifest.unlink()
                if indirect:
                    manifest.symlink_to(fifo)
                else:
                    os.mkfifo(manifest)
                try:
                    try:
                        result = subprocess.run([sys.executable, str(self.package / 'server/dsp408ctl.py'),
                            'install', '--install-dir', str(self.install), '--state-dir', str(self.state)],
                            text=True, capture_output=True, timeout=2)
                    except subprocess.TimeoutExpired:
                        self.fail('Installer blocked on a special integrity manifest')
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse((self.install / 'current').exists())
                finally:
                    manifest.unlink()
                    manifest.write_bytes(original)

    def interrupted_selection(self, operation):
        self.installed()
        second = self.make_package('1.0-cccccccccccc', revision='c' * 40)
        self.assertEqual(0, self.ctl('install', second).returncode)
        third = self.make_package('1.0-dddddddddddd', revision='d' * 40)
        script = ('import sys,os\nfrom pathlib import Path\n'
                  'sys.dont_write_bytecode=True\nsys.path.insert(0,sys.argv[1])\nimport common\n'
                  'original=common._set_pointer\n'
                  'def interrupt(install,name,target):\n'
                  ' if name=="current": os._exit(77)\n'
                  ' return original(install,name,target)\n'
                  'common._set_pointer=interrupt\n'
                  'install,state,source=map(Path,sys.argv[2:5])\n'
                  'if sys.argv[5]=="install": common.install_release(source,install,state)\n'
                  'else: common.rollback_release(install,state)\n')
        result = subprocess.run([sys.executable, '-c', script, str(TOOLS), str(self.install),
                                 str(self.state), str(third), operation], capture_output=True, text=True, timeout=5)
        self.assertEqual(77, result.returncode, result.stderr)
        self.assertEqual('releases/1.0-cccccccccccc', os.readlink(self.install / 'current'))
        result = self.ctl('rollback')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('releases/1.0-aaaaaaaaaaaa', os.readlink(self.install / 'current'))
        self.assertEqual('releases/1.0-cccccccccccc', os.readlink(self.install / 'previous'))

    def test_interrupted_install_preserves_documented_previous_rollback(self):
        self.interrupted_selection('install')

    def test_interrupted_rollback_can_be_retried(self):
        self.interrupted_selection('rollback')

    def test_optional_web_setting_drives_http_component_without_changing_state(self):
        self.installed()
        settings = self.state / 'config/server.json'
        value = json.loads(settings.read_text())
        value['webEnabled'] = True
        configured = json.dumps(value, indent=2) + '\n'
        settings.write_text(configured)
        result = self.run_launch()
        self.assertEqual(0, result[0], result[2])
        capture = json.loads((self.root / 'capture.json').read_text())
        etc = Path(capture['env']['KARAF_ETC'])
        self.assertEqual('enabled=true\n', (etc / 'de.drremote.dsp408.web.cfg').read_text())
        self.assertEqual(configured, settings.read_text())

    def test_web_setting_requires_a_json_boolean_before_startup(self):
        self.installed()
        settings = self.state / 'config/server.json'
        original = json.loads(settings.read_text())
        for value in ('true', 1, None):
            with self.subTest(value=value):
                settings.write_text(json.dumps({**original, 'webEnabled': value}))
                result = self.run_launch()
                self.assertNotEqual(0, result[0])
                self.assertFalse((self.root / 'capture.json').exists())

    def run_module(self):
        spec = importlib.util.spec_from_file_location('deployment_run_test', TOOLS / 'run.py')
        module = importlib.util.module_from_spec(spec)
        previous = sys.dont_write_bytecode
        try:
            with patch.dict(sys.modules, common=self.common_module()):
                spec.loader.exec_module(module)
        finally:
            sys.dont_write_bytecode = previous
        return module

    def test_launcher_does_not_exec_before_configured_local_address_exists(self):
        self.installed()
        settings = self.state / 'config/server.json'
        value = json.loads(settings.read_text())
        value['listenAddresses'] = ['127.0.0.1', '192.0.2.123']
        settings.write_text(json.dumps(value))
        process = self.launch()
        try:
            deadline = time.monotonic() + .3
            while time.monotonic() < deadline and process.poll() is None:
                time.sleep(.01)
            self.assertIsNone(process.poll(), 'Launcher started Karaf before the LAN address existed')
            self.assertFalse((self.root / 'capture.json').exists())
        finally:
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=5)

    def test_missing_local_address_recovers_but_permanent_absence_has_a_deadline(self):
        module = self.run_module()
        with patch.object(module.socket, 'socket') as sockets, patch.object(module.time, 'sleep'), \
                patch.object(module.time, 'monotonic', side_effect=[0, .1]):
            sockets.return_value.__enter__.return_value.bind.side_effect = [
                OSError(errno.EADDRNOTAVAIL, 'Address not assigned'), None, None]
            module.wait_for_listeners(['127.0.0.1', '::1'])
        with patch.object(module.socket, 'socket') as sockets, patch.object(module.time, 'sleep'), \
                patch.object(module.time, 'monotonic', side_effect=[0, 31]):
            sockets.return_value.__enter__.return_value.bind.side_effect = OSError(errno.EADDRNOTAVAIL, 'Absent')
            with self.assertRaisesRegex(module.DeploymentError, '127.0.0.1'):
                module.wait_for_listeners(['127.0.0.1'])
        with patch.object(module.socket, 'socket') as sockets:
            sockets.return_value.__enter__.return_value.bind.side_effect = OSError(errno.EACCES, 'Denied')
            with self.assertRaises(OSError):
                module.wait_for_listeners(['127.0.0.1'])

    def test_service_output_escapes_paths_and_uses_stable_foreground_entry(self):
        self.install = self.root / 'install % $ space'
        self.state = self.root / 'state % $ space'
        result = self.ctl('service-file',extra=('--scope','user'))
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('Type=simple',result.stdout)
        self.assertIn('/current/server/run.py',result.stdout)
        self.assertIn('%%',result.stdout)
        self.assertIn('$$',result.stdout)
        self.assertIn('WantedBy=default.target',result.stdout)
        self.assertIn('Restart=on-failure',result.stdout)
        self.assertFalse(self.install.exists())
        result = self.ctl('service-file',extra=('--scope','system','--user','dsp408'))
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('User=dsp408',result.stdout)
        self.assertIn('WantedBy=multi-user.target',result.stdout)
        rejected = self.ctl('service-file',extra=('--scope','system','--user','root'))
        self.assertNotEqual(0,rejected.returncode)


if __name__ == '__main__':
    unittest.main()

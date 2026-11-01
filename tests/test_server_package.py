"""Package contracts: corruption evidence and clean build provenance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class PackageMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.assembly = Path(self.temp.name) / 'assembly'
        self.assembly.mkdir()
        self.script = ROOT / 'ci/prepare-server-package.py'
        self.assertTrue(self.script.is_file(), 'Server package preparation is missing')
        spec = importlib.util.spec_from_file_location('prepare_server_package', self.script)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        (self.assembly / 'bin').mkdir()
        (self.assembly / 'bin/karaf').write_text('#!/bin/sh\nexit 0\n')
        for name in ('api', 'core', 'runtime', 'http'):
            self.bundle(name)

    def bundle(self, name, revision='a' * 40, dirty='false'):
        path = self.assembly / f'system/de/drremote/dsp408-{name}/0.0.1-SNAPSHOT/dsp408-{name}-0.0.1-SNAPSHOT.jar'
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF',
                'Manifest-Version: 1.0\r\n'
                f'DSP408-Source-Revision: {revision}\r\n'
                f'DSP408-Source-Dirty: {dirty}\r\n'
                f'DSP408-Protocol-Revision: {"b" * 40}\r\n'
                'DSP408-Protocol-Dirty: false\r\n\r\n')

    def test_every_payload_file_has_independent_hash_and_exact_provenance(self):
        result = self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')
        self.assertEqual('0.0.1-SNAPSHOT-aaaaaaaaaaaa', result['releaseId'])
        self.assertEqual('a' * 40, result['sourceRevision'])
        self.assertEqual('b' * 40, result['protocolRevision'])
        self.assertEqual(21, result['javaMajor'])
        checksums = json.loads((self.assembly / 'SHA256SUMS.json').read_text())
        self.assertEqual(1, checksums['schemaVersion'])
        self.assertIn('release.json', checksums['files'])
        self.assertEqual(6, len(checksums['files']))
        for rel, sha in checksums['files'].items():
            self.assertEqual(hashlib.sha256((self.assembly / rel).read_bytes()).hexdigest(), sha)
        before = (self.assembly / 'SHA256SUMS.json').read_bytes()
        self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')
        self.assertEqual(before, (self.assembly / 'SHA256SUMS.json').read_bytes())

    def test_mixed_controller_revisions_cannot_claim_one_release(self):
        self.bundle('runtime', revision='c' * 40)
        with self.assertRaises(ValueError):
            self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')
        self.assertFalse((self.assembly / 'release.json').exists())

    def test_missing_runtime_is_rejected(self):
        next(self.assembly.glob('system/de/drremote/dsp408-runtime/*/*.jar')).unlink()
        with self.assertRaises(ValueError):
            self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')

    def test_live_registry_and_generated_host_keys_are_not_distributed(self):
        for rel in ('device-registry.json', 'data/log/karaf.log', 'etc/host.key'):
            with self.subTest(rel=rel):
                path = self.assembly / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('must stay local')
                try:
                    with self.assertRaises(ValueError):
                        self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')
                finally:
                    path.unlink()

    def test_symlink_payload_cannot_borrow_files_outside_release(self):
        (self.assembly / 'borrowed').symlink_to('/etc/hostname')
        with self.assertRaises(ValueError):
            self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')

    def test_dirty_build_is_explicitly_identified(self):
        for name in ('api', 'core', 'runtime', 'http'):
            self.bundle(name, dirty='true')
        result = self.module.prepare(self.assembly, '0.0.1-SNAPSHOT')
        self.assertEqual('true', result['sourceDirty'])
        self.assertEqual('0.0.1-SNAPSHOT-aaaaaaaaaaaa-dirty', result['releaseId'])


if __name__ == '__main__':
    unittest.main()

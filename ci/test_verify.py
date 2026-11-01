#!/usr/bin/env python3
"""Exercise checkout validation and provenance at the external Maven boundary."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


VERIFY_SCRIPT = Path(__file__).with_name("verify.sh")


class VerifyCheckoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="controller-ci-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.controller = self.root / "open-DSP-408"
        self.protocol = self.root / "dsp408-protocol"
        (self.controller / "ci").mkdir(parents=True)
        (self.controller / "dsp408-parent").mkdir()
        self.protocol.mkdir()
        (self.controller / "dsp408-parent/pom.xml").write_text("<project/>\n")
        (self.protocol / "pom.xml").write_text("<project/>\n")
        self.revision = self.init_repo(self.protocol)
        (self.controller / "ci/protocol-revision").write_text(self.revision + "\n")
        self.controller_revision = self.init_repo(self.controller)
        self.log = self.root / "maven.jsonl"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake_maven = self.bin / "mvn"
        fake_maven.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "with open(os.environ['MAVEN_CALL_LOG'], 'a') as log:\n"
            "    log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
        )
        fake_maven.chmod(0o755)

    def git(self, path, *args):
        return subprocess.check_output(
            ["git", "-C", str(path), *args], text=True, stderr=subprocess.DEVNULL
        ).strip()

    def init_repo(self, path):
        self.git(path, "init", "-q")
        self.git(path, "config", "user.name", "CI test")
        self.git(path, "config", "user.email", "ci-test@example.invalid")
        self.git(path, "config", "commit.gpgsign", "false")
        self.git(path, "add", ".")
        self.git(path, "commit", "-qm", "Fixture")
        return self.git(path, "rev-parse", "HEAD")

    def run_verify(self, protocol_override=None):
        if not VERIFY_SCRIPT.exists():
            self.fail("Standalone verification script has not been implemented")
        shutil.copyfile(VERIFY_SCRIPT, self.controller / "ci/verify.sh")
        # The script belongs to the fixture checkout, not an untracked change.
        if (self.controller / ".git").exists():
            self.git(self.controller, "add", "ci/verify.sh")
            self.git(self.controller, "commit", "-qm", "Install verification entry point")
            self.controller_revision = self.git(self.controller, "rev-parse", "HEAD")
        env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}", MAVEN_CALL_LOG=str(self.log))
        env.pop("DSP408_PROTOCOL_DIR", None)
        if protocol_override is not None:
            env["DSP408_PROTOCOL_DIR"] = str(protocol_override)
        result = subprocess.run(
            ["bash", str(self.controller / "ci/verify.sh")],
            cwd=self.root, env=env, text=True, capture_output=True,
        )
        calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, calls

    def test_clean_checkouts_pass_full_revisions_to_controller_build(self):
        result, calls = self.run_verify()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        for call in calls:
            self.assertIn("clean", call)
            self.assertIn("install", call)
        self.assertIn(str(self.protocol / "pom.xml"), calls[0])
        self.assertIn(str(self.controller / "dsp408-parent/pom.xml"), calls[1])
        self.assertIn(f"-Ddsp408.source.revision={self.controller_revision}", calls[1])
        self.assertIn("-Ddsp408.source.dirty=false", calls[1])
        self.assertIn(f"-Ddsp408.protocol.revision={self.revision}", calls[1])
        self.assertIn("-Ddsp408.protocol.dirty=false", calls[1])

    def test_tracked_and_untracked_changes_cannot_be_reported_clean(self):
        (self.controller / "dsp408-parent/pom.xml").write_text("<project><!-- changed --></project>\n")
        (self.protocol / "new-source.java").write_text("class Changed {}\n")
        result, calls = self.run_verify()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Ddsp408.source.dirty=true", calls[1])
        self.assertIn("-Ddsp408.protocol.dirty=true", calls[1])

    def test_override_accepts_protocol_checkout_at_an_unrelated_path(self):
        moved = self.root / "protocol source override"
        self.protocol.rename(moved)
        result, calls = self.run_verify(moved)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(str(moved / "pom.xml"), calls[0])

    def test_wrong_protocol_revision_stops_before_maven(self):
        (self.protocol / "pom.xml").write_text("<project><!-- different commit --></project>\n")
        self.git(self.protocol, "add", ".")
        self.git(self.protocol, "commit", "-qm", "Different protocol")
        result, calls = self.run_verify()
        self.assertEqual(2, result.returncode)
        self.assertIn("does not match", result.stderr)
        self.assertEqual([], calls)

    def test_invalid_pin_stops_before_maven(self):
        (self.controller / "ci/protocol-revision").write_text("main\n")
        result, calls = self.run_verify()
        self.assertEqual(2, result.returncode)
        self.assertIn("40-character", result.stderr)
        self.assertEqual([], calls)

    def test_missing_protocol_stops_before_maven(self):
        result, calls = self.run_verify(self.root / "absent")
        self.assertEqual(2, result.returncode)
        self.assertIn("Missing dsp408-protocol", result.stderr)
        self.assertEqual([], calls)

    def test_controller_source_archive_is_explicitly_unknown(self):
        shutil.rmtree(self.controller / ".git")
        result, calls = self.run_verify()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Ddsp408.source.revision=unknown", calls[1])
        self.assertIn("-Ddsp408.source.dirty=unknown", calls[1])

    def test_protocol_archive_without_git_cannot_claim_the_pin(self):
        shutil.rmtree(self.protocol / ".git")
        result, calls = self.run_verify()
        self.assertEqual(2, result.returncode)
        self.assertIn("Git checkout", result.stderr)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()

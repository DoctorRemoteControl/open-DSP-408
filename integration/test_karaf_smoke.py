"""Offline regressions for provisioning safety and the loopback DSP peer."""
import importlib.util
import io
from argparse import Namespace
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("karaf_smoke", HERE / "karaf-smoke.py")
smoke = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = smoke
spec.loader.exec_module(smoke)


class ProvisioningTest(unittest.TestCase):
    def test_archive_cannot_escape_owned_runtime_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                member = tarfile.TarInfo("apache-karaf-4.4.11/../../outside")
                member.size = 4
                bundle.addfile(member, io.BytesIO(b"oops"))
            with self.assertRaisesRegex(ValueError, "Unsafe archive"):
                smoke.extract_distribution(archive, root / "state", "4.4.11")
            self.assertFalse((root / "outside").exists())

    def test_existing_state_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "existing-runtime"
            marker.write_text("preserve")
            with self.assertRaisesRegex(ValueError, "empty"):
                smoke.prepare_state(root)
            self.assertEqual("preserve", marker.read_text())


class FakeDspTest(unittest.TestCase):
    def test_keepalive_uses_the_generic_ack_expected_by_controller(self):
        with smoke.FakeDsp() as peer:
            with socket.create_connection(("127.0.0.1", peer.port), timeout=2) as client:
                client.settimeout(2)
                smoke.read_frame(client)
                client.sendall(bytes.fromhex("100200010112100313"))
                self.assertEqual(bytes.fromhex("01000101"), smoke.read_frame(client))

    def test_meter_reply_covers_the_complete_46_byte_fir_layout(self):
        with smoke.FakeDsp() as peer:
            with socket.create_connection(("127.0.0.1", peer.port), timeout=2) as client:
                client.settimeout(2)
                smoke.read_frame(client)
                client.sendall(bytes.fromhex("100200010140100341"))
                reply = smoke.read_frame(client)
                self.assertEqual(46, len(reply))
                self.assertEqual(bytes.fromhex("01002b40"), reply[:4])

    def test_fragmented_peer_returns_length_prefixed_block_and_rejects_writes(self):
        with smoke.FakeDsp(fragment=True, delay=0.001) as peer:
            with socket.create_connection(("127.0.0.1", peer.port), timeout=2) as client:
                client.settimeout(2)
                self.assertEqual(bytes.fromhex("010001be"), smoke.read_frame(client))
                client.sendall(bytes.fromhex("10020001022705100320"))
                payload = smoke.read_frame(client)
                self.assertEqual(bytes.fromhex("0100342405"), payload[:5])
                self.assertEqual(55, len(payload))
                # Preset load is an audio/settings write and must never be served.
                client.sendall(smoke.encode_frame(bytes.fromhex("0001022001")))
                self.assertEqual(b"", client.recv(1))
        self.assertEqual(1, peer.connections)
        self.assertEqual(["0001022001"], peer.forbidden)
        self.assertEqual(2, len(peer.requests))

    def test_drop_mode_closes_a_real_connection_after_session_marker(self):
        with smoke.FakeDsp(mode="drop") as peer:
            with socket.create_connection(("127.0.0.1", peer.port), timeout=2) as client:
                client.settimeout(2)
                self.assertEqual(bytes.fromhex("010001be"), smoke.read_frame(client))
                client.sendall(smoke.encode_frame(bytes.fromhex("00010110")))
                self.assertEqual(b"", client.recv(1))
        self.assertEqual(["00010110"], peer.requests)
        self.assertTrue(peer.evidence()["workersStopped"])
        self.assertTrue(peer.evidence()["acceptThreadStopped"])


class ShutdownTest(unittest.TestCase):
    def test_owned_process_stops_even_when_wrapper_launch_times_out(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)",
                                      "-Dkaraf.home=" + str(home)])
            validation = smoke.Validation(Namespace(karaf_version="4.4.11"), home)
            validation.home, validation.pid, validation.ports = home, child.pid, {}

            def timed_out(*args, **kwargs):
                raise subprocess.TimeoutExpired("injected-wrapper", 30)

            validation.control = timed_out
            try:
                validation.shutdown()
                self.assertTrue(validation.result["shutdown"]["stopped"])
                self.assertIn("TimeoutExpired", validation.result["shutdown"]["wrapperStopError"])
                child.wait(timeout=2)
                self.assertEqual(-15, child.returncode)
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=2)

    def test_process_with_another_home_is_never_signalled(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)",
                                      "-Dkaraf.home=" + str(home / "other-runtime")])
            validation = smoke.Validation(Namespace(karaf_version="4.4.11"), home)
            validation.home, validation.pid, validation.ports = home, child.pid, {}
            try:
                with self.assertRaisesRegex(RuntimeError, "not owned"):
                    validation.shutdown()
                self.assertIsNone(child.poll())
            finally:
                child.terminate()
                child.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()

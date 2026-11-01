"""Boundary tests for the physical-test command guard; no device access."""
import unittest
from live_readonly import FrameStream, encode, validate_request, snapshot_artifacts
import tempfile
from pathlib import Path

class ReadOnlyGuardTest(unittest.TestCase):
    def test_artifact_snapshot_is_unchanged_when_the_build_output_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root/'input.jar'
            source.write_bytes(b'initial tested artifact')
            paths, hashes = snapshot_artifacts([source], root/'snapshot')
            source.write_bytes(b'replaced build output')
            self.assertEqual(b'initial tested artifact', paths[0].read_bytes())
            self.assertEqual(1, len(hashes))
            self.assertNotEqual(source, paths[0])

    def test_zero_command_length_is_rejected_before_response_metrics(self):
        with self.assertRaises(ValueError):
            FrameStream().accept(encode(bytes.fromhex('010000')))

    def test_audio_preset_and_firmware_writes_are_rejected(self):
        for command in [0x15, 0x20, 0x21, 0x26, 0x2D, 0x34, 0x35, 0x36,
                        0x38, 0x39, 0x3F, 0x4B, 0x4E, 0x4F, 0xFF]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                validate_request(bytes([0, 1, 1, command]))

    def test_reads_require_the_exact_read_layout_and_selector_range(self):
        for payload in [bytes.fromhex('0001022700'), bytes.fromhex('000102271f'),
                        bytes.fromhex('0001022913'), bytes.fromhex('00010456009d00')]:
            validate_request(payload)
        for payload in [bytes.fromhex('0001022720'), bytes.fromhex('0001022914'),
                        bytes.fromhex('00010127'), bytes.fromhex('0001021200'),
                        bytes.fromhex('00010456000100'), bytes.fromhex('01000140')]:
            with self.subTest(payload=payload.hex()), self.assertRaises(ValueError):
                validate_request(payload)

    def test_fragmented_requests_are_not_released_until_complete(self):
        stream = FrameStream()
        # Ordinary meter request: 10 02 | 00 01 01 40 | 10 03 | 41.
        wire = bytes.fromhex('100200010140100341')
        self.assertEqual([], stream.accept(wire[:4]))
        self.assertEqual([], stream.accept(wire[4:-1]))
        self.assertEqual([wire], stream.accept(wire[-1:]))

    def test_invalid_checksum_or_trailer_is_rejected_before_forwarding(self):
        wire = bytearray(encode(bytes.fromhex('00010140')))
        wire[-1] ^= 1
        with self.assertRaises(ValueError):
            FrameStream().accept(wire)
        with self.assertRaises(ValueError):
            FrameStream().accept(b'garbage')

    def test_literal_delimiter_in_binary_response_is_preserved(self):
        payload = bytes.fromhex('0100052400100300')
        wire = encode(payload)
        stream = FrameStream()
        self.assertEqual([wire], stream.accept(wire))

if __name__ == '__main__':
    unittest.main()

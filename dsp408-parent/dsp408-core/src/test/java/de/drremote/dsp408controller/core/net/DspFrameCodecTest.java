package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.PushbackInputStream;

import org.junit.jupiter.api.Test;

class DspFrameCodecTest {

    @Test
    void readsPayloadContainingLiteralDleEtxBytes() throws Exception {
        byte[] payload = new byte[]{
                0x01, 0x00, 0x08, 0x24,
                0x10, 0x03, 0x55, 0x66,
                0x10, 0x10, 0x00
        };
        byte[] frame = DspFrameCodec.encode(payload);

        DspFrameCodec.DecodedFrame decoded;
        try (PushbackInputStream input =
                     new PushbackInputStream(new ByteArrayInputStream(frame), 1)) {
            decoded = DspFrameCodec.readFrame(input);
        }

        assertArrayEquals(payload, decoded.payload());
        assertTrue(decoded.checksumValid());
    }
}

package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DspProtocolDynamicsPayloadTest {

    @Test
    void buildsInputGatePayloadWithConfirmedFieldOrder() {
        assertArrayEquals(
                hex("00 01 0A 3E 01 09 00 E7 03 63 00 64 00"),
                DspProtocol.buildInputGate(1, -40.0, 100.0, 10.0, 1000.0));
    }

    @Test
    void buildsOutputCompressorPayloadFromDisplayValues() {
        assertArrayEquals(
                hex("00 01 0C 30 07 09 00 09 00 E7 03 06 00 A0 00"),
                DspProtocol.buildCompressor(
                        DspChannel.OUT_4, "1:4.0", 10.0, 1000.0, 6.0, -10.0));
        assertEquals(15, DspProtocol.compressorRatioLabelToRaw("LIMIT"));
    }

    @Test
    void buildsLimiterWithFixedLimitRatioAndOneSecondRelease() {
        assertArrayEquals(
                hex("00 01 0C 3F 0B 0F 00 04 00 E7 03 0C 00 AA 00"),
                DspProtocol.buildLimiter(DspChannel.OUT_8, 5.0, 1000.0, 20, -5.0));
    }

    @Test
    void buildsExtendedInputCompressorAndKeepsItsValidation() {
        assertArrayEquals(
                hex("00 01 10 30 02 0E 00 31 00 F3 01 0C 00 DC 00 AA 00 FF 09"),
                DspProtocol.buildInputCompressor(
                        DspChannel.IN_C, 14, 49, 499, 20, 220, 170, 0x123, 12));

        assertThrows(
                IllegalArgumentException.class,
                () -> DspProtocol.buildInputCompressor(
                        DspChannel.OUT_1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> DspProtocol.buildLimiter(DspChannel.OUT_1, 0, 65_536, 0, 0));
    }

    private static byte[] hex(String value) {
        String[] tokens = value.split("\\s+");
        byte[] bytes = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            bytes[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        return bytes;
    }
}

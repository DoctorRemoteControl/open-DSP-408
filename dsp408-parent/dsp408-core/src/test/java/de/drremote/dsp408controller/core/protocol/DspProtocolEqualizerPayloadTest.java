package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DspProtocolEqualizerPayloadTest {

    @Test
    void buildsCrossoverPayloadsWithConfirmedCommandAndModeBytes() {
        assertArrayEquals(
                hex("00 01 05 32 06 1F 00 0A"),
                DspProtocol.buildCrossoverHighPass(
                        DspChannel.OUT_3, 40.3, CrossoverSlope.LR_24, false));
        assertArrayEquals(
                hex("00 01 05 31 00 1F 00 00"),
                DspProtocol.buildCrossoverLowPass(DspChannel.IN_A, 40.3, null, true));
    }

    @Test
    void buildsInputGeqPayloadWithOneBasedBandSelection() {
        assertArrayEquals(
                hex("00 01 05 48 01 1E 78 00"),
                DspProtocol.buildInputGeq(1, 31, 0.0));
    }

    @Test
    void keepsEqualizerSelectorValidationAtThePublicFacade() {
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildInputGeq(4, 1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildInputGeq(0, 32, 0.0));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildOutputPeqQRaw(1, 1, 101));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildOutputPeqGainCode(1, 1, 0xF1));
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

package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DspProtocolTestTonePayloadTest {

    @Test
    void buildsSourceAndSineSelectorPayloads() {
        assertArrayEquals(
                hex("00 01 03 39 01 00"),
                DspProtocol.buildTestToneSource(" Pink-Noise "));
        assertArrayEquals(
                hex("00 01 03 39 03 1E"),
                DspProtocol.buildTestToneSineFrequencyRaw(30));
        assertArrayEquals(hex("00 01 03 39 00 00"), DspProtocol.buildTestToneOff());
    }

    @Test
    void selectsTheNearestOriginalGuiSineFrequency() {
        assertEquals(17, DspProtocol.findNearestTestToneSineSelector(1125.0));
        assertEquals(1000.0, DspProtocol.testToneSineFrequencyHz(17));
        assertArrayEquals(
                hex("00 01 03 39 03 11"),
                DspProtocol.buildTestToneSineFrequencyHz(1125.0));
    }

    @Test
    void keepsLabelsAndSelectorValidationAtTheFacade() {
        assertEquals("analoginput", DspProtocol.testToneSourceLabel(0));
        assertEquals("sine", DspProtocol.testToneSourceLabel(3));
        assertEquals("unknown(7)", DspProtocol.testToneSourceLabel(7));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildTestToneSource(4));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.testToneSineFrequencyHz(31));
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

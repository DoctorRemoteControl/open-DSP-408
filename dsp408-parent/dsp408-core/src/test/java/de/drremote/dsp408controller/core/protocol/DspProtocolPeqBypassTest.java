package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DspProtocolPeqBypassTest {

    @Test
    void outputPeqCarriesTheConfirmedBypassTailByte() {
        byte[] active = DspProtocol.buildOutputPeq(1, 1, 0.0, 40.3, 3.0, PeqFilterType.PEAK, false);
        byte[] bypass = DspProtocol.buildOutputPeq(1, 1, 0.0, 40.3, 3.0, PeqFilterType.PEAK, true);

        assertArrayEquals(hex("00 01 0A 33 04 00 78 00 1F 00 23 00 00"), active);
        assertArrayEquals(hex("00 01 0A 33 04 00 78 00 1F 00 23 00 01"), bypass);
    }

    @Test
    void buildsUniqueBypassWritesForAll104LiveVerifiedPeqs() {
        Set<String> selectors = new HashSet<>();

        for (int input = 0; input < 4; input++) {
            for (int band = 1; band <= 8; band++) {
                byte[] payload = DspProtocol.buildInputPeq(
                        input, band, 0.0, 40.3, 3.0, PeqFilterType.PEAK, true);
                assertBypassPayload(payload, input, band - 1);
                selectors.add((payload[4] & 0xFF) + ":" + (payload[5] & 0xFF));
            }
        }

        for (int output = 1; output <= 8; output++) {
            for (int band = 1; band <= 9; band++) {
                byte[] payload = DspProtocol.buildOutputPeq(
                        output, band, 0.0, 40.3, 3.0, PeqFilterType.PEAK, true);
                assertBypassPayload(payload, output + 3, band - 1);
                selectors.add((payload[4] & 0xFF) + ":" + (payload[5] & 0xFF));
            }
        }

        assertEquals(104, selectors.size());
    }

    private static void assertBypassPayload(byte[] payload, int channel, int band) {
        assertEquals(13, payload.length);
        assertEquals(0x33, payload[3] & 0xFF);
        assertEquals(channel, payload[4] & 0xFF);
        assertEquals(band, payload[5] & 0xFF);
        assertEquals(0x01, payload[12] & 0xFF);
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

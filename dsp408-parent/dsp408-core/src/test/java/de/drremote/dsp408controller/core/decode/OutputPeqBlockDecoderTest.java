package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.PeqBandState;

class OutputPeqBlockDecoderTest {

    @Test
    void decodesSpecialOut3AndOut8FiltersAndBypassBitsFromLiveDump() {
        DspState state = new DspState();
        OutputPeqBlockDecoder decoder = new OutputPeqBlockDecoder(state, null, DspLibraryLoader.loadFir408());

        storeAndDecode(state, decoder, 0x10,
                "18 01 00 00 5B 00 2C 01 0A 00 10 10 00 06 6E 00 2C 01 08 00 F0 00 2C 01 0A 03 78 00 3F 00 23 00 78 00 5F 00 23 00 78 00 7F 00 23 00 78 00 9E 00 23 00");
        storeAndDecode(state, decoder, 0x11,
                "78 00 BE 00 23 00 78 00 DE 00 23 00 78 00 FD 00 23 00 78 00 1D 01 0A 05 0F 00 04 00 2B 01 00 00 8C 00 50 00 00 00 00 00 04 00 54 4F 50 5F 52 32 00 00");
        storeAndDecode(state, decoder, 0x1B,
                "0A 0A 00 06 6E 00 2C 01 08 00 F0 00 2C 01 23 07 78 00 3F 00 23 00 78 00 5F 00 23 00 78 00 7F 00 23 00 78 00 9E 00 23 00 78 00 BE 00 23 00 78 00 DE 00");
        storeAndDecode(state, decoder, 0x1C,
                "23 00 78 00 FD 00 23 00 00 00 00 00 00 00 0F 00 E6 03 B7 0B 0C 00 DC 00 00 00 00 00 00 00 80 00 0F 00 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 01 01");
        storeAndDecode(state, decoder, 0x1D,
                "00 00 00 00 00 00 00 00 00 00 49 6E 41 46 69 6C 65 00 49 6E 42 46 69 6C 65 00 49 6E 43 46 69 6C 65 00 49 6E 44 46 69 6C 65 00 4F 75 74 31 46 69 6C 65");

        PeqBandState out3Band1 = state.outputPeq(DspChannel.OUT_3).ensureBand(1);
        assertEquals(PeqFilterType.LOW_PASS_6, out3Band1.filterType());
        assertEquals(20160.0, out3Band1.frequencyHz(), 0.1);
        assertEquals(0.71, out3Band1.q(), 0.005);
        assertEquals(12.0, out3Band1.gainDb());
        assertTrue(out3Band1.bypass());

        PeqBandState out3Band9 = state.outputPeq(DspChannel.OUT_3).ensureBand(9);
        assertEquals(PeqFilterType.HIGH_PASS_6, out3Band9.filterType());
        assertTrue(out3Band9.bypass());

        PeqBandState out8Band1 = state.outputPeq(DspChannel.OUT_8).ensureBand(1);
        assertEquals(PeqFilterType.ALL_PASS_1, out8Band1.filterType());
        assertEquals(20160.0, out8Band1.frequencyHz(), 0.1);
        assertEquals(3.0, out8Band1.q(), 0.005);
        assertEquals(12.0, out8Band1.gainDb());
        assertFalse(out8Band1.bypass());

        PeqBandState out8Band9 = state.outputPeq(DspChannel.OUT_8).ensureBand(9);
        assertEquals(PeqFilterType.PEAK, out8Band9.filterType());
        assertEquals(19.7, out8Band9.frequencyHz(), 0.05);
        assertEquals(0.40, out8Band9.q(), 0.005);
        assertEquals(-12.0, out8Band9.gainDb());
        assertFalse(out8Band9.bypass());
    }

    private static void storeAndDecode(DspState state, BlockDecoder decoder, int blockIndex, String hex) {
        byte[] data = parseHex(hex);
        state.storeBlock(blockIndex, data);
        decoder.decode(blockIndex, data);
    }

    private static byte[] parseHex(String value) {
        String[] tokens = value.trim().split("\\s+");
        byte[] data = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            data[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        return data;
    }
}

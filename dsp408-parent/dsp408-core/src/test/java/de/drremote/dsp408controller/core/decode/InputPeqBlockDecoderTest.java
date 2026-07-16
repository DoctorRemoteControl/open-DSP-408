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

class InputPeqBlockDecoderTest {

    @Test
    void decodesInBAndInCOriginalGuiPeqValuesFromLiveDump() {
        DspState state = new DspState();
        InputPeqBlockDecoder decoder = new InputPeqBlockDecoder(state, null, DspLibraryLoader.loadFir408());

        store(state, 0x04, "23 00 01 00 31 00 F3 01 DC 00 00 00 00 00 0E 01 23 00 78 00 0B 00 23 00 78 00 30 00 23 00 78 00 5A 00 23 00 78 00 83 00 23 00 78 00 AA 00 23 00 78 00");
        store(state, 0x05, "D1 00 23 00 78 00 F8 00 23 00 78 00 22 01 23 00 00 00 31 00 F3 01 00 00 DC 00 AA 00 23 00 00 02 01 00 50 00 00 00 00 00 02 00 4D 45 41 53 55 52 45 00");
        store(state, 0x07, "00 00 0E 01 23 00 78 00 0B 00 23 00 78 00 30 00 23 00 78 00 5A 00 23 00 78 00 83 00 23 00 78 00 AA 00 23 00 78 00 D1 00 23 00 78 00 F8 00 23 00 78 00");
        store(state, 0x08, "22 01 23 00 00 00 31 00 F3 01 00 00 DC 00 AA 00 23 00 00 02 01 00 3C 00 00 00 00 00 04 00 53 50 41 52 45 00 00 00 00 00 63 00 63 00 38 00 01 00 31 00");
        byte[] bypass = store(state, 0x1C, "23 00 78 00 FD 00 23 00 00 00 00 00 00 00 0F 00 E6 03 B7 0B 0C 00 DC 00 00 00 00 00 00 00 80 00 0F 00 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 01 01");

        decoder.decode(0x1C, bypass);

        assertDefaultBand(state.inputPeq(DspChannel.IN_B).ensureBand(1), 25.4);
        assertDefaultBand(state.inputPeq(DspChannel.IN_B).ensureBand(8), 16000.0);
        assertDefaultBand(state.inputPeq(DspChannel.IN_C).ensureBand(1), 25.4);
        assertDefaultBand(state.inputPeq(DspChannel.IN_C).ensureBand(8), 16000.0);
    }

    private static void assertDefaultBand(PeqBandState band, double frequencyHz) {
        assertEquals(0.0, band.gainDb());
        assertEquals(frequencyHz, band.frequencyHz(), frequencyHz < 100.0 ? 0.05 : 10.0);
        assertEquals(3.0, band.q(), 0.005);
        assertEquals(PeqFilterType.PEAK, band.filterType());
        assertFalse(band.bypass());
        assertTrue(band.frequencyConfirmedFromDevice());
        assertTrue(band.bypassConfirmedFromDevice());
    }

    private static byte[] store(DspState state, int blockIndex, String hex) {
        String[] tokens = hex.trim().split("\\s+");
        byte[] data = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            data[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        state.storeBlock(blockIndex, data);
        return data;
    }
}

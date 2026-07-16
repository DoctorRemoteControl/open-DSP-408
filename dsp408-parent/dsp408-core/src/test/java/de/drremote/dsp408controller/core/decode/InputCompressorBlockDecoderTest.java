package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.DspState;

class InputCompressorBlockDecoderTest {

    @Test
    void decodesAllFourOriginalGuiInputCompressorPages() {
        DspState state = new DspState();
        CompressorBlockDecoder decoder = new CompressorBlockDecoder(state, null, DspLibraryLoader.loadFir408());

        storeAndDecode(state, decoder, 0x02,
                "83 00 23 00 78 00 AA 00 23 00 78 00 D1 00 23 00 78 00 F8 00 23 00 78 00 22 01 23 00 00 00 31 00 F3 01 00 00 DC 00 AA 00 23 00 00 02 01 00 50 00 00 00");
        storeAndDecode(state, decoder, 0x05,
                "D1 00 23 00 78 00 F8 00 23 00 78 00 22 01 23 00 00 00 31 00 F3 01 00 00 DC 00 AA 00 23 00 00 02 01 00 50 00 00 00 00 00 02 00 4D 45 41 53 55 52 45 00");
        storeAndDecode(state, decoder, 0x08,
                "22 01 23 00 00 00 31 00 F3 01 00 00 DC 00 AA 00 23 00 00 02 01 00 3C 00 00 00 00 00 04 00 53 50 41 52 45 00 00 00 00 00 63 00 63 00 38 00 01 00 31 00");
        storeAndDecode(state, decoder, 0x0A,
                "78 00 30 00 23 00 78 00 5A 00 23 00 78 00 83 00 23 00 78 00 AA 00 23 00 78 00 D1 00 23 00 78 00 F8 00 23 00 78 00 22 01 23 00 00 00 31 00 F3 01 00 00");
        storeAndDecode(state, decoder, 0x0B,
                "DC 00 AA 00 23 00 00 02 01 00 00 00 00 00 00 08 00 54 4F 50 5F 4C 31 00 00 01 00 18 01 18 01 00 00 18 01 00 00 5B 00 2C 01 0A 00 10 10 00 06 6E 00");

        assertBypassCompressor(state.compressor(DspChannel.IN_A));
        assertBypassCompressor(state.compressor(DspChannel.IN_B));
        assertBypassCompressor(state.compressor(DspChannel.IN_C));
        assertBypassCompressor(state.compressor(DspChannel.IN_D));
    }

    private static void assertBypassCompressor(CompressorState compressor) {
        assertEquals(0, compressor.ratioIndex());
        assertEquals("1:1.0", compressor.ratioLabel());
        assertEquals(50.0, compressor.attackMs());
        assertEquals(500.0, compressor.releaseMs());
        assertEquals(0.0, compressor.kneeDb());
        assertEquals(20.0, compressor.thresholdDb());
        assertEquals(1000.0, compressor.frequencyHz(), 0.5);
        assertEquals(0x23, compressor.typeContextRaw());
        assertEquals(0, compressor.typeRaw());
        assertEquals("Bypass", compressor.typeLabel());
        assertTrue(compressor.thresholdConfirmedFromDevice());
        assertTrue(compressor.typeConfirmedFromDevice());
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

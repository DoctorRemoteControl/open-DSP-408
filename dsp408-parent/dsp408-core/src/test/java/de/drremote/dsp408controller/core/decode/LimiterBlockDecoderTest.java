package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.LimiterState;

class LimiterBlockDecoderTest {

    @Test
    void decodesAllEightOriginalGuiLimiterPages() {
        DspState state = new DspState();
        LimiterBlockDecoder decoder = new LimiterBlockDecoder(state, null, DspLibraryLoader.loadFir408());

        putLimiterBlock(state, decoder, 0x0D, 5, 300, 0, -20.0, 10);
        putLimiterBlock(state, decoder, 0x0F, 5, 300, 0, -20.0, 18);
        putLimiterBlock(state, decoder, 0x11, 5, 300, 0, -20.0, 26);
        putLimiterBlock(state, decoder, 0x13, 5, 300, 0, -20.0, 34);
        putLimiterBlock(state, decoder, 0x15, 10, 500, 0, -20.0, 42);
        putLimiterBlock(state, decoder, 0x18, 50, 500, 0, 20.0, 0);
        putLimiterBlock(state, decoder, 0x1A, 50, 500, 0, 20.0, 8);
        putLimiterBlock(state, decoder, 0x1C, 999, 3000, 12, 20.0, 16);

        assertLimiter(state.limiter(DspChannel.OUT_1), 5, 300, 0, -20);
        assertLimiter(state.limiter(DspChannel.OUT_2), 5, 300, 0, -20);
        assertLimiter(state.limiter(DspChannel.OUT_3), 5, 300, 0, -20);
        assertLimiter(state.limiter(DspChannel.OUT_4), 5, 300, 0, -20);
        assertLimiter(state.limiter(DspChannel.OUT_5), 10, 500, 0, -20);
        assertLimiter(state.limiter(DspChannel.OUT_6), 50, 500, 0, 20);
        assertLimiter(state.limiter(DspChannel.OUT_7), 50, 500, 0, 20);
        assertLimiter(state.limiter(DspChannel.OUT_8), 999, 3000, 12, 20);
    }

    private static void putLimiterBlock(DspState state, LimiterBlockDecoder decoder, int block,
                                        int attackMs, int releaseMs, int kneeDb, double thresholdDb,
                                        int dataOffset) {
        byte[] data = new byte[50];
        putU16le(data, dataOffset, attackMs - 1);
        putU16le(data, dataOffset + 2, releaseMs - 1);
        putU16le(data, dataOffset + 4, kneeDb);
        putU16le(data, dataOffset + 6, (int) Math.round((thresholdDb + 90.0) * 2.0));
        state.storeBlock(block, data);
        decoder.decode(block, data);
    }

    private static void assertLimiter(LimiterState limiter, double attack, double release,
                                      double knee, double threshold) {
        assertEquals(attack, limiter.attackMs());
        assertEquals(release, limiter.releaseMs());
        assertEquals(knee, limiter.kneeDb());
        assertEquals(threshold, limiter.thresholdDb());
        assertTrue(limiter.attackConfirmedFromDevice());
        assertTrue(limiter.kneeConfirmedFromDevice());
    }

    private static void putU16le(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}

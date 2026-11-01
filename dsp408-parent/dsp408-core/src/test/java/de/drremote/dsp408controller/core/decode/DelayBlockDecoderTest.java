package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

class DelayBlockDecoderTest {
    @Test
    void predictedDsp408InputDelaysCannotConfirmOrOverwritePendingWrites() {
        DspState state = new DspState();
        state.markDelay(DspChannel.IN_B, 42, "tx");
        state.markDelay(DspChannel.IN_C, 43, "tx");
        DelayBlockDecoder decoder = new DelayBlockDecoder(state, null, DspLibraryLoader.loadDefault());

        decoder.decode(0x05, new byte[50]);
        decoder.decode(0x08, new byte[50]);

        assertEquals(42, state.channel(DspChannel.IN_B).delayMs());
        assertEquals(43, state.channel(DspChannel.IN_C).delayMs());
        for (DspChannel channel : new DspChannel[]{DspChannel.IN_B, DspChannel.IN_C}) {
            assertFalse(state.channel(channel).delayConfirmedFromDevice());
            assertTrue(state.channel(channel).delayDirty());
        }
    }

    @Test
    void observedDsp408InputAndOutputDelayMappingsRemainConfirmed() {
        DspState state = new DspState();
        DelayBlockDecoder decoder = new DelayBlockDecoder(state, null, DspLibraryLoader.loadDefault());
        byte[] input = new byte[50];
        input[22] = (byte) 0x80;
        input[23] = 7;
        decoder.decode(0x0B, input);
        byte[] output = new byte[50];
        output[4] = (byte) 0x80;
        output[5] = 7;
        decoder.decode(0x1C, output);

        for (DspChannel channel : new DspChannel[]{DspChannel.IN_D, DspChannel.OUT_8}) {
            assertEquals(20, state.channel(channel).delayMs());
            assertTrue(state.channel(channel).delayConfirmedFromDevice());
            assertFalse(state.channel(channel).delayDirty());
        }
    }
}

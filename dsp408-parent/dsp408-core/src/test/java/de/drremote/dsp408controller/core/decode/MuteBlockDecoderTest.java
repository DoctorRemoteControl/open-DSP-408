package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.DspState;

class MuteBlockDecoderTest {
    @Test
    void inferredFirMuteBitsCannotConfirmOrOverwritePendingWrites() {
        DspState state = new DspState();
        state.markMuted(DspChannel.IN_A, true, "tx");
        state.markMuted(DspChannel.OUT_1, true, "tx");

        new MuteBlockDecoder(state, null, DspLibraryLoader.loadFir408()).decode(0x1C, new byte[50]);

        for (DspChannel channel : new DspChannel[]{DspChannel.IN_A, DspChannel.OUT_1}) {
            assertTrue(state.channel(channel).muted());
            assertTrue(state.channel(channel).muteDirty());
            assertFalse(state.channel(channel).muteConfirmedFromDevice());
        }
    }

    @Test
    void observedFir408ReadbackUpdatesInputAndOutputMuteState() {
        DspState state = new DspState();
        byte[] block = new byte[50];
        block[32] = 0x08;
        block[34] = (byte) 0x80;

        MuteBlockDecoder decoder = new MuteBlockDecoder(state, null, DspLibraryLoader.loadFir408());
        decoder.decode(0x1C, block);

        ChannelState inA = state.channel(DspChannel.IN_A);
        ChannelState inD = state.channel(DspChannel.IN_D);
        ChannelState out1 = state.channel(DspChannel.OUT_1);
        ChannelState out8 = state.channel(DspChannel.OUT_8);

        assertNull(inA.muted());
        assertTrue(inD.muted());
        assertNull(out1.muted());
        assertFalse(inA.muteConfirmedFromDevice());
        assertFalse(out1.muteConfirmedFromDevice());
        assertTrue(out8.muted());
        assertFalse(inD.muteDirty());
        assertFalse(out8.muteDirty());
        assertTrue(inD.muteConfirmedFromDevice());
        assertTrue(out8.muteConfirmedFromDevice());
    }

    @Test
    void observedDsp408ReadbackStillUpdatesMuteState() {
        DspState state = new DspState();
        state.markMuted(DspChannel.IN_A, true, "tx");

        MuteBlockDecoder decoder = new MuteBlockDecoder(state, null, DspLibraryLoader.loadDefault());
        decoder.decode(0x1C, new byte[32]);

        ChannelState channel = state.channel(DspChannel.IN_A);
        assertFalse(channel.muted());
        assertFalse(channel.muteDirty());
        assertTrue(channel.muteConfirmedFromDevice());
    }
}

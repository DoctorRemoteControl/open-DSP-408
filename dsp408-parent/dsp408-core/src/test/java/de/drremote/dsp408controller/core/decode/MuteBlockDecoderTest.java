package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.DspState;

class MuteBlockDecoderTest {
    @Test
    void fir408PredictedReadbackDoesNotOverwriteTransmittedMuteState() {
        DspState state = new DspState();
        state.markMuted(DspChannel.IN_A, true, "tx");

        MuteBlockDecoder decoder = new MuteBlockDecoder(state, null, DspLibraryLoader.loadFir408());
        decoder.decode(0x1C, new byte[32]);

        ChannelState channel = state.channel(DspChannel.IN_A);
        assertTrue(channel.muted());
        assertTrue(channel.muteDirty());
        assertFalse(channel.muteConfirmedFromDevice());
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

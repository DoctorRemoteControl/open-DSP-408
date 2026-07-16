package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.DspState;

class PhaseBlockDecoderTest {

    @Test
    void observedFir408ReadbackConfirmsPhaseFromDevice() {
        DspState state = new DspState();
        PhaseBlockDecoder decoder = new PhaseBlockDecoder(state, null, DspLibraryLoader.loadFir408());

        byte[] block02 = new byte[50];
        block02[48] = 1;
        block02[49] = 0;
        decoder.decode(0x02, block02);

        ChannelState channel = state.channel(DspChannel.IN_A);
        assertTrue(channel.phaseInverted());
        assertTrue(channel.phaseConfirmedFromDevice());
        assertFalse(channel.phaseDirty());
    }
}

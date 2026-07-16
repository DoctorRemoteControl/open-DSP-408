package de.drremote.dsp408controller.core.library;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public record CompressorChannelLocation(
        DspChannel channel,
        PeqFieldLocation ratio,
        PeqFieldLocation attack,
        PeqFieldLocation release,
        PeqFieldLocation knee,
        PeqFieldLocation threshold,
        PeqFieldLocation frequency,
        PeqFieldLocation typeContext,
        PeqFieldLocation type
) {
}

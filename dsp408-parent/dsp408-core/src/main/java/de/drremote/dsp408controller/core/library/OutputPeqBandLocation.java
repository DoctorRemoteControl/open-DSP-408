package de.drremote.dsp408controller.core.library;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public record OutputPeqBandLocation(
        DspChannel channel,
        int bandIndex,
        PeqFieldLocation gain,
        PeqFieldLocation frequency,
        PeqFieldLocation q,
        PeqFieldLocation filterType,
        PeqFieldLocation bypassMask,
        int bypassBitIndex
) {
}

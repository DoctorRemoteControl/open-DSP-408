package de.drremote.dsp408controller.core.library;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public record LimiterChannelLocation(
        DspChannel channel,
        PeqFieldLocation attack,
        PeqFieldLocation release,
        PeqFieldLocation knee,
        PeqFieldLocation threshold
) {
}

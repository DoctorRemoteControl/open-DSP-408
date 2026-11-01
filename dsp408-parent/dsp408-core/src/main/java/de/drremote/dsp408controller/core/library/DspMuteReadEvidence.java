package de.drremote.dsp408controller.core.library;

import de.drremote.dsp408controller.core.protocol.DspChannel;

/** Per-channel evidence for the bundled mute masks; inferred FIR bits remain unknown. */
public final class DspMuteReadEvidence {
    private DspMuteReadEvidence() { }

    public static boolean supports(DspLibrary library, DspChannel channel) {
        MuteReadSpec spec = library.muteReadSpec();
        return channel != null && spec != null && spec.confirmedFromDevice()
                && (!library.hasFirFeatures() || channel == DspChannel.IN_D || channel == DspChannel.OUT_8);
    }
}

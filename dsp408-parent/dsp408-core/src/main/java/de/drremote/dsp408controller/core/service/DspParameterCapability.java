package de.drremote.dsp408controller.core.service;

import java.util.List;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public record DspParameterCapability(
        DspParameter parameter, DspChannel channel, String valueType, String unit,
        Double minimum, Double maximum, List<DspParameterStep> resolution,
        boolean writable, boolean readbackSupported, String writeEvidence, String readEvidence, String reason) {
    public DspParameterCapability {
        resolution = List.copyOf(resolution);
    }
}

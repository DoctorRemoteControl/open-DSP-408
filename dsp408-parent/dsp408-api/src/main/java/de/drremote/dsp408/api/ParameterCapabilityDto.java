package de.drremote.dsp408.api;

import java.util.List;

/** Mapping evidence is separate from live write availability and readback support. */
public record ParameterCapabilityDto(String name, String valueType, String unit,
        Double minimum, Double maximum, List<ParameterStepDto> resolution,
        boolean writable, boolean readbackSupported, String writeEvidence,
        String readEvidence, String reason) {
    public ParameterCapabilityDto {
        resolution = resolution == null ? List.of() : List.copyOf(resolution);
    }
}

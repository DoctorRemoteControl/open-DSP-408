package de.drremote.dsp408.api;

import java.util.List;

public record ChannelCapabilitiesDto(String id, String displayName, int index,
        List<ParameterCapabilityDto> parameters) {
    public ChannelCapabilitiesDto {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}

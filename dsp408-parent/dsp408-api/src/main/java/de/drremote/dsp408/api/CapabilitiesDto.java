package de.drremote.dsp408.api;

import java.util.List;

/** RAM-only snapshot; a known historical identity does not imply a live connection. */
public record CapabilitiesDto(String dspId, boolean connected, boolean firmwareRecognized,
        String deviceVersion, String libraryType, List<String> parameterNames,
        List<ChannelCapabilitiesDto> channels, String unavailableReason) {
    public CapabilitiesDto {
        parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}

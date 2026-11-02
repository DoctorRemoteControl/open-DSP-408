package de.drremote.dsp408.api;

import java.util.List;
import java.util.Objects;

/** Cached control snapshot; meters deliberately do not participate in its revision. */
public record ControlStateDto(String dspId, String instanceId, long revision, boolean connected,
        List<ChannelDto> channels, CapabilitiesDto capabilities, SettingsCacheDto settingsCache) {
    public ControlStateDto {
        Objects.requireNonNull(instanceId, "Runtime instance id is required.");
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public String etag() {
        return "\"" + instanceId + "." + revision + "\"";
    }
}

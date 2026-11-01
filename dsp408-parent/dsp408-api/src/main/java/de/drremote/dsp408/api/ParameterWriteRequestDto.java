package de.drremote.dsp408.api;

/** One typed parameter change; verification is explicit and defaults to true. */
public record ParameterWriteRequestDto(String channelId, String parameter, Object value, boolean verify) {
    public ParameterWriteRequestDto {
        if (channelId == null || channelId.isBlank() || parameter == null || parameter.isBlank()) {
            throw new IllegalArgumentException("channelId and parameter are required.");
        }
        if (value == null) throw new IllegalArgumentException("value is required.");
        value = ParameterValues.snapshot(value);
    }
    public ParameterWriteRequestDto(String channelId, String parameter, Object value) {
        this(channelId, parameter, value, true);
    }
}

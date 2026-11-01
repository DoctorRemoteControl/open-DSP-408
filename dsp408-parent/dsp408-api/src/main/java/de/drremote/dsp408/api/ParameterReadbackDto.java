package de.drremote.dsp408.api;

/** Fresh device data; receiveRevision is not advanced by local cache patches. */
public record ParameterReadbackDto(Object value, String blockIndex,
        long receiveRevision, long observedAtEpochMs) {
    public ParameterReadbackDto {
        if (value == null || blockIndex == null || blockIndex.isBlank()
                || receiveRevision <= 0 || observedAtEpochMs <= 0) {
            throw new IllegalArgumentException("Readback requires a value, block and positive receive revision/time.");
        }
        value = ParameterValues.snapshot(value);
    }
}

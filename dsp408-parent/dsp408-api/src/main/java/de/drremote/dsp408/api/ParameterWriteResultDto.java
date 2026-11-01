package de.drremote.dsp408.api;

/** Correlated outcome; transmitted=null means the request may have reached the DSP. */
public record ParameterWriteResultDto(String requestId, String dspId, String channelId,
        String parameter, Object requestedValue, Object normalizedValue,
        ParameterWriteStatus status, boolean acknowledged, Boolean transmitted,
        ParameterReadbackDto readback, String errorCode, String message, long completedAtEpochMs) {
    public ParameterWriteResultDto {
        if (status == null) throw new IllegalArgumentException("Write status is required.");
        requestedValue = ParameterValues.snapshot(requestedValue);
        normalizedValue = ParameterValues.snapshot(normalizedValue);
        if (status == ParameterWriteStatus.VERIFIED && (!acknowledged || readback == null)) {
            throw new IllegalArgumentException("Verified writes require an ACK and fresh readback.");
        }
        if (status == ParameterWriteStatus.VERIFIED
                && !java.util.Objects.equals(normalizedValue, readback.value())) {
            throw new IllegalArgumentException("Verified readback must match the normalized value.");
        }
        if (status == ParameterWriteStatus.ACKNOWLEDGED && !acknowledged) {
            throw new IllegalArgumentException("Acknowledged writes require an ACK.");
        }
        if (acknowledged && !Boolean.TRUE.equals(transmitted)) {
            throw new IllegalArgumentException("An acknowledged write was transmitted.");
        }
        if (status == ParameterWriteStatus.REJECTED && (acknowledged || !Boolean.FALSE.equals(transmitted))) {
            throw new IllegalArgumentException("Rejected writes must not have been transmitted.");
        }
    }
}

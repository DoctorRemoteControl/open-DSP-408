package de.drremote.dsp408.api;

public record AutoLimitLeaseDto(
        boolean active,
        boolean acquired,
        String owner,
        String token,
        Long expiresAtEpochMs,
        String message
) {
}

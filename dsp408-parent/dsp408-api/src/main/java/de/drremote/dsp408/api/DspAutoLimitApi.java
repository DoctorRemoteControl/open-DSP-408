package de.drremote.dsp408.api;

/** Server-side automatic limiter measurement and its client lease. */
public interface DspAutoLimitApi {
    default AutoLimitLeaseDto getAutoLimitLease() {
        return new AutoLimitLeaseDto(false, false, null, null, null, "Auto-Limit is available.");
    }

    default AutoLimitLeaseDto acquireAutoLimitLease(String owner, long ttlMs) {
        return new AutoLimitLeaseDto(false, true, owner, null, null, "Auto-Limit lease not required.");
    }

    default AutoLimitLeaseDto renewAutoLimitLease(String token, long ttlMs) {
        return getAutoLimitLease();
    }

    default AutoLimitLeaseDto releaseAutoLimitLease(String token) {
        return getAutoLimitLease();
    }

    default boolean ownsAutoLimitLease(String token) {
        return true;
    }

    default AutoLimitStatusDto getAutoLimitStatus() {
        return AutoLimitStatusDto.idle();
    }

    default AutoLimitStatusDto startAutoLimit(double headroomDb, String targetMode, long measurementMs) {
        throw new UnsupportedOperationException("Server-side Auto-Limit is not available.");
    }

    default AutoLimitStatusDto cancelAutoLimit() {
        return getAutoLimitStatus();
    }

    default AutoLimitStatusDto undoAutoLimit() {
        throw new UnsupportedOperationException("Server-side Auto-Limit undo is not available.");
    }
}

package de.drremote.dsp408controller.runtime;

final class DspReconnectSchedule {
    private final long initialDelayMs;
    private final long maximumDelayMs;
    private long retryDelayMs;
    private Long nextAttemptAtEpochMs;

    DspReconnectSchedule(long initialDelayMs, long maximumDelayMs) {
        if (initialDelayMs <= 0L || maximumDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Invalid DSP reconnect delays");
        }
        this.initialDelayMs = initialDelayMs;
        this.maximumDelayMs = maximumDelayMs;
        this.retryDelayMs = initialDelayMs;
    }

    void arm(long nowEpochMs) {
        if (nextAttemptAtEpochMs == null) {
            nextAttemptAtEpochMs = nowEpochMs + retryDelayMs;
        }
    }

    boolean isArmed() {
        return nextAttemptAtEpochMs != null;
    }

    boolean isDue(long nowEpochMs) {
        return nextAttemptAtEpochMs != null && nowEpochMs >= nextAttemptAtEpochMs;
    }

    long recordFailure(long nowEpochMs) {
        long scheduledDelayMs = retryDelayMs;
        nextAttemptAtEpochMs = nowEpochMs + scheduledDelayMs;
        retryDelayMs = Math.min(maximumDelayMs, retryDelayMs * 2L);
        return scheduledDelayMs;
    }

    void recordSuccess() {
        retryDelayMs = initialDelayMs;
        nextAttemptAtEpochMs = null;
    }

    void cancel() {
        recordSuccess();
    }

    long nextAttemptAtEpochMs() {
        return nextAttemptAtEpochMs == null ? 0L : nextAttemptAtEpochMs;
    }

    long retryDelayMs() {
        return retryDelayMs;
    }
}

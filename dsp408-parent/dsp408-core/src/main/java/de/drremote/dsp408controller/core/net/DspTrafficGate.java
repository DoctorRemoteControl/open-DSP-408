package de.drremote.dsp408controller.core.net;

import java.util.concurrent.TimeUnit;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

/**
 * Coalesces high-frequency background traffic. Request/response serialization
 * itself is enforced by {@link DspCommandDispatcher}'s single command worker.
 */
final class DspTrafficGate {
    // Keep this below the default 200 ms scheduler period. A gate equal to or
    // above that period aliases normal scheduler jitter and drops every second
    // sample, turning the intended 5 Hz meter stream into roughly 2.5 Hz.
    static final long MIN_METER_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    static final long KEEPALIVE_IDLE_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final long MIN_KEEPALIVE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private long lastMeterTransmitNanos;
    private long lastResponseNanos;
    private long firstTransmitNanos;
    private long lastNonKeepAliveTransmitNanos;
    private long lastKeepAliveTransmitNanos;

    synchronized void recordResponse(long nowNanos) {
        lastResponseNanos = nowNanos;
    }

    synchronized boolean mayTransmit(byte[] payload, long nowNanos) {
        if (DspProtocol.isKeepAliveRequest(payload)) {
            // Verified responses keep the DSP session healthy. Repeated
            // unanswered meter requests must not suppress a health probe.
            return (elapsedAtLeast(nowNanos, lastNonKeepAliveTransmitNanos, KEEPALIVE_IDLE_NANOS)
                    || elapsedAtLeast(nowNanos, lastResponseNanos == 0L ? firstTransmitNanos : lastResponseNanos,
                            KEEPALIVE_IDLE_NANOS)) && elapsedAtLeast(
                    nowNanos,
                    lastKeepAliveTransmitNanos,
                    MIN_KEEPALIVE_INTERVAL_NANOS
            );
        }
        if (!DspProtocol.isRuntimeMeterRequest(payload)) {
            return true;
        }
        return elapsedAtLeast(nowNanos, lastMeterTransmitNanos, MIN_METER_INTERVAL_NANOS);
    }

    synchronized void recordTransmitted(byte[] payload, long nowNanos) {
        if (firstTransmitNanos == 0L) firstTransmitNanos = nowNanos;
        if (DspProtocol.isKeepAliveRequest(payload)) {
            lastKeepAliveTransmitNanos = nowNanos;
        } else {
            lastNonKeepAliveTransmitNanos = nowNanos;
        }
        if (DspProtocol.isRuntimeMeterRequest(payload)) {
            lastMeterTransmitNanos = nowNanos;
        }
    }

    synchronized void reset() {
        lastResponseNanos = 0L;
        firstTransmitNanos = 0L;
        lastMeterTransmitNanos = 0L;
        lastNonKeepAliveTransmitNanos = 0L;
        lastKeepAliveTransmitNanos = 0L;
    }

    private static boolean elapsedAtLeast(long nowNanos, long previousNanos, long minimumNanos) {
        return previousNanos == 0L || nowNanos - previousNanos >= minimumNanos;
    }
}

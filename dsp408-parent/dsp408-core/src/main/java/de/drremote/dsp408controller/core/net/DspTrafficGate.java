package de.drremote.dsp408controller.core.net;

import java.util.concurrent.TimeUnit;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

/**
 * Keeps high-frequency meter consumers from flooding the single DSP command
 * connection and gives control writes a short exclusive processing window.
 */
final class DspTrafficGate {
    static final long MIN_METER_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(175);
    static final long CONTROL_QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(750);

    private long lastMeterTransmitNanos;
    private long lastControlTransmitNanos;

    boolean mayTransmit(byte[] payload, long nowNanos) {
        if (!DspProtocol.isRuntimeMeterRequest(payload)) {
            return true;
        }
        return elapsedAtLeast(nowNanos, lastMeterTransmitNanos, MIN_METER_INTERVAL_NANOS)
                && elapsedAtLeast(nowNanos, lastControlTransmitNanos, CONTROL_QUIET_NANOS);
    }

    void recordTransmitted(byte[] payload, long nowNanos) {
        if (DspProtocol.isRuntimeMeterRequest(payload)) {
            lastMeterTransmitNanos = nowNanos;
        } else if (isControlWrite(payload)) {
            lastControlTransmitNanos = nowNanos;
        }
    }

    void reset() {
        lastMeterTransmitNanos = 0L;
        lastControlTransmitNanos = 0L;
    }

    private static boolean elapsedAtLeast(long nowNanos, long previousNanos, long minimumNanos) {
        return previousNanos == 0L || nowNanos - previousNanos >= minimumNanos;
    }

    private static boolean isControlWrite(byte[] payload) {
        if (payload == null || payload.length <= 3) {
            return false;
        }
        int command = payload[3] & 0xFF;
        return command >= 0x30 && command != 0x40;
    }
}

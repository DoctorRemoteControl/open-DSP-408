package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspTrafficGateTest {
    @Test
    void coalescesDuplicateMeterRequests() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] meter = DspProtocol.runtimeMeterRequest();
        long start = TimeUnit.SECONDS.toNanos(1);

        assertTrue(gate.mayTransmit(meter, start));
        gate.recordTransmitted(meter, start);

        assertFalse(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(149)));
        assertTrue(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(150)));
    }

    @Test
    void meterCanResumeAfterAnAcknowledgedControlWrite() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] meter = DspProtocol.runtimeMeterRequest();
        byte[] gain = DspProtocol.buildGain(0, 0.0);
        long start = TimeUnit.SECONDS.toNanos(1);

        assertTrue(gate.mayTransmit(gain, start));
        gate.recordTransmitted(gain, start);

        assertTrue(gate.mayTransmit(DspProtocol.buildGain(1, 0.0), start + 1));
        assertTrue(gate.mayTransmit(meter, start + 1));
    }

    @Test
    void suppressesKeepAliveWhileRealTrafficKeepsConnectionActive() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] keepAlive = DspProtocol.keepAlive();
        long start = TimeUnit.SECONDS.toNanos(1);

        gate.recordTransmitted(DspProtocol.readParameterBlock(0x1C), start);

        assertFalse(gate.mayTransmit(
                keepAlive,
                start + DspTrafficGate.KEEPALIVE_IDLE_NANOS - 1
        ));
        assertTrue(gate.mayTransmit(
                keepAlive,
                start + DspTrafficGate.KEEPALIVE_IDLE_NANOS
        ));
    }

    @Test
    void throttlesRepeatedKeepAliveFramesDuringAnIdleSession() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] keepAlive = DspProtocol.keepAlive();
        long start = TimeUnit.SECONDS.toNanos(1);

        assertTrue(gate.mayTransmit(keepAlive, start));
        gate.recordTransmitted(keepAlive, start);

        assertFalse(gate.mayTransmit(
                keepAlive,
                start + DspTrafficGate.MIN_KEEPALIVE_INTERVAL_NANOS - 1
        ));
        assertTrue(gate.mayTransmit(
                keepAlive,
                start + DspTrafficGate.MIN_KEEPALIVE_INTERVAL_NANOS
        ));
    }

    @Test
    void unansweredMeterTrafficDoesNotSuppressKeepAlive() {
        DspTrafficGate gate = new DspTrafficGate();
        long start = TimeUnit.SECONDS.toNanos(1);
        gate.recordTransmitted(DspProtocol.runtimeMeterRequest(), start);
        // A peer that accepts TCP bytes without replying must still be probed.
        long probeTime = start + DspTrafficGate.KEEPALIVE_IDLE_NANOS;
        gate.recordTransmitted(DspProtocol.runtimeMeterRequest(), probeTime);
        assertTrue(gate.mayTransmit(DspProtocol.keepAlive(), probeTime));
    }

    @Test
    void resetClearsMeterTiming() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] meter = DspProtocol.runtimeMeterRequest();
        long start = TimeUnit.SECONDS.toNanos(1);

        gate.recordTransmitted(DspProtocol.buildGain(0, 0.0), start);
        gate.recordTransmitted(meter, start);
        gate.recordTransmitted(DspProtocol.keepAlive(), start);
        gate.reset();

        assertTrue(gate.mayTransmit(meter, start + 1));
        assertTrue(gate.mayTransmit(DspProtocol.keepAlive(), start + 1));
    }
}

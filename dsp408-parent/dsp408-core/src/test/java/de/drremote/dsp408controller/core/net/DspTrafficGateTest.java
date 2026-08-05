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

        assertFalse(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(174)));
        assertTrue(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(175)));
    }

    @Test
    void givesControlWritesAQuietWindowWithoutBlockingOtherWrites() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] meter = DspProtocol.runtimeMeterRequest();
        byte[] gain = DspProtocol.buildGain(0, 0.0);
        long start = TimeUnit.SECONDS.toNanos(1);

        assertTrue(gate.mayTransmit(gain, start));
        gate.recordTransmitted(gain, start);

        assertTrue(gate.mayTransmit(DspProtocol.buildGain(1, 0.0), start + 1));
        assertFalse(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(749)));
        assertTrue(gate.mayTransmit(meter, start + TimeUnit.MILLISECONDS.toNanos(750)));
    }

    @Test
    void resetClearsMeterAndControlTiming() {
        DspTrafficGate gate = new DspTrafficGate();
        byte[] meter = DspProtocol.runtimeMeterRequest();
        long start = TimeUnit.SECONDS.toNanos(1);

        gate.recordTransmitted(DspProtocol.buildGain(0, 0.0), start);
        gate.recordTransmitted(meter, start);
        gate.reset();

        assertTrue(gate.mayTransmit(meter, start + 1));
    }
}

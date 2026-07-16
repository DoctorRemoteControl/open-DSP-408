package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.InputGateDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspRuntimeInputGateDtoTest {

    @Test
    void exposesGateValuesAndIndependentReadbackTrust() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        runtime.withController(controller -> {
            controller.state().markInputGateFromDevice(
                    DspChannel.IN_A,
                    -90.0,
                    10.0,
                    1.0,
                    10.0,
                    "gate blocks:00"
            );
            return null;
        });

        InputGateDto inA = runtime.getInputGates().stream()
                .filter(gate -> gate.id().equals("ina"))
                .findFirst()
                .orElseThrow();
        InputGateDto inB = runtime.getInputGates().stream()
                .filter(gate -> gate.id().equals("inb"))
                .findFirst()
                .orElseThrow();

        assertEquals(4, runtime.getInputGates().size());
        assertEquals(-90.0, inA.thresholdDb());
        assertEquals(10.0, inA.holdMs());
        assertEquals(1.0, inA.attackMs());
        assertEquals(10.0, inA.releaseMs());
        assertTrue(inA.thresholdConfirmedFromDevice());
        assertTrue(inA.holdConfirmedFromDevice());
        assertTrue(inA.attackConfirmedFromDevice());
        assertTrue(inA.releaseConfirmedFromDevice());

        assertNull(inB.thresholdDb());
        assertFalse(inB.thresholdConfirmedFromDevice());
    }
}

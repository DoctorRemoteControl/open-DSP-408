package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspRuntimeMeterDtoTest {

    @Test
    void exposesLevelAuxAndLimiterStateThroughPublicDtos() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        runtime.withController(controller -> {
            controller.state().markMeterFromDevice(
                    DspChannel.IN_A,
                    0x26,
                    0x3C,
                    0x91,
                    "test-meter"
            );
            controller.state().markMeterFromDevice(
                    DspChannel.OUT_1,
                    0xA4,
                    0x3B,
                    0x76,
                    "test-meter"
            );
            controller.state().markLimiterRuntimeActive(
                    DspChannel.OUT_1,
                    true,
                    "test-limiter"
            );
            return null;
        });

        MeterStateDto meters = runtime.getMeters();
        MeterDto input = runtime.getMeter("ina");
        MeterDto output = runtime.getMeter("out1");

        assertEquals(12, meters.channels().size());
        assertEquals(0x3C26, input.rawValue());
        assertEquals(0x91, input.aux());
        assertEquals(0.02, input.levelDb(), 0.01);
        assertNull(input.limiterActive());
        assertEquals(0x3BA4, output.rawValue());
        assertEquals(0x76, output.aux());
        assertTrue(output.limiterActive());
        assertTrue(output.limiterConfirmedFromDevice());
        assertTrue(output.limiterMappingConfirmed());
    }
}

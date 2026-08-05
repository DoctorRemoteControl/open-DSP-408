package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            controller.state().markMeterClipFromDevice(
                    DspChannel.IN_A,
                    true,
                    "test-meter-threshold"
            );
            byte[] payload = new byte[46];
            payload[3] = 0x40;
            payload[40] = 0x01;
            payload[41] = 0x02;
            payload[42] = 0x03;
            payload[43] = 0x04;
            payload[44] = 0x05;
            payload[45] = 0x06;
            controller.state().markMeterRuntimePayloadFromDevice(payload, "test-cmd40");
            return null;
        });

        MeterStateDto meters = runtime.getMeters();
        MeterDto input = runtime.getMeter("ina");
        MeterDto output = runtime.getMeter("out1");

        assertEquals(12, meters.channels().size());
        assertEquals(0x3C26, input.rawValue());
        assertEquals(0x91, input.aux());
        assertEquals(0.05, input.levelDb(), 0.01);
        assertNull(input.limiterActive());
        assertTrue(input.clipActive());
        assertTrue(input.clipDerivedFromDeviceMeter());
        assertFalse(input.clipThresholdConfirmed());
        assertEquals("test-meter-threshold", input.lastClipUpdateSource());
        assertEquals(0x3BA4, output.rawValue());
        assertEquals(0x76, output.aux());
        assertTrue(output.limiterActive());
        assertTrue(output.limiterConfirmedFromDevice());
        assertTrue(output.limiterMappingConfirmed());
        assertNull(output.clipActive());
        assertEquals("00 00 00 40 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 02 03 04 05 06",
                meters.rawPayloadHex());
        assertEquals(java.util.List.of(1, 2, 3, 4, 5, 6), meters.tailBytesRaw());
        assertEquals("test-cmd40", meters.lastRawPayloadUpdateSource());
        assertTrue(meters.rawPayloadConfirmedFromDevice());
    }
}

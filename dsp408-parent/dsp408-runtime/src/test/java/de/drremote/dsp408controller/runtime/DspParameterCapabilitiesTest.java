package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.CapabilitiesDto;
import de.drremote.dsp408.api.ParameterCapabilityDto;
import de.drremote.dsp408controller.core.service.DspController;

class DspParameterCapabilitiesTest {
    @Test
    void offlineCapabilitiesHaveAllChannelsAndDoNotBootstrapHardware() {
        DspController controller = new DspController();
        AtomicReference<String> id = new AtomicReference<>("dsp-main");
        DspParameterCapabilities mapper = new DspParameterCapabilities(controller, id::get);
        CapabilitiesDto result = mapper.get();
        assertEquals("dsp-main", result.dspId());
        assertFalse(result.connected());
        assertFalse(result.firmwareRecognized());
        assertEquals(List.of("gain", "mute", "phase", "delay"), result.parameterNames());
        assertEquals(12, result.channels().size());
        assertEquals("ina", result.channels().getFirst().id());
        assertEquals("Out8", result.channels().getLast().displayName());
        assertEquals(11, result.channels().getLast().index());
        assertNotNull(result.unavailableReason());
        assertTrue(result.channels().stream().flatMap(c -> c.parameters().stream()).noneMatch(ParameterCapabilityDto::writable));
        assertEquals(0L, controller.trafficSnapshot().submittedCommands());
        id.set("dsp-renamed");
        assertEquals("dsp-renamed", mapper.get().dspId());
    }

    @Test
    void mapsKnownFirEvidenceAndQuantizationWithoutAConnection() throws Exception {
        DspController controller = new DspController();
        controller.setPreferredLibraryType("fir408");
        Field version = DspController.class.getDeclaredField("deviceVersion");
        version.setAccessible(true);
        version.set(controller, "FIR408 V0113P");
        CapabilitiesDto result = new DspParameterCapabilities(controller, () -> "fir").get();
        assertTrue(result.firmwareRecognized());
        assertFalse(result.connected());
        var gain = result.channels().getFirst().parameters().getFirst();
        assertEquals("dB", gain.unit());
        assertEquals(-60, gain.minimum());
        assertEquals(12, gain.maximum());
        assertEquals(.5, gain.resolution().getFirst().step());
        assertEquals(.1, gain.resolution().getLast().step());
        assertFalse(result.channels().getFirst().parameters().get(1).readbackSupported());
        assertEquals("inferred", result.channels().getFirst().parameters().get(1).readEvidence());
        assertTrue(result.channels().get(3).parameters().get(1).readbackSupported());
        assertTrue(result.channels().getLast().parameters().get(1).readbackSupported());
        assertFalse(result.channels().getLast().parameters().get(1).writable());
    }
}

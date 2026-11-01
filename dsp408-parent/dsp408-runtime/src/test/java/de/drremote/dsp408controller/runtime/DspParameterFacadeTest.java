package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteStatus;

class DspParameterFacadeTest {
    @Test
    void disconnectedInteractiveWriteRetainsCacheStatus() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent(false);
        var before = runtime.getSettingsCacheStatus();
        var result = runtime.writeParameterInteractive(new ParameterWriteRequestDto("out8", "gain", -14.0, false));
        assertEquals(ParameterWriteStatus.REJECTED, result.status());
        assertEquals("dsp_not_connected", result.errorCode());
        assertEquals(Boolean.FALSE, result.transmitted());
        assertEquals(before, runtime.getSettingsCacheStatus());
    }

    @Test
    void runtimePublishesItsOfflineCatalogWithoutConnecting() {
        DspRuntimeComponent runtime = new DspRuntimeComponent(false);
        var capabilities = runtime.getCapabilities();
        assertFalse(capabilities.connected());
        assertEquals(12, capabilities.channels().size());
        assertEquals(48, capabilities.channels().stream().mapToInt(c -> c.parameters().size()).sum());
        assertTrue(capabilities.channels().stream().flatMap(c -> c.parameters().stream()).noneMatch(p -> p.writable()));
    }

    @Test
    void disconnectedWriteIsRejectedAndRetainsCacheStatus() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent(false);
        var before = runtime.getSettingsCacheStatus();
        var result = runtime.writeParameter(new ParameterWriteRequestDto("out8", "gain", -14.0));
        assertEquals(ParameterWriteStatus.REJECTED, result.status());
        assertEquals("dsp_not_connected", result.errorCode());
        assertEquals(Boolean.FALSE, result.transmitted());
        assertEquals(before, runtime.getSettingsCacheStatus());
    }
}

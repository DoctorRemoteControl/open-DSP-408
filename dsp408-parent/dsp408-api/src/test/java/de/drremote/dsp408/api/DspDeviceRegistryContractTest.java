package de.drremote.dsp408.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DspDeviceRegistryContractTest {
    @Test
    void definitionNormalizesIdentityAndEndpointWithoutResolvingDns() {
        var definition = new DspDeviceDefinitionDto(" Rack.Left ", "DSP.Example.INVALID.", 9761, "AUTO", false);
        assertEquals("rack.left", definition.id());
        assertEquals("dsp.example.invalid", definition.host());
        assertEquals("auto", definition.library());
        assertEquals(new DspDeviceDefinitionDto("v6", "::1", 9761, "auto", false),
                new DspDeviceDefinitionDto("v6", "[0:0:0:0:0:0:0:1]", 9761, "auto", false));
    }

    @Test
    void invalidDefinitionsCannotReachPersistenceOrNetwork() {
        for (String host : List.of("", "https://dsp.local", "dsp.local/path", "dsp local", "dsp\n", "a:1:2", "999.1.2.3", "-bad.local", "[::1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DspDeviceDefinitionDto("one", host, 9761, "auto", false), host);
        }
        for (String id : List.of("", "a b", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DspDeviceDefinitionDto(id, "dsp.local", 9761, "auto", false));
        }
        assertThrows(IllegalArgumentException.class, () -> new DspDeviceDefinitionDto("one", "localhost", 0, "auto", false));
        assertThrows(IllegalArgumentException.class, () -> new DspDeviceDefinitionDto("one", "localhost", 65536, "auto", false));
        assertThrows(IllegalArgumentException.class, () -> new DspDeviceDefinitionDto("one", "localhost", 9761, "wrong", false));
    }

    @Test
    void registrySnapshotOwnsItsListAndLegacyProviderDefaultsAreSafe() {
        var source = new ArrayList<DspDeviceDefinitionDto>();
        var snapshot = new DspDeviceRegistryDto("managed", true, source);
        source.add(new DspDeviceDefinitionDto("one", "localhost", 9761, "auto", false));
        assertTrue(snapshot.devices().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.devices().clear());
        DspDeviceRegistryApi legacy = new DspDeviceRegistryApi() {
            public String connect() { return ""; }
            public String reconnect() { return ""; }
            public void disconnect() { }
            public boolean isConnected() { return false; }
            public DeviceInfoDto getDeviceInfo() { return null; }
        };
        assertEquals(new DspDeviceRegistryDto("unavailable", false, List.of()), legacy.getDeviceRegistry());
        assertThrows(UnsupportedOperationException.class, () -> legacy.registerDevice(source.getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> legacy.removeDevice("one"));
        assertThrows(DspDeviceNotFoundException.class, () -> legacy.getDeviceDefinition("one"));
    }
}

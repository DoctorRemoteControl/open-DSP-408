package de.drremote.dsp408.api;

import java.util.List;

/** Optional registry extension; legacy providers retain safe defaults. */
public interface DspDeviceRegistryApi extends DspConnectionApi {
    default DspDeviceRegistryDto getDeviceRegistry() {
        return new DspDeviceRegistryDto("unavailable", false, List.of());
    }

    default DspDeviceRegistrationDto registerDevice(DspDeviceDefinitionDto definition) {
        throw new UnsupportedOperationException("Managed device registration is unavailable");
    }

    default void removeDevice(String id) {
        throw new UnsupportedOperationException("Managed device registration is unavailable");
    }

    default DspDeviceDefinitionDto getDeviceDefinition(String id) {
        String normalized = DspDeviceDefinitionDto.normalizeId(id);
        return getDeviceRegistry().devices().stream()
                .filter(device -> device.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new DspDeviceNotFoundException("Unknown DSP id: " + id));
    }
}

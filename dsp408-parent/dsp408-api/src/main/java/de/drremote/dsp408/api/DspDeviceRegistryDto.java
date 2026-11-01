package de.drremote.dsp408.api;

import java.util.List;

/** A stable snapshot of registered endpoint definitions. */
public record DspDeviceRegistryDto(String mode, boolean persistent, List<DspDeviceDefinitionDto> devices) {
    public DspDeviceRegistryDto {
        devices = List.copyOf(devices);
    }
}

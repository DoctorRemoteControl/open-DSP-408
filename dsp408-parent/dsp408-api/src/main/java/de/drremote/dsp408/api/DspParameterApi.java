package de.drremote.dsp408.api;

import java.util.List;

/** Additive parameter contract; defaults keep older service providers compatible. */
public interface DspParameterApi extends DspConnectionApi {
    default CapabilitiesDto getCapabilities() {
        DeviceInfoDto info = getDeviceInfo();
        return new CapabilitiesDto(dspId(), isConnected(), false,
                info == null ? null : info.deviceVersion(), "unknown", List.of(), List.of(),
                "capabilities_unavailable");
    }
    default ParameterWriteResultDto writeParameter(ParameterWriteRequestDto request) throws Exception {
        throw new UnsupportedOperationException("This provider does not support parameter writes.");
    }
}

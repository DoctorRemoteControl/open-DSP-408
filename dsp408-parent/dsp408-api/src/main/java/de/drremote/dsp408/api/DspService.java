package de.drremote.dsp408.api;

/**
 * Backwards-compatible facade used for OSGi service registration. Consumers
 * that need a narrower contract can depend on one of the parent interfaces.
 */
public interface DspService extends DspStateApi, DspCommandApi, DspAutoLimitApi, DspControlApi, DspParameterApi, DspDeviceRegistryApi {
    @Override
    default DspService forDsp(String dspId) {
        return (DspService) DspStateApi.super.forDsp(dspId);
    }
}

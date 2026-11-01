package de.drremote.dsp408.api;

import java.util.List;

/** Device selection and connection lifecycle. */
public interface DspConnectionApi {
    default String dspId() {
        return "main";
    }

    default List<DspInstanceDto> getDspInstances() {
        return List.of(new DspInstanceDto(dspId(), null, 0, true, isConnected(), getDeviceInfo()));
    }

    default DspConnectionApi forDsp(String dspId) {
        if (dspId == null || dspId.isBlank() || dspId().equalsIgnoreCase(dspId.trim())) {
            return this;
        }
        throw new IllegalArgumentException("Unknown DSP id: " + dspId);
    }

    String connect() throws Exception;

    String reconnect() throws Exception;

    void disconnect();

    boolean isConnected();

    DeviceInfoDto getDeviceInfo();
}

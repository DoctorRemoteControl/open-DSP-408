package de.drremote.dsp408.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParameterApiContractTest {
    @Test
    void olderProviderRejectsInteractiveRatherThanCallingLegacyWrite() throws Exception {
        DspParameterApi older = new DspParameterApi() {
            public String connect() { throw new AssertionError(); }
            public String reconnect() { throw new AssertionError(); }
            public void disconnect() { throw new AssertionError(); }
            public boolean isConnected() { return false; }
            public DeviceInfoDto getDeviceInfo() { return null; }
            public ParameterWriteResultDto writeParameter(ParameterWriteRequestDto request) { throw new AssertionError("Must not call the blocking provider"); }
        };
        assertThrows(UnsupportedOperationException.class,
                () -> older.writeParameterInteractive(new ParameterWriteRequestDto("out8", "gain", -14.0, false)));
    }

    @Test
    void capabilitiesOwnTheirNestedLists() {
        List<ParameterCapabilityDto> parameters = new ArrayList<>();
        ChannelCapabilitiesDto channel = new ChannelCapabilitiesDto("ina", "InA", 0, parameters);
        List<ChannelCapabilitiesDto> channels = new ArrayList<>(List.of(channel));
        CapabilitiesDto capabilities = new CapabilitiesDto("main", false, false, null,
                "unknown", List.of("gain"), channels, "not_connected");
        parameters.add(new ParameterCapabilityDto("gain", "number", "dB", -60.0, 12.0,
                List.of(new ParameterStepDto(-60, -20, .5)), false, false,
                "unverified", "unverified", "not_connected"));
        channels.clear();
        assertEquals(1, capabilities.channels().size());
        assertTrue(capabilities.channels().getFirst().parameters().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> capabilities.channels().clear());
    }

    @Test
    void requestDefaultsToReadbackAndSnapshotsMutableNumbers() {
        AtomicInteger number = new AtomicInteger(12);
        var request = new ParameterWriteRequestDto("out8", "gain", number);
        number.set(3);
        assertTrue(request.verify());
        assertEquals(12.0, request.value());
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterWriteRequestDto("out8", "gain", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterWriteRequestDto("out8", "gain", "0"));
    }

    @Test
    void verifiedStatusRequiresAnAckAndRealReadbackEvidence() {
        assertThrows(IllegalArgumentException.class, () -> result(ParameterWriteStatus.VERIFIED, false, true, null));
        assertThrows(IllegalArgumentException.class, () -> result(ParameterWriteStatus.VERIFIED, true, true, null));
        assertThrows(IllegalArgumentException.class, () -> result(ParameterWriteStatus.REJECTED, true, true, null));
        assertThrows(IllegalArgumentException.class, () -> result(ParameterWriteStatus.VERIFIED, true, true,
                new ParameterReadbackDto(-13.0, "1C", 8L, 123L)));
        var reading = new ParameterReadbackDto(-14.0, "1C", 8L, 123L);
        assertEquals(ParameterWriteStatus.VERIFIED,
                result(ParameterWriteStatus.VERIFIED, true, true, reading).status());
    }

    @Test
    void impossibleResolutionCannotBePublished() {
        assertThrows(IllegalArgumentException.class, () -> new ParameterStepDto(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ParameterStepDto(2, 1, 1));
    }

    private ParameterWriteResultDto result(ParameterWriteStatus status, boolean ack, Boolean transmitted,
                                           ParameterReadbackDto reading) {
        return new ParameterWriteResultDto("test", "main", "out8", "gain", -14.0, -14.0,
                status, ack, transmitted, reading, null, "Test outcome", 123L);
    }
}

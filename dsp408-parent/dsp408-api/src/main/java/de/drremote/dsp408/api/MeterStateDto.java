package de.drremote.dsp408.api;

import java.util.List;

public record MeterStateDto(List<MeterDto> channels,
                            Integer statusByteRaw,
                            boolean statusConfirmedFromDevice,
                            String lastStatusUpdateSource,
                            String rawPayloadHex,
                            List<Integer> tailBytesRaw,
                            Long observedAtEpochMs,
                            boolean rawPayloadConfirmedFromDevice,
                            String lastRawPayloadUpdateSource) {
    public MeterStateDto {
        channels = channels == null ? List.of() : List.copyOf(channels);
        tailBytesRaw = tailBytesRaw == null ? List.of() : List.copyOf(tailBytesRaw);
    }

    public MeterStateDto(List<MeterDto> channels,
                         Integer statusByteRaw,
                         boolean statusConfirmedFromDevice,
                         String lastStatusUpdateSource) {
        this(
                channels,
                statusByteRaw,
                statusConfirmedFromDevice,
                lastStatusUpdateSource,
                null,
                List.of(),
                null,
                false,
                null
        );
    }
}

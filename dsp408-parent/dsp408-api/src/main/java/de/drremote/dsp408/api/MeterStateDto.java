package de.drremote.dsp408.api;

import java.util.List;

public record MeterStateDto(List<MeterDto> channels,
                            Integer statusByteRaw,
                            boolean statusConfirmedFromDevice,
                            String lastStatusUpdateSource) {
    public MeterStateDto {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}

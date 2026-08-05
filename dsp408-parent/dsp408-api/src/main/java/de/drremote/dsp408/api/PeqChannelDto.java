package de.drremote.dsp408.api;

import java.util.List;

public record PeqChannelDto(String id,
                            String displayName,
                            int index,
                            boolean input,
                            int bandCount,
                            List<PeqBandDto> bands) {
    public PeqChannelDto {
        bands = bands == null ? List.of() : List.copyOf(bands);
    }
}

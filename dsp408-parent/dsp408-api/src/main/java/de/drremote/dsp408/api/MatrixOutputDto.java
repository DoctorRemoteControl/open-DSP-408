package de.drremote.dsp408.api;

import java.util.List;

public record MatrixOutputDto(String id,
                              String displayName,
                              int index,
                              List<String> routedInputIds,
                              List<MatrixCrosspointDto> crosspoints) {
    public MatrixOutputDto {
        routedInputIds = routedInputIds == null ? List.of() : List.copyOf(routedInputIds);
        crosspoints = crosspoints == null ? List.of() : List.copyOf(crosspoints);
    }
}

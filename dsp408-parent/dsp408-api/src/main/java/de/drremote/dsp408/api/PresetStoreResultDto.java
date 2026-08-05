package de.drremote.dsp408.api;

import java.util.List;

public record PresetStoreResultDto(String slot,
                                   int index,
                                   String name,
                                   long storedAtEpochMs,
                                   boolean confirmedFromDevice,
                                   String confirmedName,
                                   List<RawTxDto> commands) {
    public PresetStoreResultDto {
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}

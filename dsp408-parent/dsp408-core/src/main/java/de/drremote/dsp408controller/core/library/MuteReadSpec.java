package de.drremote.dsp408controller.core.library;

public record MuteReadSpec(
        int blockIndex,
        int inputDataOffset,
        int outputDataOffset,
        boolean confirmedFromDevice
) {
}

package de.drremote.dsp408.api;

import java.util.List;

/**
 * Portable, read-only capture of one complete parameter-block dump.
 *
 * <p>The raw blocks are the authoritative backup. {@code decodedState} is a
 * convenient view of the parameters currently understood by the controller.
 * FIR408 does not provide a raw block-write command, so restoring the exact
 * capture is intentionally handled through a native DSP user-preset slot.</p>
 */
public record PresetSnapshotDto(String schemaVersion,
                                String dspId,
                                String libraryType,
                                long capturedAtEpochMs,
                                String currentPresetName,
                                String blocksSha256,
                                StateDto decodedState,
                                List<BlockDto> blocks) {
    public PresetSnapshotDto {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}

package de.drremote.dsp408.api;

import java.util.List;

/**
 * Status of the server-side mirror of the DSP parameter blocks.
 *
 * <p>Normal settings GET requests are served from this mirror. Only an
 * explicit refresh, a reconnect, or a preset recall reads parameter blocks
 * from the DSP.</p>
 */
public record SettingsCacheDto(
        String phase,
        boolean ready,
        int expectedBlockCount,
        int cachedBlockCount,
        List<String> missingBlockIndices,
        Long startedAtEpochMs,
        Long completedAtEpochMs,
        long generation,
        String message
) {
    public SettingsCacheDto {
        phase = phase == null || phase.isBlank() ? "EMPTY" : phase;
        expectedBlockCount = Math.max(0, expectedBlockCount);
        cachedBlockCount = Math.max(0, cachedBlockCount);
        missingBlockIndices = missingBlockIndices == null
                ? List.of()
                : List.copyOf(missingBlockIndices);
        message = message == null ? "" : message;
    }

    public static SettingsCacheDto empty() {
        return new SettingsCacheDto(
                "EMPTY",
                false,
                0,
                0,
                List.of(),
                null,
                null,
                0L,
                "DSP settings cache has not been synchronized."
        );
    }
}

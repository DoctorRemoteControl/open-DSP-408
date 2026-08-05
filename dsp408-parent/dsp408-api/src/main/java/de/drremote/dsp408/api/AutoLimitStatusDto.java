package de.drremote.dsp408.api;

import java.util.List;
import java.util.Map;

public record AutoLimitStatusDto(
        String jobId,
        String phase,
        boolean running,
        int progressPercent,
        String targetMode,
        double headroomDb,
        long measurementMs,
        String message,
        Map<String, Double> measuredPeaksDb,
        Map<String, Double> appliedThresholdsDb,
        List<String> activeLimiterIds,
        Long startedAtEpochMs,
        Long finishedAtEpochMs,
        boolean rollbackPerformed,
        boolean undoAvailable,
        String undoSourceJobId,
        Long undoCreatedAtEpochMs
) {
    public AutoLimitStatusDto {
        measuredPeaksDb = measuredPeaksDb == null ? Map.of() : Map.copyOf(measuredPeaksDb);
        appliedThresholdsDb = appliedThresholdsDb == null ? Map.of() : Map.copyOf(appliedThresholdsDb);
        activeLimiterIds = activeLimiterIds == null ? List.of() : List.copyOf(activeLimiterIds);
        progressPercent = Math.max(0, Math.min(100, progressPercent));
    }

    public static AutoLimitStatusDto idle() {
        return new AutoLimitStatusDto(
                null,
                "IDLE",
                false,
                0,
                null,
                1.0,
                6_000L,
                "Auto-Limit ist bereit.",
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                false,
                false,
                null,
                null
        );
    }
}

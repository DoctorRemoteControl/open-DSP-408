package de.drremote.dsp408controller.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AutoLimitPlanner {
    static final String ACTIVE_OUTPUTS = "ACTIVE_OUTPUTS";
    static final String ALL_OUTPUTS = "ALL_OUTPUTS";
    static final double MIN_THRESHOLD_DB = -90.0;
    static final double MAX_THRESHOLD_DB = 20.0;
    static final double MIN_SIGNAL_DB = -50.0;
    static final double CLEARANCE_STEP_DB = 1.0;
    static final double TIGHTENING_STEP_DB = 0.5;
    static final double TIGHTENING_COARSE_STEP_DB = 3.0;
    static final double METER_TO_LIMITER_OFFSET_DB = 11.0;
    static final double ATTACK_MS = 1.0;
    static final double RELEASE_MS = 300.0;
    static final long DEFAULT_MEASUREMENT_MS = 6_000L;
    static final long MIN_MEASUREMENT_MS = 1_500L;
    static final long MAX_MEASUREMENT_MS = 20_000L;

    private AutoLimitPlanner() {
    }

    static String normalizeMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "", "ALL", "ALL_OUTPUTS" -> ALL_OUTPUTS;
            case "ACTIVE", "ACTIVE_OUTPUTS" -> ACTIVE_OUTPUTS;
            default -> throw new IllegalArgumentException("Unknown Auto-Limit target mode: " + value);
        };
    }

    static double normalizeHeadroom(double headroomDb) {
        if (!Double.isFinite(headroomDb)) {
            throw new IllegalArgumentException("Auto-Limit headroom must be finite.");
        }
        return snapHalfDb(Math.max(0.0, Math.min(6.0, headroomDb)));
    }

    static long normalizeMeasurementMs(long measurementMs) {
        long requested = measurementMs <= 0L ? DEFAULT_MEASUREMENT_MS : measurementMs;
        return Math.max(MIN_MEASUREMENT_MS, Math.min(MAX_MEASUREMENT_MS, requested));
    }

    static Map<String, Double> initialThresholds(
            List<String> outputIds,
            Map<String, Double> measuredPeaksDb,
            double headroomDb,
            String targetMode
    ) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        if (measuredPeaksDb.isEmpty()) {
            return result;
        }
        if (ALL_OUTPUTS.equals(targetMode)) {
            double referencePeakDb = measuredPeaksDb.values().stream()
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElseThrow();
            double thresholdDb = initialThreshold(referencePeakDb, headroomDb);
            outputIds.forEach(id -> result.put(id, thresholdDb));
            return result;
        }
        for (String id : outputIds) {
            Double peakDb = measuredPeaksDb.get(id);
            if (peakDb != null) {
                result.put(id, initialThreshold(peakDb, headroomDb));
            }
        }
        return result;
    }

    static Map<String, Double> raiseActiveThresholds(
            Map<String, Double> currentThresholdsDb,
            Set<String> activeLimiterIds
    ) {
        LinkedHashMap<String, Double> raised = new LinkedHashMap<>();
        currentThresholdsDb.forEach((id, thresholdDb) -> raised.put(
                id,
                activeLimiterIds.contains(id)
                        ? Math.min(MAX_THRESHOLD_DB, snapHalfDb(thresholdDb + CLEARANCE_STEP_DB))
                        : thresholdDb
        ));
        return raised;
    }

    static double commonThreshold(Map<String, Double> thresholdsDb) {
        return thresholdsDb.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElseThrow();
    }

    static double lowerProbeThreshold(double currentThresholdDb) {
        return Math.max(
                MIN_THRESHOLD_DB,
                snapHalfDb(currentThresholdDb - TIGHTENING_STEP_DB)
        );
    }

    static double lowerCoarseProbeThreshold(double currentThresholdDb) {
        return Math.max(
                MIN_THRESHOLD_DB,
                snapHalfDb(currentThresholdDb - TIGHTENING_COARSE_STEP_DB)
        );
    }

    static double midpointThreshold(
            double activeThresholdDb,
            double freeThresholdDb
    ) {
        return snapHalfDb((activeThresholdDb + freeThresholdDb) / 2.0);
    }

    static double freeThresholdAboveActiveProbe(
            double activeProbeThresholdDb,
            double headroomDb
    ) {
        double clearanceDb = Math.max(
                TIGHTENING_STEP_DB,
                normalizeHeadroom(headroomDb)
        );
        return Math.min(
                MAX_THRESHOLD_DB,
                snapHalfDb(activeProbeThresholdDb + clearanceDb)
        );
    }

    private static double initialThreshold(double peakDb, double headroomDb) {
        return Math.max(
                MIN_THRESHOLD_DB,
                Math.min(
                        MAX_THRESHOLD_DB,
                        snapHalfDb(peakDb - METER_TO_LIMITER_OFFSET_DB + headroomDb)
                )
        );
    }

    private static double snapHalfDb(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}

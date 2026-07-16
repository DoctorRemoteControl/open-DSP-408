package de.drremote.dsp408controller.core.state;

/**
 * Converts the two-byte runtime meter word to the empirically calibrated
 * FIR408/DSP408 meter scale.
 */
public final class MeterLevelDecoder {
    private static final double ZERO_DB_RAW = 15397.5257354183;
    private static final double RAW_VALUES_PER_DB = 21.1860578791;
    private static final double MINIMUM_DB = -60.0;

    private MeterLevelDecoder() {
    }

    public static double decodeDb(int rawValue) {
        double db = (rawValue - ZERO_DB_RAW) / RAW_VALUES_PER_DB;
        double limited = Math.max(MINIMUM_DB, db);
        return Math.round(limited * 100.0) / 100.0;
    }
}

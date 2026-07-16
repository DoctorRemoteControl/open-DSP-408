package de.drremote.dsp408controller.core.codec;

public final class DspCodecRegistry {
    private DspCodecRegistry() {
    }

    public static final ValueCodec GAIN = new ValueCodec() {
        @Override
        public double rawToDouble(int raw) {
            int clamped = Math.max(0, Math.min(400, raw));
            return clamped <= 80
                    ? ((clamped / 2.0) - 60.0)
                    : ((clamped / 10.0) - 28.0);
        }

        @Override
        public int doubleToRaw(double value) {
            double db = clamp(value, -60.0, 12.0);
            int raw = db <= -20.0
                    ? (int) Math.round((db + 60.0) * 2.0)
                    : (int) Math.round((db + 28.0) * 10.0);

            return Math.max(0, Math.min(400, raw));
        }
    };

    public static final ValueCodec PEQ_FREQUENCY = new ValueCodec() {
        private static final double MIN_HZ = 19.6875;
        private static final double MAX_HZ = 20160.0;

        @Override
        public double rawToDouble(int raw) {
            int clamped = Math.max(0, Math.min(300, raw));
            return MIN_HZ * Math.pow(2.0, clamped / 30.0);
        }

        @Override
        public int doubleToRaw(double value) {
            double hz = clamp(value, MIN_HZ, MAX_HZ);
            double raw = 30.0 * Math.log(hz / MIN_HZ) / Math.log(2.0);
            int rounded = (int) Math.round(raw);
            return Math.max(0, Math.min(300, rounded));
        }
    };

    public static final ValueCodec PEQ_Q = new ValueCodec() {
        private static final double MIN_Q = 0.4;
        private static final double MAX_Q = 128.0;
        private static final double RAW_MAX = 100.0;

        @Override
        public double rawToDouble(int raw) {
            int clamped = Math.max(0, Math.min(100, raw));
            if (clamped == 100) {
                return MAX_Q;
            }
            return MIN_Q * Math.pow(10.0, clamped / 40.0);
        }

        @Override
        public int doubleToRaw(double value) {
            double q = clamp(value, MIN_Q, MAX_Q);
            if (q >= MAX_Q) {
                return 100;
            }
            double raw = 40.0 * Math.log10(q / MIN_Q);
            int rounded = (int) Math.round(raw);
            return Math.max(0, Math.min(100, rounded));
        }
    };

    public static final ValueCodec PEQ_GAIN = new ValueCodec() {
        @Override
        public double rawToDouble(int raw) {
            int clamped = Math.max(0, Math.min(240, raw));
            return (clamped / 10.0) - 12.0;
        }

        @Override
        public int doubleToRaw(double value) {
            double gainDb = clamp(value, -12.0, 12.0);
            int raw = (int) Math.round((gainDb + 12.0) * 10.0);
            return Math.max(0, Math.min(240, raw));
        }
    };

    public static final ValueCodec DELAY_MS = new ValueCodec() {
        private static final double MAX_MS = 680.0;
        private static final int RAW_MAX = 65280;

        @Override
        public double rawToDouble(int raw) {
            int clamped = Math.max(0, Math.min(RAW_MAX, raw));
            return clamped / 96.0;
        }

        @Override
        public int doubleToRaw(double value) {
            double ms = clamp(value, 0.0, MAX_MS);
            int raw = (int) Math.round(ms * 96.0);
            return Math.max(0, Math.min(RAW_MAX, raw));
        }
    };

    public static ValueCodec gain() {
        return GAIN;
    }

    public static ValueCodec peqFrequency() {
        return PEQ_FREQUENCY;
    }

    public static ValueCodec peqQ() {
        return PEQ_Q;
    }

    public static ValueCodec peqGain() {
        return PEQ_GAIN;
    }

    public static ValueCodec delayMs() {
        return DELAY_MS;
    }

    public static double clampDelayMs(double value) {
        return clamp(value, 0.0, 680.0);
    }

    public static double rawToDynamicsTimeMs(int raw) {
        return Math.max(0, raw) + 1.0;
    }

    public static int dynamicsTimeMsToRaw(double value, double minMs, double maxMs) {
        double ms = clamp(value, minMs, maxMs);
        return Math.max(0, (int) Math.round(ms - 1.0));
    }

    public static double rawToDynamicsThresholdDb(int raw) {
        return (Math.max(0, raw) / 2.0) - 90.0;
    }

    public static int dynamicsThresholdDbToRaw(double value, double minDb, double maxDb) {
        double db = clamp(value, minDb, maxDb);
        return Math.max(0, (int) Math.round((db + 90.0) * 2.0));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

package de.drremote.dsp408controller.core.protocol;

import java.util.Locale;

final class DspTestToneProtocol {
    private static final byte CMD_SET_TEST_TONE = 0x39;

    private static final double[] SINE_FREQUENCIES_HZ = {
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0,
            125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0,
            5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    };

    private DspTestToneProtocol() {
    }

    static byte[] buildSource(int sourceIndex) {
        checkSourceIndex(sourceIndex);
        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_TEST_TONE,
                (byte) sourceIndex,
                0x00
        };
    }

    static byte[] buildSource(String source) {
        return buildSource(parseSource(source));
    }

    static byte[] buildSineFrequencyRaw(int selectorRaw) {
        checkSineSelectorRaw(selectorRaw);
        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_TEST_TONE,
                0x03,
                (byte) selectorRaw
        };
    }

    static byte[] buildSineFrequencyHz(double hz) {
        return buildSineFrequencyRaw(findNearestSineSelector(hz));
    }

    static byte[] buildOff() {
        return buildSource(0x00);
    }

    static String sourceLabel(int sourceIndex) {
        return switch (sourceIndex) {
            case 0x00 -> "analoginput";
            case 0x01 -> "pinknoise";
            case 0x02 -> "whitenoise";
            case 0x03 -> "sine";
            default -> "unknown(" + sourceIndex + ")";
        };
    }

    static double sineFrequencyHz(int selectorRaw) {
        checkSineSelectorRaw(selectorRaw);
        return SINE_FREQUENCIES_HZ[selectorRaw];
    }

    static int findNearestSineSelector(double hz) {
        double bestDistance = Double.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < SINE_FREQUENCIES_HZ.length; i++) {
            double distance = Math.abs(SINE_FREQUENCIES_HZ[i] - hz);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int parseSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Test tone source is required");
        }

        return switch (normalize(source)) {
            case "analoginput", "analog", "input", "off" -> 0;
            case "pinknoise", "pink" -> 1;
            case "whitenoise", "white" -> 2;
            case "sine" -> 3;
            default -> throw new IllegalArgumentException("Unknown test tone source: " + source);
        };
    }

    private static String normalize(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private static void checkSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex > 3) {
            throw new IllegalArgumentException("Test tone source must be 0..3");
        }
    }

    private static void checkSineSelectorRaw(int selectorRaw) {
        if (selectorRaw < 0 || selectorRaw >= SINE_FREQUENCIES_HZ.length) {
            throw new IllegalArgumentException("Test tone sine selector must be in range 0..30");
        }
    }
}

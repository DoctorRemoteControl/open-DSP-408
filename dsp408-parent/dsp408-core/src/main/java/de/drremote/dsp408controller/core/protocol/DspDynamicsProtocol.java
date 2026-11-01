package de.drremote.dsp408controller.core.protocol;

import java.util.Locale;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

final class DspDynamicsProtocol {
    private static final byte CMD_SET_COMPRESSOR = 0x30;
    private static final byte CMD_SET_INPUT_GATE = 0x3E;
    private static final byte CMD_SET_LIMITER = 0x3F;

    private static final String[] COMPRESSOR_RATIO_LABELS = {
            "1:1.0",
            "1:1.1",
            "1:1.3",
            "1:1.5",
            "1:1.7",
            "1:2.0",
            "1:2.5",
            "1:3.0",
            "1:3.5",
            "1:4.0",
            "1:5.0",
            "1:6.0",
            "1:8.0",
            "1:10",
            "1:20",
            "LIMIT"
    };

    private DspDynamicsProtocol() {
    }

    static byte[] buildInputGate(int inputChannelIndex,
                                 double thresholdDb,
                                 double holdMs,
                                 double attackMs,
                                 double releaseMs) {
        checkInputChannelIndex(inputChannelIndex);

        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0);
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0);
        int holdRaw = DspCodecRegistry.dynamicsTimeMsToRaw(holdMs, 10.0, 999.0);
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 0.0);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_SET_INPUT_GATE,
                (byte) inputChannelIndex,
                (byte) (attackRaw & 0xFF),
                (byte) ((attackRaw >>> 8) & 0xFF),
                (byte) (releaseRaw & 0xFF),
                (byte) ((releaseRaw >>> 8) & 0xFF),
                (byte) (holdRaw & 0xFF),
                (byte) ((holdRaw >>> 8) & 0xFF),
                (byte) (thresholdRaw & 0xFF),
                (byte) ((thresholdRaw >>> 8) & 0xFF)
        };
    }

    static byte[] buildCompressor(DspChannel output,
                                  String ratioLabel,
                                  double attackMs,
                                  double releaseMs,
                                  double kneeDb,
                                  double thresholdDb) {
        int ratioRaw = compressorRatioLabelToRaw(ratioLabel);
        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0);
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0);
        int kneeRaw = clampInt((int) Math.round(kneeDb), 0, 12);
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0);
        return buildCompressor(output, ratioRaw, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
    }

    static byte[] buildCompressor(DspChannel output,
                                  int ratioRaw,
                                  int attackRaw,
                                  int releaseRaw,
                                  int kneeRaw,
                                  int thresholdRaw) {
        checkOutputChannel(output);
        checkCompressorRatioRaw(ratioRaw);

        return new byte[]{
                0x00,
                0x01,
                0x0C,
                CMD_SET_COMPRESSOR,
                (byte) output.index(),
                (byte) (ratioRaw & 0xFF),
                (byte) ((ratioRaw >>> 8) & 0xFF),
                (byte) (attackRaw & 0xFF),
                (byte) ((attackRaw >>> 8) & 0xFF),
                (byte) (releaseRaw & 0xFF),
                (byte) ((releaseRaw >>> 8) & 0xFF),
                (byte) (kneeRaw & 0xFF),
                (byte) ((kneeRaw >>> 8) & 0xFF),
                (byte) (thresholdRaw & 0xFF),
                (byte) ((thresholdRaw >>> 8) & 0xFF)
        };
    }

    static byte[] buildLimiter(DspChannel output,
                               double attackMs,
                               double releaseMs,
                               int kneeRaw,
                               double thresholdDb) {
        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0);
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0);
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0);
        return buildLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
    }

    static byte[] buildLimiter(DspChannel output,
                               int attackRaw,
                               int releaseRaw,
                               int kneeRaw,
                               int thresholdRaw) {
        checkOutputChannel(output);
        checkUInt16(attackRaw, "Limiter attack");
        checkUInt16(releaseRaw, "Limiter release");
        checkUInt16(thresholdRaw, "Limiter threshold");

        int ratioRaw = 0x0F;
        int normalizedKneeRaw = clampInt(kneeRaw, 0, 12);

        return new byte[]{
                0x00,
                0x01,
                0x0C,
                CMD_SET_LIMITER,
                (byte) output.index(),
                (byte) ratioRaw,
                0x00,
                (byte) (attackRaw & 0xFF),
                (byte) ((attackRaw >>> 8) & 0xFF),
                (byte) (releaseRaw & 0xFF),
                (byte) ((releaseRaw >>> 8) & 0xFF),
                (byte) (normalizedKneeRaw & 0xFF),
                (byte) ((normalizedKneeRaw >>> 8) & 0xFF),
                (byte) (thresholdRaw & 0xFF),
                (byte) ((thresholdRaw >>> 8) & 0xFF)
        };
    }

    static byte[] buildInputCompressor(DspChannel input,
                                       int ratioRaw,
                                       int attackRaw,
                                       int releaseRaw,
                                       int kneeRaw,
                                       int thresholdRaw,
                                       int frequencyRaw,
                                       int typeContextRaw,
                                       int typeRaw) {
        checkInputChannel(input);
        checkCompressorRatioRaw(ratioRaw);
        checkUInt16(attackRaw, "Input compressor attack");
        checkUInt16(releaseRaw, "Input compressor release");
        checkUInt16(thresholdRaw, "Input compressor threshold");
        checkUInt16(frequencyRaw, "Input compressor frequency");

        int normalizedKneeRaw = clampInt(kneeRaw, 0, 12);
        int normalizedTypeContextRaw = clampInt(typeContextRaw, 0, 0xFF);
        int normalizedTypeRaw = clampInt(typeRaw, 0, 9);

        return new byte[]{
                0x00,
                0x01,
                0x10,
                CMD_SET_COMPRESSOR,
                (byte) input.index(),
                (byte) (ratioRaw & 0xFF),
                0x00,
                (byte) (attackRaw & 0xFF),
                (byte) ((attackRaw >>> 8) & 0xFF),
                (byte) (releaseRaw & 0xFF),
                (byte) ((releaseRaw >>> 8) & 0xFF),
                (byte) normalizedKneeRaw,
                0x00,
                (byte) (thresholdRaw & 0xFF),
                (byte) ((thresholdRaw >>> 8) & 0xFF),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) normalizedTypeContextRaw,
                (byte) normalizedTypeRaw
        };
    }

    static int compressorRatioLabelToRaw(String ratioLabel) {
        if (ratioLabel == null || ratioLabel.isBlank()) {
            throw new IllegalArgumentException("Compressor ratio label is required");
        }

        String normalized = normalize(ratioLabel);
        for (int i = 0; i < COMPRESSOR_RATIO_LABELS.length; i++) {
            if (normalize(COMPRESSOR_RATIO_LABELS[i]).equals(normalized)) {
                return i;
            }
        }

        throw new IllegalArgumentException("Unknown compressor ratio label: " + ratioLabel);
    }

    private static String normalize(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private static void checkInputChannelIndex(int inputChannelIndex) {
        if (inputChannelIndex < 0 || inputChannelIndex > 3) {
            throw new IllegalArgumentException("Input channel index must be in range 0..3");
        }
    }

    private static void checkOutputChannel(DspChannel channel) {
        if (channel == null || channel.index() < 4 || channel.index() > 11) {
            throw new IllegalArgumentException("Output channel required: Out1..Out8");
        }
    }

    private static void checkInputChannel(DspChannel channel) {
        if (channel == null || channel.index() < 0 || channel.index() > 3) {
            throw new IllegalArgumentException("Input channel required: InA..InD");
        }
    }

    private static void checkCompressorRatioRaw(int ratioRaw) {
        if (ratioRaw < 0 || ratioRaw >= COMPRESSOR_RATIO_LABELS.length) {
            throw new IllegalArgumentException("Compressor ratio raw must be in range 0..15");
        }
    }

    private static void checkUInt16(int value, String fieldName) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(fieldName + " must be in range 0..65535");
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

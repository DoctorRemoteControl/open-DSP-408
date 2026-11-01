package de.drremote.dsp408controller.core.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

final class DspFirProtocol {
    private static final byte CMD_SET_FIR_GENERATOR = 0x4B;
    private static final byte CMD_SET_FIR_PROCESSING_MODE = 0x4C;
    private static final byte CMD_EXTERNAL_FIR_UPLOAD_CHUNK = 0x4E;
    private static final byte CMD_EXTERNAL_FIR_UPLOAD_BEGIN = 0x4F;
    private static final byte CMD_EXTERNAL_FIR_NAME = 0x5B;
    private static final int EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS = 12;

    private DspFirProtocol() {
    }

    static byte[] buildProcessingMode(DspChannel output, FirProcessingMode mode) {
        checkOutputChannel(output);
        if (mode == null) {
            throw new IllegalArgumentException("FIR processing mode is required");
        }

        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_FIR_PROCESSING_MODE,
                (byte) output.index(),
                (byte) mode.rawValue()
        };
    }

    static byte[] buildProcessingMode(DspChannel output, String mode) {
        return buildProcessingMode(output, FirProcessingMode.parse(mode));
    }

    static byte[] buildGenerator(DspChannel output,
                                 FirFilterType type,
                                 FirWindowFunction window,
                                 double highPassFrequencyHz,
                                 double lowPassFrequencyHz,
                                 int taps) {
        checkOutputChannel(output);
        if (type == null) {
            throw new IllegalArgumentException("FIR type is required");
        }
        if (window == null) {
            throw new IllegalArgumentException("FIR window is required");
        }

        int highPassRaw = DspCodecRegistry.peqFrequency().doubleToRaw(highPassFrequencyHz);
        int lowPassRaw = DspCodecRegistry.peqFrequency().doubleToRaw(lowPassFrequencyHz);
        int tapsRaw = tapCountToRaw(taps);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_SET_FIR_GENERATOR,
                (byte) output.index(),
                (byte) type.rawValue(),
                (byte) window.rawValue(),
                (byte) (highPassRaw & 0xFF),
                (byte) ((highPassRaw >>> 8) & 0xFF),
                (byte) (lowPassRaw & 0xFF),
                (byte) ((lowPassRaw >>> 8) & 0xFF),
                (byte) (tapsRaw & 0xFF),
                (byte) ((tapsRaw >>> 8) & 0xFF)
        };
    }

    static byte[] buildGenerator(DspChannel output,
                                 String type,
                                 String window,
                                 double highPassFrequencyHz,
                                 double lowPassFrequencyHz,
                                 int taps) {
        return buildGenerator(
                output,
                FirFilterType.parse(type),
                FirWindowFunction.parse(window),
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        );
    }

    static List<byte[]> buildExternalUpload(DspChannel channel,
                                            String name,
                                            double[] coefficients,
                                            boolean includeBeginCommand) {
        checkAnyChannel(channel);
        checkUploadCoefficients(coefficients);

        List<byte[]> payloads = new ArrayList<>();
        if (includeBeginCommand) {
            payloads.add(buildExternalUploadBegin(channel));
        }

        for (int offset = 0, chunkIndex = 0;
             offset < coefficients.length;
             offset += EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS, chunkIndex++) {
            int count = Math.min(EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS, coefficients.length - offset);
            payloads.add(buildExternalUploadChunk(
                    channel,
                    chunkIndex,
                    coefficients.length,
                    encodeExternalChunk(coefficients, offset, count)
            ));
        }

        payloads.add(buildExternalName(channel, name));
        return List.copyOf(payloads);
    }

    static byte[] buildExternalUploadBegin(DspChannel channel) {
        checkAnyChannel(channel);
        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_EXTERNAL_FIR_UPLOAD_BEGIN,
                (byte) channel.index(),
                0x00
        };
    }

    static byte[] buildExternalUploadChunk(DspChannel channel,
                                           int chunkIndex,
                                           int tapCount,
                                           byte[] coefficientData) {
        checkAnyChannel(channel);
        checkUploadTapCount(tapCount);
        if (chunkIndex < 0 || chunkIndex > 0xFF) {
            throw new IllegalArgumentException("FIR chunk index must be in range 0..255");
        }
        if (coefficientData == null
                || coefficientData.length == 0
                || coefficientData.length > EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS * 4
                || coefficientData.length % 4 != 0) {
            throw new IllegalArgumentException("FIR coefficient chunk must contain 1..12 float32 values");
        }

        byte[] payload = new byte[8 + coefficientData.length];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = (byte) (5 + coefficientData.length);
        payload[3] = CMD_EXTERNAL_FIR_UPLOAD_CHUNK;
        payload[4] = (byte) channel.index();
        payload[5] = (byte) chunkIndex;
        payload[6] = (byte) (tapCount & 0xFF);
        payload[7] = (byte) ((tapCount >>> 8) & 0xFF);
        System.arraycopy(coefficientData, 0, payload, 8, coefficientData.length);
        return payload;
    }

    static byte[] buildExternalName(DspChannel channel, String name) {
        checkAnyChannel(channel);
        byte[] nameBytes = nameBytes(name);
        byte[] payload = new byte[13];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x0A;
        payload[3] = CMD_EXTERNAL_FIR_NAME;
        payload[4] = (byte) channel.index();
        System.arraycopy(nameBytes, 0, payload, 5, nameBytes.length);
        return payload;
    }

    static int tapCountToRaw(int taps) {
        if (taps < 256 || taps > 1024 || (taps - 256) % 32 != 0) {
            throw new IllegalArgumentException("FIR taps must be in range 256..1024 with step 32");
        }
        return (taps - 256) / 32;
    }

    static int tapRawToCount(int raw) {
        if (raw < 0 || raw > 24) {
            throw new IllegalArgumentException("FIR taps raw must be in range 0..24");
        }
        return 256 + raw * 32;
    }

    private static byte[] encodeExternalChunk(double[] coefficients, int offset, int count) {
        byte[] data = new byte[count * 4];
        int out = 0;
        for (int i = 0; i < count; i++) {
            float value = (float) coefficients[offset + i];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("FIR coefficients must be finite float32 values");
            }

            int bits = Float.floatToIntBits(value);
            data[out++] = (byte) ((bits >>> 24) & 0xFF);
            data[out++] = (byte) ((bits >>> 16) & 0xFF);
            data[out++] = (byte) ((bits >>> 8) & 0xFF);
            data[out++] = (byte) (bits & 0xFF);
        }
        return data;
    }

    private static byte[] nameBytes(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("FIR name is required");
        }

        String trimmed = name.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) > 0x7F) {
                throw new IllegalArgumentException("FIR name must be ASCII");
            }
        }

        byte[] ascii = trimmed.getBytes(StandardCharsets.US_ASCII);
        byte[] fixed = new byte[8];
        Arrays.fill(fixed, (byte) 0x20);
        System.arraycopy(ascii, 0, fixed, 0, Math.min(ascii.length, fixed.length));
        return fixed;
    }

    private static void checkAnyChannel(DspChannel channel) {
        if (channel == null || channel.index() < 0 || channel.index() > 11) {
            throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
        }
    }

    private static void checkOutputChannel(DspChannel channel) {
        if (channel == null || channel.index() < 4 || channel.index() > 11) {
            throw new IllegalArgumentException("Output channel required: Out1..Out8");
        }
    }

    private static void checkUploadCoefficients(double[] coefficients) {
        if (coefficients == null || coefficients.length == 0) {
            throw new IllegalArgumentException("FIR coefficients are required");
        }
        checkUploadTapCount(coefficients.length);
        for (double coefficient : coefficients) {
            if (!Double.isFinite(coefficient)) {
                throw new IllegalArgumentException("FIR coefficients must be finite values");
            }
        }
    }

    private static void checkUploadTapCount(int tapCount) {
        if (tapCount < 1 || tapCount > 1024) {
            throw new IllegalArgumentException("External FIR tap count must be in range 1..1024");
        }
    }
}

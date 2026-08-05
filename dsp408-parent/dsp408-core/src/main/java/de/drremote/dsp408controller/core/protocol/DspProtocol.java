package de.drremote.dsp408controller.core.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

public final class DspProtocol {
    private static final byte CMD_DELAY_UNIT = 0x15;
    private static final byte CMD_PRESET_LOAD = 0x20;
    private static final byte CMD_PRESET_STORE = 0x21;
    private static final byte CMD_PRESET_NAME = 0x26;
    private static final byte CMD_READ_PRESET_NAME = 0x29;
    private static final byte CMD_HANDSHAKE_INIT = 0x10;
    private static final byte CMD_HANDSHAKE_ACK = 0x13;
    private static final byte CMD_SYSTEM_INFO = 0x2C;
    private static final byte CMD_KEEPALIVE = 0x12;
    private static final byte CMD_LOGIN = 0x2D;

    private static final byte CMD_SET_COMPRESSOR = 0x30;
    private static final byte CMD_SET_CROSSOVER_LOW_PASS = 0x31;
    private static final byte CMD_SET_CROSSOVER_HIGH_PASS = 0x32;
    private static final byte CMD_WRITE_PARAMETER = 0x33;
    private static final byte CMD_SET_GAIN = 0x34;
    private static final byte CMD_SET_MUTE = 0x35;
    private static final byte CMD_SET_PHASE = 0x36;
    private static final byte CMD_SET_DELAY = 0x38;
    private static final byte CMD_SET_TEST_TONE = 0x39;
    private static final byte CMD_SET_MATRIX_ROUTE = 0x3A;
    private static final byte CMD_SET_CHANNEL_NAME = 0x3D;
    private static final byte CMD_SET_INPUT_GATE = 0x3E;
    private static final byte CMD_SET_LIMITER = 0x3F;
    private static final byte CMD_RUNTIME_METERS = 0x40;
    private static final byte CMD_SET_MATRIX_CROSSPOINT_GAIN = 0x41;
    private static final byte CMD_SET_INPUT_GEQ = 0x48;
    private static final byte CMD_SET_FIR_GENERATOR = 0x4B;
    private static final byte CMD_SET_FIR_PROCESSING_MODE = 0x4C;
    private static final byte CMD_EXTERNAL_FIR_UPLOAD_CHUNK = 0x4E;
    private static final byte CMD_EXTERNAL_FIR_UPLOAD_BEGIN = 0x4F;
    private static final byte CMD_EXTERNAL_FIR_NAME = 0x5B;
    private static final byte CMD_READ_PARAMETER_BLOCK = 0x27;

    public static final byte[] ACK = new byte[]{0x01, 0x00, 0x01, 0x01};
    private static final int EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS = 12;

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

    private static final double[] TEST_TONE_SINE_FREQUENCIES_HZ = {
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0,
            125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0,
            5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    };

    private DspProtocol() {
    }

    public static byte[] handshakeInit() {
        return new byte[]{0x00, 0x01, 0x01, CMD_HANDSHAKE_INIT};
    }

    public static byte[] handshakeAck() {
        return new byte[]{0x00, 0x01, 0x01, CMD_HANDSHAKE_ACK};
    }

    public static byte[] systemInfoRequest() {
        return new byte[]{0x00, 0x01, 0x01, CMD_SYSTEM_INFO};
    }

    public static byte[] keepAlive() {
        return new byte[]{0x00, 0x01, 0x01, CMD_KEEPALIVE};
    }

    public static byte[] runtimeMeterRequest() {
        return new byte[]{0x00, 0x01, 0x01, CMD_RUNTIME_METERS};
    }

    public static boolean isRuntimeMeterRequest(byte[] payload) {
        return payload != null
                && payload.length == 4
                && (payload[0] & 0xFF) == 0x00
                && (payload[1] & 0xFF) == 0x01
                && (payload[2] & 0xFF) == 0x01
                && (payload[3] & 0xFF) == (CMD_RUNTIME_METERS & 0xFF);
    }

    public static boolean isAck(byte[] payload) {
        return Arrays.equals(payload, ACK);
    }

    public static boolean isHandshakeInitReply(byte[] payload) {
        return isReplyFor(payload, CMD_HANDSHAKE_INIT);
    }

    public static boolean isHandshakeAckReply(byte[] payload) {
        return isReplyFor(payload, CMD_HANDSHAKE_ACK);
    }

    public static boolean isSystemInfoReply(byte[] payload) {
        return isReplyFor(payload, CMD_SYSTEM_INFO);
    }

    public static boolean isRuntimeMeterResponse(byte[] payload) {
        return payload != null
                && payload.length >= 42
                && (payload[0] & 0xFF) == 0x01
                && (payload[3] & 0xFF) == (CMD_RUNTIME_METERS & 0xFF);
    }

    public static boolean isBlockResponse(byte[] payload) {
        return payload != null
                && payload.length >= 5
                && (payload[3] & 0xFF) == 0x24;
    }

    public static int blockResponseIndex(byte[] payload) {
        if (!isBlockResponse(payload)) {
            throw new IllegalArgumentException("Not a block response");
        }
        return payload[4] & 0xFF;
    }

    public static byte[] blockResponseData(byte[] payload) {
        if (!isBlockResponse(payload)) {
            throw new IllegalArgumentException("Not a block response");
        }
        return Arrays.copyOfRange(payload, 5, payload.length);
    }

    public static byte[] readParameterBlock(int blockIndex) {
        if (blockIndex < 0x00 || blockIndex > 0x1F) {
            throw new IllegalArgumentException("Block index must be in range 0x00..0x1F");
        }

        return new byte[]{
                0x00,
                0x01,
                0x02,
                CMD_READ_PARAMETER_BLOCK,
                (byte) blockIndex
        };
    }

    public static byte[] readPresetName(int presetIndex) {
        checkPresetIndex(presetIndex);
        return new byte[]{
                0x00,
                0x01,
                0x02,
                CMD_READ_PRESET_NAME,
                (byte) presetIndex
        };
    }

    public static byte[] loadPreset(int presetIndex) {
        checkPresetIndex(presetIndex);
        return new byte[]{
                0x00,
                0x01,
                0x02,
                CMD_PRESET_LOAD,
                (byte) presetIndex
        };
    }

    public static byte[] loadPreset(String slotLabel) {
        return loadPreset(parsePresetSlot(slotLabel));
    }

    public static byte[] storePreset(int presetIndex) {
        checkUserPresetIndex(presetIndex);
        return new byte[]{
                0x00,
                0x01,
                0x02,
                CMD_PRESET_STORE,
                (byte) presetIndex
        };
    }

    public static byte[] storePreset(String slotLabel) {
        return storePreset(parsePresetSlot(slotLabel));
    }

    public static byte[] buildPresetName(String name) {
        byte[] ascii = presetNameBytes(name);
        byte[] payload = new byte[18];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x0F;
        payload[3] = CMD_PRESET_NAME;
        Arrays.fill(payload, 4, payload.length, (byte) 0x20);
        System.arraycopy(ascii, 0, payload, 4, ascii.length);
        return payload;
    }

    public static boolean isPresetNameResponse(byte[] payload) {
        return payload != null
                && payload.length >= 5
                && (payload[0] & 0xFF) == 0x01
                && (payload[3] & 0xFF) == (CMD_READ_PRESET_NAME & 0xFF);
    }

    public static int presetNameResponseIndex(byte[] payload) {
        if (!isPresetNameResponse(payload)) {
            throw new IllegalArgumentException("Not a preset-name response");
        }
        return payload[4] & 0xFF;
    }

    public static String presetNameResponseName(byte[] payload) {
        if (!isPresetNameResponse(payload)) {
            throw new IllegalArgumentException("Not a preset-name response");
        }
        int end = payload.length;
        while (end > 5 && (payload[end - 1] == 0x00 || payload[end - 1] == 0x20)) {
            end--;
        }
        return new String(payload, 5, end - 5, StandardCharsets.US_ASCII);
    }

    public static int parsePresetSlot(String slotLabel) {
        if (slotLabel == null || slotLabel.isBlank()) {
            throw new IllegalArgumentException("Preset slot is required");
        }

        String normalized = slotLabel.trim().toUpperCase(Locale.ROOT);
        if ("F00".equals(normalized)) {
            return 0;
        }

        if (normalized.matches("U\\d{2}")) {
            int userIndex = Integer.parseInt(normalized.substring(1));
            if (userIndex < 1 || userIndex > 19) {
                throw new IllegalArgumentException("User preset must be U01..U19");
            }
            return userIndex;
        }

        throw new IllegalArgumentException("Preset slot must be F00 or U01..U19");
    }

    public static byte[] login(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits");
        }

        byte[] ascii = pin.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[5 + ascii.length];

        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x06;
        payload[3] = CMD_LOGIN;
        payload[4] = 0x00;
        System.arraycopy(ascii, 0, payload, 5, ascii.length);

        return payload;
    }

    public static byte[] buildDelayUnit(int unitRaw) {
        checkDelayUnit(unitRaw);
        return new byte[]{
                0x00,
                0x01,
                0x02,
                CMD_DELAY_UNIT,
                (byte) unitRaw
        };
    }

    public static byte[] buildDelayUnit(String unit) {
        return buildDelayUnit(parseDelayUnit(unit));
    }

    public static byte[] buildChannelName(DspChannel channel, String name) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel is required");
        }
        if (name == null) {
            throw new IllegalArgumentException("Channel name is required");
        }

        byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length > 8) {
            throw new IllegalArgumentException("Channel name must be max 8 ASCII bytes");
        }

        for (byte b : ascii) {
            if ((b & 0x80) != 0) {
                throw new IllegalArgumentException("Channel name must be ASCII");
            }
        }

        byte[] payload = new byte[13];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x0A;
        payload[3] = CMD_SET_CHANNEL_NAME;
        payload[4] = (byte) channel.index();
        System.arraycopy(ascii, 0, payload, 5, ascii.length);
        return payload;
    }

    public static byte[] buildMute(int selector, boolean muted) {
        checkChannel(selector);

        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_MUTE,
                (byte) selector,
                (byte) (muted ? 0x01 : 0x00)
        };
    }

    public static byte[] buildGain(int channelIndex, double db) {
        checkChannel(channelIndex);

        int raw = dbToRaw(db);
        int low = raw & 0xFF;
        int high = (raw >>> 8) & 0xFF;

        return new byte[]{
                0x00,
                0x01,
                0x04,
                CMD_SET_GAIN,
                (byte) channelIndex,
                (byte) low,
                (byte) high
        };
    }

    public static byte[] buildPhase(int channelIndex, boolean inverted) {
        checkChannel(channelIndex);

        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_PHASE,
                (byte) channelIndex,
                (byte) (inverted ? 0x01 : 0x00)
        };
    }

    public static byte[] buildDelay(int channelIndex, double ms) {
        checkChannel(channelIndex);

        int raw = DspCodecRegistry.delayMs().doubleToRaw(ms);
        return new byte[]{
                0x00,
                0x01,
                0x04,
                CMD_SET_DELAY,
                (byte) channelIndex,
                (byte) (raw & 0xFF),
                (byte) ((raw >>> 8) & 0xFF)
        };
    }

    public static byte[] buildMatrixRoute(DspChannel output, DspChannel input) {
        return buildMatrixRoutes(output, java.util.List.of(input));
    }

    public static byte[] buildMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) {
        checkOutputChannel(output);
        if (inputs == null) {
            throw new IllegalArgumentException("Matrix inputs are required");
        }

        int inputMask = 0;
        for (DspChannel input : inputs) {
            checkInputChannel(input);
            inputMask |= matrixInputMask(input);
        }

        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_MATRIX_ROUTE,
                (byte) output.index(),
                (byte) inputMask
        };
    }

    public static byte[] buildMatrixCrosspointGain(DspChannel output, DspChannel input, double db) {
        checkOutputChannel(output);
        checkInputChannel(input);

        int raw = dbToRaw(Math.max(-60.0, Math.min(0.0, db)));

        return new byte[]{
                0x00,
                0x01,
                0x05,
                CMD_SET_MATRIX_CROSSPOINT_GAIN,
                (byte) output.index(),
                (byte) matrixInputIndex(input),
                (byte) (raw & 0xFF),
                (byte) ((raw >>> 8) & 0xFF)
        };
    }

    public static byte[] buildCrossoverHighPass(DspChannel channel,
                                                double frequencyHz,
                                                CrossoverSlope slope,
                                                boolean bypass) {
        return buildCrossover(CMD_SET_CROSSOVER_HIGH_PASS, channel, frequencyHz, slope, bypass);
    }

    public static byte[] buildCrossoverLowPass(DspChannel channel,
                                               double frequencyHz,
                                               CrossoverSlope slope,
                                               boolean bypass) {
        return buildCrossover(CMD_SET_CROSSOVER_LOW_PASS, channel, frequencyHz, slope, bypass);
    }

    public static byte[] buildOutputPeqFrequency(int outputNumber, int peqIndex, double hz) {
        return buildOutputPeq(outputNumber, peqIndex, 0.0, hz, 3.0, PeqFilterType.PEAK);
    }

    public static byte[] buildOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) {
        if (qRaw < 0 || qRaw > 100) {
            throw new IllegalArgumentException("PEQ Q raw must be in range 0..100");
        }
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(0.0),
                defaultPeqFrequencyRaw(peqIndex),
                qRaw,
                PeqFilterType.PEAK
        );
    }

    public static byte[] buildOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) {
        if (gainCode < 0 || gainCode > 0x00F0) {
            throw new IllegalArgumentException("PEQ gain code must be in range 0x0000..0x00F0");
        }
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                gainCode,
                defaultPeqFrequencyRaw(peqIndex),
                DspCodecRegistry.peqQ().doubleToRaw(3.0),
                PeqFilterType.PEAK
        );
    }

    public static byte[] buildOutputPeq(int outputNumber,
                                        int peqIndex,
                                        double gainDb,
                                        double frequencyHz,
                                        double q,
                                        PeqFilterType filterType) {
        return buildOutputPeq(outputNumber, peqIndex, gainDb, frequencyHz, q, filterType, false);
    }

    public static byte[] buildOutputPeq(int outputNumber,
                                        int peqIndex,
                                        double gainDb,
                                        double frequencyHz,
                                        double q,
                                        PeqFilterType filterType,
                                        boolean bypass) {
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(gainDb),
                DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz),
                DspCodecRegistry.peqQ().doubleToRaw(q),
                filterType,
                bypass
        );
    }

    public static byte[] buildInputPeq(int inputChannelIndex,
                                       int peqIndex,
                                       double gainDb,
                                       double frequencyHz,
                                       double q,
                                       PeqFilterType filterType,
                                       boolean bypass) {
        return buildInputPeqRaw(
                inputChannelIndex,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(gainDb),
                DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz),
                DspCodecRegistry.peqQ().doubleToRaw(q),
                filterType,
                bypass
        );
    }

    public static byte[] buildInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                gainDb,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                PeqFilterType.PEAK,
                false
        );
    }

    public static byte[] buildInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                frequencyHz,
                3.0,
                PeqFilterType.PEAK,
                false
        );
    }

    public static byte[] buildInputPeqQ(int inputChannelIndex, int peqIndex, double q) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                q,
                PeqFilterType.PEAK,
                false
        );
    }

    public static byte[] buildInputPeqType(int inputChannelIndex,
                                           int peqIndex,
                                           PeqFilterType filterType) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                filterType,
                false
        );
    }

    public static byte[] buildInputPeqBypass(int inputChannelIndex,
                                             int peqIndex,
                                             boolean bypass) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                PeqFilterType.PEAK,
                bypass
        );
    }

    public static byte[] buildInputGeq(int inputChannelIndex, int bandIndex, double gainDb) {
        checkInputChannelIndex(inputChannelIndex);
        checkInputGeqBandIndex(bandIndex);

        int gainRaw = DspCodecRegistry.peqGain().doubleToRaw(gainDb);
        return new byte[]{
                0x00,
                0x01,
                0x05,
                CMD_SET_INPUT_GEQ,
                (byte) inputChannelIndex,
                (byte) (bandIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF)
        };
    }

    public static byte[] buildFirProcessingMode(DspChannel output, FirProcessingMode mode) {
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

    public static byte[] buildFirProcessingMode(DspChannel output, String mode) {
        return buildFirProcessingMode(output, FirProcessingMode.parse(mode));
    }

    public static byte[] buildFirGenerator(DspChannel output,
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
        int tapsRaw = firTapCountToRaw(taps);

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

    public static byte[] buildFirGenerator(DspChannel output,
                                           String type,
                                           String window,
                                           double highPassFrequencyHz,
                                           double lowPassFrequencyHz,
                                           int taps) {
        return buildFirGenerator(
                output,
                FirFilterType.parse(type),
                FirWindowFunction.parse(window),
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        );
    }

    public static List<byte[]> buildExternalFirUpload(DspChannel channel,
                                                      String name,
                                                      double[] coefficients,
                                                      boolean includeBeginCommand) {
        checkAnyChannel(channel);
        checkFirUploadCoefficients(coefficients);

        List<byte[]> payloads = new ArrayList<>();
        if (includeBeginCommand) {
            payloads.add(buildExternalFirUploadBegin(channel));
        }

        for (int offset = 0, chunkIndex = 0; offset < coefficients.length; offset += EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS, chunkIndex++) {
            int count = Math.min(EXTERNAL_FIR_UPLOAD_CHUNK_FLOATS, coefficients.length - offset);
            payloads.add(buildExternalFirUploadChunk(
                    channel,
                    chunkIndex,
                    coefficients.length,
                    encodeExternalFirChunk(coefficients, offset, count)
            ));
        }

        payloads.add(buildExternalFirName(channel, name));
        return List.copyOf(payloads);
    }

    public static byte[] buildExternalFirUploadBegin(DspChannel channel) {
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

    public static byte[] buildExternalFirUploadChunk(DspChannel channel,
                                                     int chunkIndex,
                                                     int tapCount,
                                                     byte[] coefficientData) {
        checkAnyChannel(channel);
        checkFirUploadTapCount(tapCount);
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

    public static byte[] buildExternalFirName(DspChannel channel, String name) {
        checkAnyChannel(channel);
        byte[] nameBytes = firNameBytes(name);
        byte[] payload = new byte[13];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x0A;
        payload[3] = CMD_EXTERNAL_FIR_NAME;
        payload[4] = (byte) channel.index();
        System.arraycopy(nameBytes, 0, payload, 5, nameBytes.length);
        return payload;
    }

    public static byte[] buildInputGate(int inputChannelIndex,
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

    public static byte[] buildCompressor(DspChannel output,
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

    public static byte[] buildCompressor(DspChannel output,
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

    public static byte[] buildLimiter(DspChannel output,
                                      double attackMs,
                                      double releaseMs,
                                      int kneeRaw,
                                      double thresholdDb) {
        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0);
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0);
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0);
        return buildLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
    }

    public static byte[] buildLimiter(DspChannel output,
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

    public static byte[] buildInputCompressor(DspChannel input,
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

    public static byte[] buildTestToneSource(int sourceIndex) {
        checkTestToneSourceIndex(sourceIndex);
        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_TEST_TONE,
                (byte) sourceIndex,
                0x00
        };
    }

    public static byte[] buildTestToneSource(String source) {
        return buildTestToneSource(parseTestToneSource(source));
    }

    public static byte[] buildTestToneSineFrequencyRaw(int selectorRaw) {
        checkTestToneSineSelectorRaw(selectorRaw);
        return new byte[]{
                0x00,
                0x01,
                0x03,
                CMD_SET_TEST_TONE,
                0x03,
                (byte) selectorRaw
        };
    }

    public static byte[] buildTestToneSineFrequencyHz(double hz) {
        return buildTestToneSineFrequencyRaw(findNearestTestToneSineSelector(hz));
    }

    public static byte[] buildTestToneOff() {
        return buildTestToneSource(0x00);
    }

    public static String testToneSourceLabel(int sourceIndex) {
        return switch (sourceIndex) {
            case 0x00 -> "analoginput";
            case 0x01 -> "pinknoise";
            case 0x02 -> "whitenoise";
            case 0x03 -> "sine";
            default -> "unknown(" + sourceIndex + ")";
        };
    }

    public static double testToneSineFrequencyHz(int selectorRaw) {
        checkTestToneSineSelectorRaw(selectorRaw);
        return TEST_TONE_SINE_FREQUENCIES_HZ[selectorRaw];
    }

    public static int findNearestTestToneSineSelector(double hz) {
        return nearestTestToneSineSelector(hz);
    }

    public static int compressorRatioLabelToRaw(String ratioLabel) {
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

    public static int hzToPeqRaw(double hz) {
        return DspCodecRegistry.peqFrequency().doubleToRaw(hz);
    }

    public static int firTapCountToRaw(int taps) {
        if (taps < 256 || taps > 1024 || (taps - 256) % 32 != 0) {
            throw new IllegalArgumentException("FIR taps must be in range 256..1024 with step 32");
        }
        return (taps - 256) / 32;
    }

    public static int firTapRawToCount(int raw) {
        if (raw < 0 || raw > 24) {
            throw new IllegalArgumentException("FIR taps raw must be in range 0..24");
        }
        return 256 + raw * 32;
    }

    public static double clampDb(double db) {
        return Math.max(-60.0, Math.min(12.0, db));
    }

    public static int dbToRaw(double db) {
        return DspCodecRegistry.gain().doubleToRaw(db);
    }

    public static double rawToDb(int raw) {
        return DspCodecRegistry.gain().rawToDouble(raw);
    }

    private static boolean isReplyFor(byte[] payload, int command) {
        return payload != null
                && payload.length >= 4
                && (payload[3] & 0xFF) == (command & 0xFF);
    }

    private static byte[] buildOutputPeqRaw(int outputNumber,
                                            int peqIndex,
                                            int gainRaw,
                                            int frequencyRaw,
                                            int qRaw,
                                            PeqFilterType filterType) {
        return buildOutputPeqRaw(outputNumber, peqIndex, gainRaw, frequencyRaw, qRaw, filterType, false);
    }

    private static byte[] buildOutputPeqRaw(int outputNumber,
                                            int peqIndex,
                                            int gainRaw,
                                            int frequencyRaw,
                                            int qRaw,
                                            PeqFilterType filterType,
                                            boolean bypass) {
        checkOutputNumber(outputNumber);
        checkPeqIndex(peqIndex);
        checkPeqQRaw(qRaw);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_WRITE_PARAMETER,
                (byte) (outputNumber + 3),
                (byte) (peqIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) (qRaw & 0xFF),
                (byte) filterType.rawValue(),
                (byte) (bypass ? 0x01 : 0x00)
        };
    }

    private static byte[] buildCrossover(byte command,
                                         DspChannel channel,
                                         double frequencyHz,
                                         CrossoverSlope slope,
                                         boolean bypass) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel is required");
        }
        if (!bypass && slope == null) {
            throw new IllegalArgumentException("Crossover slope is required when bypass is off");
        }

        int frequencyRaw = DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz);
        int modeRaw = bypass ? 0x00 : slope.rawValue();

        return new byte[]{
                0x00,
                0x01,
                0x05,
                command,
                (byte) channel.index(),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) modeRaw
        };
    }

    private static byte[] buildInputPeqRaw(int inputChannelIndex,
                                           int peqIndex,
                                           int gainRaw,
                                           int frequencyRaw,
                                           int qRaw,
                                           PeqFilterType filterType,
                                           boolean bypass) {
        checkInputChannelIndex(inputChannelIndex);
        checkInputPeqIndex(peqIndex);
        checkPeqQRaw(qRaw);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_WRITE_PARAMETER,
                (byte) inputChannelIndex,
                (byte) (peqIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) qRaw,
                (byte) filterType.rawValue(),
                (byte) (bypass ? 0x01 : 0x00)
        };
    }

    private static int defaultPeqFrequencyRaw(int peqIndex) {
        double hz = switch (peqIndex) {
            case 1 -> 40.3;
            case 2 -> 84.4;
            case 3 -> 176.8;
            case 4 -> 370.3;
            case 5 -> 757.9;
            case 6 -> 1590.0;
            case 7 -> 3320.0;
            case 8 -> 6810.0;
            case 9 -> 14250.0;
            default -> throw new IllegalArgumentException("PEQ index must be in range 1..9");
        };
        return DspCodecRegistry.peqFrequency().doubleToRaw(hz);
    }

    private static double defaultInputPeqFrequency(int peqIndex) {
        return switch (peqIndex) {
            case 1 -> 40.3;
            case 2 -> 84.4;
            case 3 -> 176.8;
            case 4 -> 370.3;
            case 5 -> 757.9;
            case 6 -> 1590.0;
            case 7 -> 3320.0;
            case 8 -> 6810.0;
            default -> throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
        };
    }

    private static int parseDelayUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Delay unit is required");
        }

        return switch (normalize(unit)) {
            case "ms" -> 0;
            case "m", "meter", "meters" -> 1;
            case "ft", "feet" -> 2;
            default -> throw new IllegalArgumentException("Delay unit must be ms|m|ft");
        };
    }

    private static int parseTestToneSource(String source) {
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

    private static int nearestTestToneSineSelector(double hz) {
        double bestDistance = Double.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < TEST_TONE_SINE_FREQUENCIES_HZ.length; i++) {
            double distance = Math.abs(TEST_TONE_SINE_FREQUENCIES_HZ[i] - hz);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static String normalize(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private static byte[] encodeExternalFirChunk(double[] coefficients, int offset, int count) {
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

    private static byte[] firNameBytes(String name) {
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

    private static void checkChannel(int channelIndex) {
        if (channelIndex < 0 || channelIndex > 11) {
            throw new IllegalArgumentException("Channel must be in range 0..11");
        }
    }

    private static void checkAnyChannel(DspChannel channel) {
        if (channel == null || channel.index() < 0 || channel.index() > 11) {
            throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
        }
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

    private static int matrixInputMask(DspChannel input) {
        return switch (input) {
            case IN_A -> 0x01;
            case IN_B -> 0x02;
            case IN_C -> 0x04;
            case IN_D -> 0x08;
            default -> throw new IllegalArgumentException("Input channel required: InA..InD");
        };
    }

    private static int matrixInputIndex(DspChannel input) {
        checkInputChannel(input);
        return input.index();
    }

    private static void checkOutputNumber(int outputNumber) {
        if (outputNumber < 1 || outputNumber > 8) {
            throw new IllegalArgumentException("Output number must be in range 1..8");
        }
    }

    private static void checkPeqIndex(int peqIndex) {
        if (peqIndex < 1 || peqIndex > 9) {
            throw new IllegalArgumentException("PEQ index must be in range 1..9");
        }
    }

    private static void checkInputPeqIndex(int peqIndex) {
        if (peqIndex < 1 || peqIndex > 8) {
            throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
        }
    }

    private static void checkInputGeqBandIndex(int bandIndex) {
        if (bandIndex < 1 || bandIndex > 31) {
            throw new IllegalArgumentException("Input GEQ band index must be in range 1..31");
        }
    }

    private static void checkFirUploadCoefficients(double[] coefficients) {
        if (coefficients == null || coefficients.length == 0) {
            throw new IllegalArgumentException("FIR coefficients are required");
        }
        checkFirUploadTapCount(coefficients.length);
        for (double coefficient : coefficients) {
            if (!Double.isFinite(coefficient)) {
                throw new IllegalArgumentException("FIR coefficients must be finite values");
            }
        }
    }

    private static void checkFirUploadTapCount(int tapCount) {
        if (tapCount < 1 || tapCount > 1024) {
            throw new IllegalArgumentException("External FIR tap count must be in range 1..1024");
        }
    }

    private static void checkPeqQRaw(int qRaw) {
        if (qRaw < 0 || qRaw > 100) {
            throw new IllegalArgumentException("PEQ Q raw must be in range 0..100");
        }
    }

    private static void checkPresetIndex(int presetIndex) {
        if (presetIndex < 0 || presetIndex > 19) {
            throw new IllegalArgumentException("Preset index must be in range 0..19");
        }
    }

    private static void checkUserPresetIndex(int presetIndex) {
        if (presetIndex < 1 || presetIndex > 19) {
            throw new IllegalArgumentException("Writable preset index must be in range 1..19");
        }
    }

    private static byte[] presetNameBytes(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Preset name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 14) {
            throw new IllegalArgumentException("Preset name must contain at most 14 ASCII characters");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char value = trimmed.charAt(index);
            if (value < 0x20 || value > 0x7E) {
                throw new IllegalArgumentException("Preset name must contain printable ASCII characters only");
            }
        }
        return trimmed.getBytes(StandardCharsets.US_ASCII);
    }

    private static void checkDelayUnit(int unitRaw) {
        if (unitRaw < 0 || unitRaw > 2) {
            throw new IllegalArgumentException("Delay unit must be 0=ms, 1=m, 2=ft");
        }
    }

    private static void checkCompressorRatioRaw(int ratioRaw) {
        if (ratioRaw < 0 || ratioRaw >= COMPRESSOR_RATIO_LABELS.length) {
            throw new IllegalArgumentException("Compressor ratio raw must be in range 0..15");
        }
    }

    private static void checkTestToneSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex > 3) {
            throw new IllegalArgumentException("Test tone source must be 0..3");
        }
    }

    private static void checkTestToneSineSelectorRaw(int selectorRaw) {
        if (selectorRaw < 0 || selectorRaw >= TEST_TONE_SINE_FREQUENCIES_HZ.length) {
            throw new IllegalArgumentException("Test tone sine selector must be in range 0..30");
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

package de.drremote.dsp408controller.core.protocol;

import java.util.Arrays;
import java.util.List;

public final class DspProtocol {
    public static final byte[] ACK = new byte[]{0x01, 0x00, 0x01, 0x01};

    private DspProtocol() {
    }

    public static byte[] handshakeInit() {
		return DspSessionProtocol.handshakeInit();
    }

    public static byte[] handshakeAck() {
		return DspSessionProtocol.handshakeAck();
    }

    public static byte[] systemInfoRequest() {
		return DspSessionProtocol.systemInfoRequest();
    }

    public static byte[] startupZeroBlockRequest() {
		return DspSessionProtocol.startupZeroBlockRequest();
    }

    public static byte[] startupStatusRequest() {
		return DspSessionProtocol.startupStatusRequest();
    }

    public static byte[] readFirStartupWindow(int offset) {
		return DspSessionProtocol.readFirStartupWindow(offset);
    }

    public static byte[] keepAlive() {
		return DspSessionProtocol.keepAlive();
    }

    public static boolean isKeepAliveRequest(byte[] payload) {
		return DspSessionProtocol.isKeepAliveRequest(payload);
    }

    public static byte[] runtimeMeterRequest() {
		return DspSessionProtocol.runtimeMeterRequest();
    }

    public static boolean isRuntimeMeterRequest(byte[] payload) {
		return DspSessionProtocol.isRuntimeMeterRequest(payload);
    }

    public static boolean isAck(byte[] payload) {
        return Arrays.equals(payload, ACK);
    }

    public static boolean isHandshakeInitReply(byte[] payload) {
		return DspSessionProtocol.isHandshakeInitReply(payload);
    }

    public static boolean isHandshakeAckReply(byte[] payload) {
		return DspSessionProtocol.isHandshakeAckReply(payload);
    }

    public static boolean isSystemInfoReply(byte[] payload) {
		return DspSessionProtocol.isSystemInfoReply(payload);
    }

    public static boolean isRuntimeMeterResponse(byte[] payload) {
		return DspSessionProtocol.isRuntimeMeterResponse(payload);
    }

    public static boolean isBlockResponse(byte[] payload) {
		return DspSessionProtocol.isBlockResponse(payload);
    }

    public static int blockResponseIndex(byte[] payload) {
		return DspSessionProtocol.blockResponseIndex(payload);
    }

    public static byte[] blockResponseData(byte[] payload) {
		return DspSessionProtocol.blockResponseData(payload);
    }

    public static byte[] readParameterBlock(int blockIndex) {
		return DspSessionProtocol.readParameterBlock(blockIndex);
    }

    public static byte[] readPresetName(int presetIndex) {
		return DspSessionProtocol.readPresetName(presetIndex);
    }

    public static byte[] loadPreset(int presetIndex) {
		return DspSessionProtocol.loadPreset(presetIndex);
    }

    public static byte[] loadPreset(String slotLabel) {
		return DspSessionProtocol.loadPreset(slotLabel);
    }

    public static byte[] storePreset(int presetIndex) {
		return DspSessionProtocol.storePreset(presetIndex);
    }

    public static byte[] storePreset(String slotLabel) {
		return DspSessionProtocol.storePreset(slotLabel);
    }

    public static byte[] buildPresetName(String name) {
		return DspSessionProtocol.buildPresetName(name);
    }

    public static boolean isPresetNameResponse(byte[] payload) {
		return DspSessionProtocol.isPresetNameResponse(payload);
    }

    public static int presetNameResponseIndex(byte[] payload) {
		return DspSessionProtocol.presetNameResponseIndex(payload);
    }

    public static String presetNameResponseName(byte[] payload) {
		return DspSessionProtocol.presetNameResponseName(payload);
    }

    public static int parsePresetSlot(String slotLabel) {
		return DspSessionProtocol.parsePresetSlot(slotLabel);
    }

    public static byte[] login(String pin) {
		return DspSessionProtocol.login(pin);
    }

    public static byte[] buildDelayUnit(int unitRaw) {
		return DspSessionProtocol.buildDelayUnit(unitRaw);
    }

    public static byte[] buildDelayUnit(String unit) {
		return DspSessionProtocol.buildDelayUnit(unit);
    }

    public static byte[] buildChannelName(DspChannel channel, String name) {
		return DspChannelProtocol.buildChannelName(channel, name);
    }

    public static byte[] buildMute(int selector, boolean muted) {
		return DspChannelProtocol.buildMute(selector, muted);
    }

    public static byte[] buildGain(int channelIndex, double db) {
		return DspChannelProtocol.buildGain(channelIndex, db);
    }

    public static byte[] buildPhase(int channelIndex, boolean inverted) {
		return DspChannelProtocol.buildPhase(channelIndex, inverted);
    }

    public static byte[] buildDelay(int channelIndex, double ms) {
		return DspChannelProtocol.buildDelay(channelIndex, ms);
    }

    public static byte[] buildMatrixRoute(DspChannel output, DspChannel input) {
		return DspChannelProtocol.buildMatrixRoute(output, input);
    }

    public static byte[] buildMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) {
		return DspChannelProtocol.buildMatrixRoutes(output, inputs);
    }

    public static byte[] buildMatrixCrosspointGain(DspChannel output, DspChannel input, double db) {
		return DspChannelProtocol.buildMatrixCrosspointGain(output, input, db);
    }

    public static byte[] buildCrossoverHighPass(DspChannel channel,
                                                double frequencyHz,
                                                CrossoverSlope slope,
                                                boolean bypass) {
        return DspEqualizerProtocol.buildCrossoverHighPass(channel, frequencyHz, slope, bypass);
    }

    public static byte[] buildCrossoverLowPass(DspChannel channel,
                                               double frequencyHz,
                                               CrossoverSlope slope,
                                               boolean bypass) {
        return DspEqualizerProtocol.buildCrossoverLowPass(channel, frequencyHz, slope, bypass);
    }

    public static byte[] buildOutputPeqFrequency(int outputNumber, int peqIndex, double hz) {
        return DspEqualizerProtocol.buildOutputPeqFrequency(outputNumber, peqIndex, hz);
    }

    public static byte[] buildOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) {
        return DspEqualizerProtocol.buildOutputPeqQRaw(outputNumber, peqIndex, qRaw);
    }

    public static byte[] buildOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) {
        return DspEqualizerProtocol.buildOutputPeqGainCode(outputNumber, peqIndex, gainCode);
    }

    public static byte[] buildOutputPeq(int outputNumber,
                                        int peqIndex,
                                        double gainDb,
                                        double frequencyHz,
                                        double q,
                                        PeqFilterType filterType) {
        return DspEqualizerProtocol.buildOutputPeq(
                outputNumber, peqIndex, gainDb, frequencyHz, q, filterType);
    }

    public static byte[] buildOutputPeq(int outputNumber,
                                        int peqIndex,
                                        double gainDb,
                                        double frequencyHz,
                                        double q,
                                        PeqFilterType filterType,
                                        boolean bypass) {
        return DspEqualizerProtocol.buildOutputPeq(
                outputNumber, peqIndex, gainDb, frequencyHz, q, filterType, bypass);
    }

    public static byte[] buildInputPeq(int inputChannelIndex,
                                       int peqIndex,
                                       double gainDb,
                                       double frequencyHz,
                                       double q,
                                       PeqFilterType filterType,
                                       boolean bypass) {
        return DspEqualizerProtocol.buildInputPeq(
                inputChannelIndex, peqIndex, gainDb, frequencyHz, q, filterType, bypass);
    }

    public static byte[] buildInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) {
        return DspEqualizerProtocol.buildInputPeqGain(inputChannelIndex, peqIndex, gainDb);
    }

    public static byte[] buildInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) {
        return DspEqualizerProtocol.buildInputPeqFrequency(inputChannelIndex, peqIndex, frequencyHz);
    }

    public static byte[] buildInputPeqQ(int inputChannelIndex, int peqIndex, double q) {
        return DspEqualizerProtocol.buildInputPeqQ(inputChannelIndex, peqIndex, q);
    }

    public static byte[] buildInputPeqType(int inputChannelIndex,
                                           int peqIndex,
                                           PeqFilterType filterType) {
        return DspEqualizerProtocol.buildInputPeqType(inputChannelIndex, peqIndex, filterType);
    }

    public static byte[] buildInputPeqBypass(int inputChannelIndex,
                                             int peqIndex,
                                             boolean bypass) {
        return DspEqualizerProtocol.buildInputPeqBypass(inputChannelIndex, peqIndex, bypass);
    }

    public static byte[] buildInputGeq(int inputChannelIndex, int bandIndex, double gainDb) {
        return DspEqualizerProtocol.buildInputGeq(inputChannelIndex, bandIndex, gainDb);
    }

    public static byte[] buildFirProcessingMode(DspChannel output, FirProcessingMode mode) {
        return DspFirProtocol.buildProcessingMode(output, mode);
    }

    public static byte[] buildFirProcessingMode(DspChannel output, String mode) {
        return DspFirProtocol.buildProcessingMode(output, mode);
    }

    public static byte[] buildFirGenerator(DspChannel output,
                                           FirFilterType type,
                                           FirWindowFunction window,
                                           double highPassFrequencyHz,
                                           double lowPassFrequencyHz,
                                           int taps) {
        return DspFirProtocol.buildGenerator(
                output, type, window, highPassFrequencyHz, lowPassFrequencyHz, taps);
    }

    public static byte[] buildFirGenerator(DspChannel output,
                                           String type,
                                           String window,
                                           double highPassFrequencyHz,
                                           double lowPassFrequencyHz,
                                           int taps) {
        return DspFirProtocol.buildGenerator(
                output, type, window, highPassFrequencyHz, lowPassFrequencyHz, taps);
    }

    public static List<byte[]> buildExternalFirUpload(DspChannel channel,
                                                      String name,
                                                      double[] coefficients,
                                                      boolean includeBeginCommand) {
        return DspFirProtocol.buildExternalUpload(channel, name, coefficients, includeBeginCommand);
    }

    public static byte[] buildExternalFirUploadBegin(DspChannel channel) {
        return DspFirProtocol.buildExternalUploadBegin(channel);
    }

    public static byte[] buildExternalFirUploadChunk(DspChannel channel,
                                                     int chunkIndex,
                                                     int tapCount,
                                                     byte[] coefficientData) {
        return DspFirProtocol.buildExternalUploadChunk(channel, chunkIndex, tapCount, coefficientData);
    }

    public static byte[] buildExternalFirName(DspChannel channel, String name) {
        return DspFirProtocol.buildExternalName(channel, name);
    }

    public static byte[] buildInputGate(int inputChannelIndex,
                                        double thresholdDb,
                                        double holdMs,
                                        double attackMs,
                                        double releaseMs) {
        return DspDynamicsProtocol.buildInputGate(
                inputChannelIndex, thresholdDb, holdMs, attackMs, releaseMs);
    }

    public static byte[] buildCompressor(DspChannel output,
                                         String ratioLabel,
                                         double attackMs,
                                         double releaseMs,
                                         double kneeDb,
                                         double thresholdDb) {
        return DspDynamicsProtocol.buildCompressor(
                output, ratioLabel, attackMs, releaseMs, kneeDb, thresholdDb);
    }

    public static byte[] buildCompressor(DspChannel output,
                                         int ratioRaw,
                                         int attackRaw,
                                         int releaseRaw,
                                         int kneeRaw,
                                         int thresholdRaw) {
        return DspDynamicsProtocol.buildCompressor(
                output, ratioRaw, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
    }

    public static byte[] buildLimiter(DspChannel output,
                                      double attackMs,
                                      double releaseMs,
                                      int kneeRaw,
                                      double thresholdDb) {
        return DspDynamicsProtocol.buildLimiter(output, attackMs, releaseMs, kneeRaw, thresholdDb);
    }

    public static byte[] buildLimiter(DspChannel output,
                                      int attackRaw,
                                      int releaseRaw,
                                      int kneeRaw,
                                      int thresholdRaw) {
        return DspDynamicsProtocol.buildLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
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
        return DspDynamicsProtocol.buildInputCompressor(
                input,
                ratioRaw,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw,
                frequencyRaw,
                typeContextRaw,
                typeRaw);
    }

    public static byte[] buildTestToneSource(int sourceIndex) {
        return DspTestToneProtocol.buildSource(sourceIndex);
    }

    public static byte[] buildTestToneSource(String source) {
        return DspTestToneProtocol.buildSource(source);
    }

    public static byte[] buildTestToneSineFrequencyRaw(int selectorRaw) {
        return DspTestToneProtocol.buildSineFrequencyRaw(selectorRaw);
    }

    public static byte[] buildTestToneSineFrequencyHz(double hz) {
        return DspTestToneProtocol.buildSineFrequencyHz(hz);
    }

    public static byte[] buildTestToneOff() {
        return DspTestToneProtocol.buildOff();
    }

    public static String testToneSourceLabel(int sourceIndex) {
        return DspTestToneProtocol.sourceLabel(sourceIndex);
    }

    public static double testToneSineFrequencyHz(int selectorRaw) {
        return DspTestToneProtocol.sineFrequencyHz(selectorRaw);
    }

    public static int findNearestTestToneSineSelector(double hz) {
        return DspTestToneProtocol.findNearestSineSelector(hz);
    }

    public static int compressorRatioLabelToRaw(String ratioLabel) {
        return DspDynamicsProtocol.compressorRatioLabelToRaw(ratioLabel);
    }

    public static int hzToPeqRaw(double hz) {
        return DspProtocolValues.hzToPeqRaw(hz);
    }

    public static int firTapCountToRaw(int taps) {
        return DspFirProtocol.tapCountToRaw(taps);
    }

    public static int firTapRawToCount(int raw) {
        return DspFirProtocol.tapRawToCount(raw);
    }

    public static double clampDb(double db) {
        return DspProtocolValues.clampDb(db);
    }

    public static int dbToRaw(double db) {
        return DspProtocolValues.dbToRaw(db);
    }

    public static double rawToDb(int raw) {
        return DspProtocolValues.rawToDb(raw);
    }

}

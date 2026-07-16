package de.drremote.dsp408.api;

import java.util.List;

public interface DspService {
    default String dspId() {
        return "main";
    }

    default List<DspInstanceDto> getDspInstances() {
        return List.of(new DspInstanceDto(dspId(), null, 0, true, isConnected(), getDeviceInfo()));
    }

    default DspService forDsp(String dspId) {
        if (dspId == null || dspId.isBlank() || dspId().equalsIgnoreCase(dspId.trim())) {
            return this;
        }
        throw new IllegalArgumentException("Unknown DSP id: " + dspId);
    }

    String helpText();

    String executeShell(String line) throws Exception;

    String executeMatrix(String line, boolean isAdmin) throws Exception;

    String executeVolumeRoom(String line) throws Exception;

    String connect() throws Exception;

    String reconnect() throws Exception;

    void disconnect();

    boolean isConnected();

    DeviceInfoDto getDeviceInfo();

    List<ChannelDto> getChannels();

    ChannelDto getChannel(String channelId);

    default List<InputGateDto> getInputGates() {
        return List.of();
    }

    default List<InputCompressorDto> getInputCompressors() {
        return List.of();
    }

    default InputCompressorDto getInputCompressor(String channelId) {
        String requested = channelId == null ? "" : channelId.trim();
        return getInputCompressors().stream()
                .filter(compressor -> compressor.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown input compressor channel: " + channelId));
    }

    default List<LimiterDto> getLimiters() {
        return List.of();
    }

    default LimiterDto getLimiter(String channelId) {
        String requested = channelId == null ? "" : channelId.trim();
        return getLimiters().stream()
                .filter(limiter -> limiter.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown limiter output channel: " + channelId));
    }

    default MeterStateDto getMeters() {
        return new MeterStateDto(List.of(), null, false, null);
    }

    default MeterDto getMeter(String channelId) {
        String requested = channelId == null ? "" : channelId.trim();
        return getMeters().channels().stream()
                .filter(meter -> meter.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown meter channel: " + channelId));
    }

    ChannelDto setGain(String channelId, double db) throws Exception;

    ChannelDto mute(String channelId) throws Exception;

    ChannelDto unmute(String channelId) throws Exception;

    List<String> getCachedBlockIndices();

    BlockDto getCachedBlock(String blockIndexHex);

    BlockDto readBlock(String blockIndexHex) throws Exception;

    StateDto scanBlocks() throws Exception;

    RawTxDto sendRawHex(String payloadHex) throws Exception;

    void loginPin(String pin) throws Exception;

    RawTxDto loadPreset(String slotLabel) throws Exception;

    RawTxDto loadPreset(int presetIndex) throws Exception;

    RawTxDto readPresetName(int presetIndex) throws Exception;

    RawTxDto setDelayUnit(String unit) throws Exception;

    RawTxDto setChannelName(String channelId, String name) throws Exception;

    ChannelDto setPhase(String channelId, boolean inverted) throws Exception;

    ChannelDto setDelay(String channelId, double ms) throws Exception;

    RawTxDto setMatrixRoute(String outputId, String inputId) throws Exception;

    RawTxDto setMatrixRoutes(String outputId, List<String> inputIds) throws Exception;

    RawTxDto setMatrixCrosspointGain(String outputId, String inputId, double db) throws Exception;

    RawTxDto setCrossoverHighPass(String channelId, double frequencyHz, String slope, boolean bypass) throws Exception;

    RawTxDto setCrossoverHighPassFrequency(String channelId, double frequencyHz) throws Exception;

    RawTxDto setCrossoverHighPassSlope(String channelId, String slope) throws Exception;

    RawTxDto setCrossoverHighPassBypass(String channelId, boolean bypass) throws Exception;

    RawTxDto setCrossoverLowPass(String channelId, double frequencyHz, String slope, boolean bypass) throws Exception;

    RawTxDto setCrossoverLowPassFrequency(String channelId, double frequencyHz) throws Exception;

    RawTxDto setCrossoverLowPassSlope(String channelId, String slope) throws Exception;

    RawTxDto setCrossoverLowPassBypass(String channelId, boolean bypass) throws Exception;

    RawTxDto setFirProcessingMode(String outputId, String mode) throws Exception;

    RawTxDto setFirGenerator(String outputId, String type, String window, double highPassFrequencyHz,
                             double lowPassFrequencyHz, int taps) throws Exception;

    List<RawTxDto> uploadExternalFir(String channelId, String name, List<Double> coefficients) throws Exception;

    RawTxDto setOutputPeq(String channelId, int peqIndex, String filterType, double frequencyHz, double q, double gainDb)
            throws Exception;

    RawTxDto setOutputPeqFrequency(String channelId, int peqIndex, double hz) throws Exception;

    RawTxDto setOutputPeqQ(String channelId, int peqIndex, double q) throws Exception;

    RawTxDto setOutputPeqQRaw(String channelId, int peqIndex, int raw) throws Exception;

    RawTxDto setOutputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception;

    RawTxDto setOutputPeqGainCode(String channelId, int peqIndex, int code) throws Exception;

    RawTxDto setOutputPeqType(String channelId, int peqIndex, String filterType) throws Exception;

    RawTxDto setInputPeq(String channelId, int peqIndex, String filterType, double frequencyHz, double q, double gainDb,
                         boolean bypass) throws Exception;

    RawTxDto setInputPeqFrequency(String channelId, int peqIndex, double frequencyHz) throws Exception;

    RawTxDto setInputPeqQ(String channelId, int peqIndex, double q) throws Exception;

    RawTxDto setInputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception;

    RawTxDto setInputPeqType(String channelId, int peqIndex, String filterType) throws Exception;

    RawTxDto setInputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception;

    RawTxDto setInputGeq(String channelId, int bandIndex, double gainDb) throws Exception;

    RawTxDto setInputGate(String channelId, double thresholdDb, double holdMs, double attackMs, double releaseMs)
            throws Exception;

    RawTxDto setInputGateThreshold(String channelId, double thresholdDb) throws Exception;

    RawTxDto setInputGateHold(String channelId, double holdMs) throws Exception;

    RawTxDto setInputGateAttack(String channelId, double attackMs) throws Exception;

    RawTxDto setInputGateRelease(String channelId, double releaseMs) throws Exception;

    RawTxDto setCompressor(String inputId, double thresholdDb, String ratioLabel, double attackMs, double releaseMs,
                           double kneeDb) throws Exception;

    RawTxDto setLimiter(String outputId, double thresholdDb, double attackMs, double releaseMs) throws Exception;

    RawTxDto setTestToneSource(String source) throws Exception;

    RawTxDto setTestToneSineFrequency(double hz) throws Exception;

    RawTxDto setTestToneSineFrequencyRaw(int selectorRaw) throws Exception;

    RawTxDto disableTestTone() throws Exception;

    StateDto requestRuntimeMeters() throws Exception;
}

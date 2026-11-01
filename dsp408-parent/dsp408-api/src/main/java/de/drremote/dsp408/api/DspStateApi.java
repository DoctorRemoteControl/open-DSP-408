package de.drremote.dsp408.api;

import java.util.List;

/** Read-only device state and transport diagnostics. */
public interface DspStateApi extends DspConnectionApi {
    List<ChannelDto> getChannels();

    ChannelDto getChannel(String channelId);

    default List<MatrixOutputDto> getMatrixOutputs() {
        return List.of();
    }

    default MatrixOutputDto getMatrixOutput(String outputId) {
        String requested = outputId == null ? "" : outputId.trim();
        return getMatrixOutputs().stream()
                .filter(output -> output.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown matrix output: " + outputId));
    }

    default List<PeqChannelDto> getPeqChannels() {
        return List.of();
    }

    default PeqChannelDto getPeqChannel(String channelId) {
        String requested = channelId == null ? "" : channelId.trim();
        return getPeqChannels().stream()
                .filter(channel -> channel.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown PEQ channel: " + channelId));
    }

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

    default TrafficStatusDto getTrafficStatus() {
        return new TrafficStatusDto(
                isConnected(),
                0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                null, null, false, null, null
        );
    }

    default SettingsCacheDto getSettingsCacheStatus() {
        return SettingsCacheDto.empty();
    }

    List<String> getCachedBlockIndices();

    BlockDto getCachedBlock(String blockIndexHex);
}

package de.drremote.dsp408.api;

import java.util.List;

public record StateDto(boolean connected,
                       DeviceInfoDto deviceInfo,
                       List<ChannelDto> channels,
                       List<String> cachedBlockIndices,
                       List<MatrixOutputDto> matrixOutputs,
                       List<PeqChannelDto> peqChannels,
                       List<InputGateDto> inputGates,
                       List<InputCompressorDto> inputCompressors,
                       List<LimiterDto> limiters,
                       MeterStateDto meters) {
    public StateDto {
        channels = channels == null ? List.of() : List.copyOf(channels);
        cachedBlockIndices = cachedBlockIndices == null ? List.of() : List.copyOf(cachedBlockIndices);
        matrixOutputs = matrixOutputs == null ? List.of() : List.copyOf(matrixOutputs);
        peqChannels = peqChannels == null ? List.of() : List.copyOf(peqChannels);
        inputGates = inputGates == null ? List.of() : List.copyOf(inputGates);
        inputCompressors = inputCompressors == null ? List.of() : List.copyOf(inputCompressors);
        limiters = limiters == null ? List.of() : List.copyOf(limiters);
        meters = meters == null ? new MeterStateDto(List.of(), null, false, null) : meters;
    }

    public StateDto(boolean connected,
                    DeviceInfoDto deviceInfo,
                    List<ChannelDto> channels,
                    List<String> cachedBlockIndices,
                    List<MatrixOutputDto> matrixOutputs,
                    List<InputGateDto> inputGates,
                    List<InputCompressorDto> inputCompressors,
                    List<LimiterDto> limiters,
                    MeterStateDto meters) {
        this(connected, deviceInfo, channels, cachedBlockIndices, matrixOutputs, List.of(), inputGates,
                inputCompressors, limiters, meters);
    }

    public StateDto(boolean connected,
                    DeviceInfoDto deviceInfo,
                    List<ChannelDto> channels,
                    List<String> cachedBlockIndices,
                    List<InputGateDto> inputGates,
                    List<InputCompressorDto> inputCompressors,
                    List<LimiterDto> limiters,
                    MeterStateDto meters) {
        this(connected, deviceInfo, channels, cachedBlockIndices, List.of(), List.of(), inputGates,
                inputCompressors, limiters, meters);
    }

    public StateDto(boolean connected,
                    DeviceInfoDto deviceInfo,
                    List<ChannelDto> channels,
                    List<String> cachedBlockIndices,
                    MeterStateDto meters) {
        this(connected, deviceInfo, channels, cachedBlockIndices, List.of(), List.of(), List.of(), List.of(), List.of(), meters);
    }

    public StateDto(boolean connected,
                    DeviceInfoDto deviceInfo,
                    List<ChannelDto> channels,
                    List<String> cachedBlockIndices) {
        this(connected, deviceInfo, channels, cachedBlockIndices, List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }
}

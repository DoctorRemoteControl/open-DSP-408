package de.drremote.dsp408controller.runtime;

import java.util.List;

import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.DeviceInfoDto;
import de.drremote.dsp408.api.InputCompressorDto;
import de.drremote.dsp408.api.InputGateDto;
import de.drremote.dsp408.api.LimiterDto;
import de.drremote.dsp408.api.MatrixOutputDto;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.PeqChannelDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408.api.TrafficStatusDto;
import de.drremote.dsp408controller.core.net.DspTrafficSnapshot;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

/** Owns read-only state projections and channel-kind validation. */
final class DspStateQueries {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;

    DspStateQueries(DspController controller, DspRuntimeDtoMapper dtoMapper) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
    }

    DeviceInfoDto deviceInfo() {
        return dtoMapper.deviceInfo();
    }

    List<ChannelDto> channels() {
        return controller.state().allChannels().stream()
                .map(dtoMapper::channel)
                .toList();
    }

    ChannelDto channel(String channelId) {
        return dtoMapper.channel(controller.state().channel(DspChannel.parse(channelId)));
    }

    List<MatrixOutputDto> matrixOutputs() {
        return dtoMapper.matrixOutputs();
    }

    MatrixOutputDto matrixOutput(String outputId) {
        DspChannel output = requireOutput(outputId);
        return dtoMapper.matrixOutput(controller.state().matrixOutput(output));
    }

    List<PeqChannelDto> peqChannels() {
        return dtoMapper.peqChannels();
    }

    PeqChannelDto peqChannel(String channelId) {
        return dtoMapper.peqChannel(DspChannel.parse(channelId));
    }

    List<InputGateDto> inputGates() {
        return dtoMapper.inputGates();
    }

    List<InputCompressorDto> inputCompressors() {
        return dtoMapper.inputCompressors();
    }

    InputCompressorDto inputCompressor(String channelId) {
        DspChannel input = requireInput(channelId);
        return dtoMapper.inputCompressor(controller.state().compressor(input));
    }

    List<LimiterDto> limiters() {
        return dtoMapper.limiters();
    }

    LimiterDto limiter(String channelId) {
        DspChannel output = requireOutput(channelId);
        return dtoMapper.limiter(controller.state().limiter(output));
    }

    MeterStateDto meters() {
        return dtoMapper.meters();
    }

    MeterDto meter(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        return dtoMapper.meter(controller.state().meterState().channel(channel));
    }

    StateDto state() {
        return dtoMapper.state();
    }

    TrafficStatusDto trafficStatus() {
        DspTrafficSnapshot traffic = controller.trafficSnapshot();
        return new TrafficStatusDto(
                controller.isConnected(),
                traffic.pendingCommands(),
                traffic.maxPendingCommands(),
                traffic.submittedCommands(),
                traffic.transmittedCommands(),
                traffic.supersededCommands(),
                traffic.meterTransmissions(),
                traffic.coalescedMeterRequests(),
                traffic.controlTransmissions(),
                traffic.writeRetries(),
                traffic.unacknowledgedWrites(),
                traffic.activePriority(),
                traffic.lastTransmitEpochMs() == 0L ? null : traffic.lastTransmitEpochMs(),
                traffic.centralMeterPolling(),
                traffic.meterPollIntervalMs(),
                controller.state().meterState().lastRuntimePayloadObservedAtEpochMs()
        );
    }

    private static DspChannel requireInput(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        if (channel.index() > DspChannel.IN_D.index()) {
            throw new IllegalArgumentException("Input channel required: InA..InD");
        }
        return channel;
    }

    private static DspChannel requireOutput(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        if (channel.index() < DspChannel.OUT_1.index()) {
            throw new IllegalArgumentException("Output channel required: Out1..Out8");
        }
        return channel;
    }
}

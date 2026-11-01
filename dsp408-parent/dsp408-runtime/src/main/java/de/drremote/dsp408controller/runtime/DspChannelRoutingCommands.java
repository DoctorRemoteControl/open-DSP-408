package de.drremote.dsp408controller.runtime;

import java.util.List;

import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.service.DspController;

/** Owns channel metadata, delay and matrix-routing write operations. */
final class DspChannelRoutingCommands {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;

    DspChannelRoutingCommands(DspController controller, DspRuntimeDtoMapper dtoMapper) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
    }

    RawTxDto setDelayUnit(String unit) throws Exception {
        controller.setDelayUnit(unit);
        return dtoMapper.rawTx(DspProtocol.buildDelayUnit(unit));
    }

    RawTxDto setChannelName(String channelId, String name) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setChannelName(channel, name);
        return dtoMapper.rawTx(DspProtocol.buildChannelName(channel, name));
    }

    ChannelDto setPhase(String channelId, boolean inverted) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setPhase(channel, inverted);
        return dtoMapper.channel(controller.state().channel(channel));
    }

    ChannelDto setDelay(String channelId, double ms) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setDelay(channel, ms);
        return dtoMapper.channel(controller.state().channel(channel));
    }

    RawTxDto setMatrixRoute(String outputId, String inputId) throws Exception {
        return setMatrixRoutes(outputId, List.of(inputId));
    }

    RawTxDto setMatrixRoutes(String outputId, List<String> inputIds) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        if (inputIds == null) {
            throw new IllegalArgumentException("Matrix inputs are required");
        }
        List<DspChannel> inputs = inputIds.stream().map(DspChannel::parse).toList();
        controller.setMatrixRoutes(output, inputs);
        return dtoMapper.rawTx(DspProtocol.buildMatrixRoutes(output, inputs));
    }

    RawTxDto setMatrixCrosspointGain(String outputId, String inputId, double db) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        DspChannel input = DspChannel.parse(inputId);
        controller.setMatrixCrosspointGain(output, input, db);
        return dtoMapper.rawTx(DspProtocol.buildMatrixCrosspointGain(output, input, db));
    }
}

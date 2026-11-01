package de.drremote.dsp408controller.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.drremote.dsp408.api.BlockDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.DeviceInfoDto;
import de.drremote.dsp408.api.InputCompressorDto;
import de.drremote.dsp408.api.InputGateDto;
import de.drremote.dsp408.api.LimiterDto;
import de.drremote.dsp408.api.MatrixCrosspointDto;
import de.drremote.dsp408.api.MatrixOutputDto;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.PeqBandDto;
import de.drremote.dsp408.api.PeqChannelDto;
import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.InputGateState;
import de.drremote.dsp408controller.core.state.LimiterState;
import de.drremote.dsp408controller.core.state.MatrixOutputState;
import de.drremote.dsp408controller.core.state.MeterChannelState;
import de.drremote.dsp408controller.core.state.MeterLevelDecoder;
import de.drremote.dsp408controller.core.state.MeterState;
import de.drremote.dsp408controller.core.state.PeqBandState;
import de.drremote.dsp408controller.util.Hex;

/**
 * Maps the controller's domain state to the stable public API DTOs.
 *
 * <p>This class deliberately contains no I/O, retries, locking or lifecycle
 * logic. Keeping the projection separate prevents API representation changes
 * from leaking into the DSP transaction coordinator.</p>
 */
final class DspRuntimeDtoMapper {
    private static final List<DspChannel> INPUTS = List.of(
            DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D
    );
    private static final List<DspChannel> OUTPUTS = List.of(
            DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
            DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
    );
    private static final List<DspChannel> CHANNELS = List.of(
            DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D,
            DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
            DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
    );

    private final DspController controller;

    DspRuntimeDtoMapper(DspController controller) {
        this.controller = controller;
    }

    ChannelDto channel(ChannelState state) {
        if (state == null) {
            return null;
        }
        DspChannel channel = state.channel();
        return new ChannelDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                state.gainDb(),
                state.muted(),
                state.phaseInverted(),
                state.delayMs(),
                state.gainConfirmedFromDevice(),
                state.muteConfirmedFromDevice(),
                state.phaseConfirmedFromDevice(),
                state.delayConfirmedFromDevice(),
                state.gainDirty(),
                state.muteDirty(),
                state.phaseDirty(),
                state.delayDirty(),
                state.lastGainUpdateSource(),
                state.lastMuteUpdateSource(),
                state.lastPhaseUpdateSource(),
                state.lastDelayUpdateSource()
        );
    }

    List<ChannelDto> channels() {
        return CHANNELS.stream()
                .map(channel -> channel(controller.state().channel(channel)))
                .toList();
    }

    DeviceInfoDto deviceInfo() {
        String version = controller.deviceVersion();
        byte[] systemInfo = controller.lastSystemInfoPayload();
        if (version == null && systemInfo == null) {
            return new DeviceInfoDto(null, null, null, null);
        }
        return new DeviceInfoDto(
                version,
                systemInfo == null ? null : Hex.format(systemInfo),
                systemInfo == null ? null : Hex.ascii(systemInfo),
                systemInfo == null ? null : systemInfo.length
        );
    }

    StateDto state() {
        return new StateDto(
                controller.isConnected(),
                deviceInfo(),
                channels(),
                cachedBlockIndices(),
                matrixOutputs(),
                peqChannels(),
                inputGates(),
                inputCompressors(),
                limiters(),
                meters()
        );
    }

    List<String> cachedBlockIndices() {
        return controller.state().cachedBlockIndices().stream()
                .map(DspRuntimeDtoMapper::formatIndexHex)
                .toList();
    }

    List<MatrixOutputDto> matrixOutputs() {
        return OUTPUTS.stream()
                .map(channel -> matrixOutput(controller.state().matrixOutput(channel)))
                .toList();
    }

    MatrixOutputDto matrixOutput(MatrixOutputState matrix) {
        DspChannel output = matrix.output();
        List<String> routedInputs = matrix.routedInputsSnapshot().stream()
                .sorted(Comparator.comparingInt(DspChannel::index))
                .map(DspRuntimeDtoMapper::channelId)
                .toList();
        List<MatrixCrosspointDto> crosspoints = INPUTS.stream()
                .map(input -> new MatrixCrosspointDto(
                        channelId(input),
                        input.displayName(),
                        input.index(),
                        matrix.crosspointGain(input),
                        matrix.crosspointGainConfirmedFromDevice(input),
                        matrix.crosspointGainUpdateSource(input)
                ))
                .toList();
        return new MatrixOutputDto(
                channelId(output),
                output.displayName(),
                output.index(),
                routedInputs,
                crosspoints
        );
    }

    List<PeqChannelDto> peqChannels() {
        return CHANNELS.stream().map(this::peqChannel).toList();
    }

    PeqChannelDto peqChannel(DspChannel channel) {
        boolean input = channel.index() <= DspChannel.IN_D.index();
        int bandCount = input ? 8 : 9;
        List<PeqBandDto> bands = new ArrayList<>(bandCount);
        for (int bandIndex = 1; bandIndex <= bandCount; bandIndex++) {
            PeqBandState band = input
                    ? controller.state().inputPeq(channel).band(bandIndex)
                    : controller.state().outputPeq(channel).band(bandIndex);
            bands.add(peqBand(bandIndex, band));
        }
        return new PeqChannelDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                input,
                bandCount,
                bands
        );
    }

    private static PeqBandDto peqBand(int bandIndex, PeqBandState band) {
        if (band == null) {
            return new PeqBandDto(
                    bandIndex, null, null, null, null, null,
                    false, false, false, false, false,
                    null, null, null, null, null
            );
        }
        return new PeqBandDto(
                bandIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType() == null ? null : band.filterType().displayName(),
                band.bypass(),
                band.gainConfirmedFromDevice(),
                band.frequencyConfirmedFromDevice(),
                band.qConfirmedFromDevice(),
                band.typeConfirmedFromDevice(),
                band.bypassConfirmedFromDevice(),
                band.lastGainUpdateSource(),
                band.lastFrequencyUpdateSource(),
                band.lastQUpdateSource(),
                band.lastTypeUpdateSource(),
                band.lastBypassUpdateSource()
        );
    }

    List<InputGateDto> inputGates() {
        return INPUTS.stream()
                .map(channel -> inputGate(controller.state().inputGate(channel)))
                .toList();
    }

    InputGateDto inputGate(InputGateState gate) {
        DspChannel channel = gate.input();
        return new InputGateDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                gate.thresholdDb(),
                gate.holdMs(),
                gate.attackMs(),
                gate.releaseMs(),
                gate.thresholdConfirmedFromDevice(),
                gate.holdConfirmedFromDevice(),
                gate.attackConfirmedFromDevice(),
                gate.releaseConfirmedFromDevice(),
                gate.lastThresholdUpdateSource(),
                gate.lastHoldUpdateSource(),
                gate.lastAttackUpdateSource(),
                gate.lastReleaseUpdateSource()
        );
    }

    List<InputCompressorDto> inputCompressors() {
        return INPUTS.stream()
                .map(channel -> inputCompressor(controller.state().compressor(channel)))
                .toList();
    }

    InputCompressorDto inputCompressor(CompressorState compressor) {
        DspChannel channel = compressor.channel();
        return new InputCompressorDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                compressor.ratioIndex(),
                compressor.ratioLabel(),
                compressor.attackMs(),
                compressor.releaseMs(),
                compressor.kneeDb(),
                compressor.thresholdDb(),
                compressor.frequencyHz(),
                compressor.typeContextRaw(),
                compressor.typeRaw(),
                compressor.typeLabel(),
                compressor.ratioConfirmedFromDevice(),
                compressor.attackConfirmedFromDevice(),
                compressor.releaseConfirmedFromDevice(),
                compressor.kneeConfirmedFromDevice(),
                compressor.thresholdConfirmedFromDevice(),
                compressor.frequencyConfirmedFromDevice(),
                compressor.typeContextConfirmedFromDevice(),
                compressor.typeConfirmedFromDevice(),
                compressor.lastRatioUpdateSource(),
                compressor.lastAttackUpdateSource(),
                compressor.lastReleaseUpdateSource(),
                compressor.lastKneeUpdateSource(),
                compressor.lastThresholdUpdateSource(),
                compressor.lastFrequencyUpdateSource(),
                compressor.lastTypeContextUpdateSource(),
                compressor.lastTypeUpdateSource()
        );
    }

    List<LimiterDto> limiters() {
        return OUTPUTS.stream()
                .map(channel -> limiter(controller.state().limiter(channel)))
                .toList();
    }

    LimiterDto limiter(LimiterState limiter) {
        DspChannel channel = limiter.output();
        return new LimiterDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                limiter.attackMs(),
                limiter.releaseMs(),
                limiter.thresholdDb(),
                limiter.kneeDb(),
                limiter.runtimeActive(),
                limiter.attackConfirmedFromDevice(),
                limiter.releaseConfirmedFromDevice(),
                limiter.thresholdConfirmedFromDevice(),
                limiter.kneeConfirmedFromDevice(),
                limiter.runtimeConfirmedFromDevice(),
                limiter.lastAttackUpdateSource(),
                limiter.lastReleaseUpdateSource(),
                limiter.lastThresholdUpdateSource(),
                limiter.lastKneeUpdateSource(),
                limiter.lastRuntimeUpdateSource()
        );
    }

    MeterStateDto meters() {
        MeterState meterState = controller.state().meterState();
        byte[] rawPayload = meterState.lastRuntimePayload();
        List<Integer> tailBytes = new ArrayList<>();
        if (rawPayload != null) {
            for (int offset = 40; offset < rawPayload.length; offset++) {
                tailBytes.add(rawPayload[offset] & 0xFF);
            }
        }
        return new MeterStateDto(
                meterState.allChannels().stream().map(this::meter).toList(),
                meterState.statusByteRaw(),
                meterState.statusConfirmedFromDevice(),
                meterState.lastStatusUpdateSource(),
                rawPayload == null ? null : Hex.format(rawPayload),
                tailBytes,
                meterState.lastRuntimePayloadObservedAtEpochMs(),
                meterState.runtimePayloadConfirmedFromDevice(),
                meterState.lastRuntimePayloadUpdateSource()
        );
    }

    MeterDto meter(MeterChannelState meter) {
        if (meter == null) {
            return null;
        }
        DspChannel channel = meter.channel();
        LimiterState limiter = channel.index() >= DspChannel.OUT_1.index()
                ? controller.state().limiter(channel)
                : null;
        Integer rawValue = meter.rawValue();
        return new MeterDto(
                channelId(channel),
                channel.displayName(),
                channel.index(),
                meter.rawLowByte(),
                meter.rawHighByte(),
                rawValue,
                rawValue == null ? null : MeterLevelDecoder.decodeDb(rawValue, meter.slotByte2()),
                meter.slotByte2(),
                limiter == null ? null : limiter.runtimeActive(),
                meter.clipActive(),
                meter.confirmedFromDevice(),
                limiter != null && limiter.runtimeConfirmedFromDevice(),
                meter.clipDerivedFromDeviceMeter(),
                controller.meterClipThresholdConfirmed(channel),
                controller.meterChannelMappingConfirmed(),
                controller.meterLevelCalibrationConfirmed(),
                limiter != null && controller.meterLimiterMappingConfirmed(),
                meter.lastUpdateSource(),
                limiter == null ? null : limiter.lastRuntimeUpdateSource(),
                meter.lastClipUpdateSource()
        );
    }

    RawTxDto rawTx(byte[] payload) {
        byte[] frame = DspFrameCodec.encode(payload);
        return new RawTxDto(Hex.format(payload), Hex.format(frame), payload.length, frame.length);
    }

    BlockDto block(int index, byte[] data) {
        return new BlockDto(
                formatIndexHex(index),
                index & 0xFF,
                data != null,
                data == null ? null : Hex.format(data),
                data == null ? null : Hex.ascii(data),
                data == null ? null : data.length
        );
    }

    static String formatIndexHex(int index) {
        return Hex.byteToHex(index);
    }

    static String channelId(DspChannel channel) {
        return switch (channel) {
            case IN_A -> "ina";
            case IN_B -> "inb";
            case IN_C -> "inc";
            case IN_D -> "ind";
            case OUT_1 -> "out1";
            case OUT_2 -> "out2";
            case OUT_3 -> "out3";
            case OUT_4 -> "out4";
            case OUT_5 -> "out5";
            case OUT_6 -> "out6";
            case OUT_7 -> "out7";
            case OUT_8 -> "out8";
        };
    }
}

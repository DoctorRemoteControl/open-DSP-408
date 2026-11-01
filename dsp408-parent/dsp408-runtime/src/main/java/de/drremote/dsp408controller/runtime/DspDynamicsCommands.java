package de.drremote.dsp408controller.runtime;

import java.util.EnumMap;
import java.util.Map;

import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.InputGateState;
import de.drremote.dsp408controller.core.state.LimiterState;

/** Owns input gates, compressors and output limiter writes. */
final class DspDynamicsCommands {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspTransactionCoordinator transactions;
    private final Map<DspChannel, Integer> confirmedLimiterKnees =
            new EnumMap<>(DspChannel.class);

    DspDynamicsCommands(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspTransactionCoordinator transactions
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.transactions = transactions;
    }


    RawTxDto setInputGate(String channelId, double thresholdDb, double holdMs, double attackMs,
                                              double releaseMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputGate(channel, thresholdDb, holdMs, attackMs, releaseMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), thresholdDb, holdMs, attackMs, releaseMs));
    }

    RawTxDto setInputGateThreshold(String channelId, double thresholdDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateThreshold(channel, thresholdDb);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), thresholdDb, gate.holdMs(), gate.attackMs(), gate.releaseMs()));
    }

    RawTxDto setInputGateHold(String channelId, double holdMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateHold(channel, holdMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), holdMs, gate.attackMs(), gate.releaseMs()));
    }

    RawTxDto setInputGateAttack(String channelId, double attackMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateAttack(channel, attackMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), gate.holdMs(), attackMs, gate.releaseMs()));
    }

    RawTxDto setInputGateRelease(String channelId, double releaseMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateRelease(channel, releaseMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), gate.holdMs(), gate.attackMs(), releaseMs));
    }

    RawTxDto setCompressor(String inputId, double thresholdDb, String ratioLabel, double attackMs,
                                               double releaseMs, double kneeDb) throws Exception {
        DspChannel input = DspChannel.parse(inputId);
        controller.setCompressor(input, thresholdDb, ratioLabel, attackMs, releaseMs, kneeDb);
        CompressorState state = controller.state().compressor(input);
        return buildRawTxDto(DspProtocol.buildInputCompressor(
                input,
                state.ratioIndex(),
                DspCodecRegistry.dynamicsTimeMsToRaw(state.attackMs(), 1.0, 999.0),
                DspCodecRegistry.dynamicsTimeMsToRaw(state.releaseMs(), 10.0, 3000.0),
                (int) Math.round(state.kneeDb()),
                DspCodecRegistry.dynamicsThresholdDbToRaw(state.thresholdDb(), -90.0, 20.0),
                DspCodecRegistry.peqFrequency().doubleToRaw(state.frequencyHz()),
                state.typeContextRaw(),
                state.typeRaw()
        ));
    }

    RawTxDto setLimiter(String outputId, double thresholdDb, double attackMs, double releaseMs)
            throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        rememberConfirmedLimiterKnees();
        int kneeRaw = transactions.withMetersPaused(() -> writeLimiterAndUpdateCache(
                output,
                thresholdDb,
                attackMs,
                releaseMs,
                "api limiter tx repeated"
        ));
        return buildRawTxDto(DspProtocol.buildLimiter(output, attackMs, releaseMs, kneeRaw, thresholdDb));
    }


    int writeLimiterAndUpdateCache(
            DspChannel output,
            double thresholdDb,
            double attackMs,
            double releaseMs,
            String source
    ) throws Exception {
        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0);
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0);
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(
                thresholdDb, -90.0, 20.0
        );
        int kneeRaw = resolveCachedLimiterKnee(output);
        controller.setLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
        controller.state().markLimiter(
                output,
                DspCodecRegistry.rawToDynamicsTimeMs(attackRaw),
                DspCodecRegistry.rawToDynamicsTimeMs(releaseRaw),
                kneeRaw,
                DspCodecRegistry.rawToDynamicsThresholdDb(thresholdRaw),
                source
        );
        Fir408WriteVerifier.LimiterLocation location =
                Fir408WriteVerifier.limiterLocation(output);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.attackOffset(), attackRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.releaseOffset(), releaseRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.kneeOffset(), kneeRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.thresholdOffset(), thresholdRaw);
        confirmedLimiterKnees.put(output, kneeRaw);
        return kneeRaw;
    }

    void rememberConfirmedLimiterKnees() {
        for (DspChannel channel : DspChannel.values()) {
            if (channel.index() < DspChannel.OUT_1.index()) {
                continue;
            }
            LimiterState state = controller.state().limiter(channel);
            if (state == null || state.kneeDb() == null
                    || !state.kneeConfirmedFromDevice()
                    || state.kneeDb() < 0.0 || state.kneeDb() > 12.0) {
                continue;
            }
            confirmedLimiterKnees.put(channel, (int) Math.round(state.kneeDb()));
        }
    }

    private int resolveCachedLimiterKnee(DspChannel output) {
        LimiterState state = controller.state().limiter(output);
        if (state != null && state.kneeDb() != null
                && state.kneeDb() >= 0.0 && state.kneeDb() <= 12.0) {
            int kneeRaw = (int) Math.round(state.kneeDb());
            confirmedLimiterKnees.put(output, kneeRaw);
            return kneeRaw;
        }

        Integer rememberedKneeRaw = confirmedLimiterKnees.get(output);
        if (rememberedKneeRaw != null) {
            return rememberedKneeRaw;
        }
        throw new IllegalStateException(
                "Limiter-Knee für " + output.displayName()
                        + " fehlt im Server-Cache. Zuerst /refresh ausführen."
        );
    }

    private RawTxDto buildRawTxDto(byte[] payload) {
        return dtoMapper.rawTx(payload);
    }

    private static int inputNumber(DspChannel channel) {
        return channel.index();
    }
}


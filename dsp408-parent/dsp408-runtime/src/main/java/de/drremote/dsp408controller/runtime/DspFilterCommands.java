package de.drremote.dsp408controller.runtime;

import java.util.ArrayList;
import java.util.List;

import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.CrossoverFilterState;
import de.drremote.dsp408controller.core.state.PeqBandState;

/** Owns crossover, FIR, PEQ and GEQ write operations. */
final class DspFilterCommands {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;

    DspFilterCommands(DspController controller, DspRuntimeDtoMapper dtoMapper) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
    }


    RawTxDto setCrossoverHighPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverSlope parsedSlope = bypass && (slope == null || slope.isBlank()) ? CrossoverSlope.LR_48 : CrossoverSlope.parse(slope);
        controller.setCrossoverHighPass(channel, frequencyHz, parsedSlope, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, parsedSlope, bypass));
    }

    RawTxDto setCrossoverHighPassFrequency(String channelId, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        controller.setCrossoverHighPassFrequency(channel, frequencyHz);
        CrossoverSlope slope = resolvedSlope(existing);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, slope, Boolean.TRUE.equals(existing.bypass())));
    }

    RawTxDto setCrossoverHighPassSlope(String channelId, String slope) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        CrossoverSlope parsedSlope = CrossoverSlope.parse(slope);
        controller.setCrossoverHighPassSlope(channel, parsedSlope);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, existing.frequencyHz(), parsedSlope, Boolean.TRUE.equals(existing.bypass())));
    }

    RawTxDto setCrossoverHighPassBypass(String channelId, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        controller.setCrossoverHighPassBypass(channel, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, existing.frequencyHz(), resolvedSlope(existing), bypass));
    }

    RawTxDto setCrossoverLowPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverSlope parsedSlope = bypass && (slope == null || slope.isBlank()) ? CrossoverSlope.LR_48 : CrossoverSlope.parse(slope);
        controller.setCrossoverLowPass(channel, frequencyHz, parsedSlope, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, parsedSlope, bypass));
    }

    RawTxDto setCrossoverLowPassFrequency(String channelId, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        controller.setCrossoverLowPassFrequency(channel, frequencyHz);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, resolvedSlope(existing), Boolean.TRUE.equals(existing.bypass())));
    }

    RawTxDto setCrossoverLowPassSlope(String channelId, String slope) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        CrossoverSlope parsedSlope = CrossoverSlope.parse(slope);
        controller.setCrossoverLowPassSlope(channel, parsedSlope);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, existing.frequencyHz(), parsedSlope, Boolean.TRUE.equals(existing.bypass())));
    }

    RawTxDto setCrossoverLowPassBypass(String channelId, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        controller.setCrossoverLowPassBypass(channel, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, existing.frequencyHz(), resolvedSlope(existing), bypass));
    }

    RawTxDto setFirProcessingMode(String outputId, String mode) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        FirProcessingMode parsedMode = FirProcessingMode.parse(mode);
        controller.setFirProcessingMode(output, parsedMode);
        return buildRawTxDto(DspProtocol.buildFirProcessingMode(output, parsedMode));
    }

    RawTxDto setFirGenerator(String outputId, String type, String window,
                                                 double highPassFrequencyHz, double lowPassFrequencyHz, int taps)
            throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        FirFilterType parsedType = FirFilterType.parse(type);
        FirWindowFunction parsedWindow = FirWindowFunction.parse(window);
        controller.setFirGenerator(output, parsedType, parsedWindow, highPassFrequencyHz, lowPassFrequencyHz, taps);
        return buildRawTxDto(DspProtocol.buildFirGenerator(
                output,
                parsedType,
                parsedWindow,
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        ));
    }

    List<RawTxDto> uploadExternalFir(String channelId, String name, List<Double> coefficients)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        double[] coefficientArray = toDoubleArray(coefficients);
        return controller.uploadExternalFir(channel, name, coefficientArray).stream()
                .map(this::buildRawTxDto)
                .toList();
    }

    RawTxDto setOutputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                              double q, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        PeqFilterType type = PeqFilterType.parse(filterType);
        controller.setOutputPeq(channel, peqIndex, type, frequencyHz, q, gainDb);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqFrequency(String channelId, int peqIndex, double hz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqFrequency(channel, peqIndex, hz);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqQ(String channelId, int peqIndex, double q) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqQ(channel, peqIndex, q);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqQRaw(String channelId, int peqIndex, int raw) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqQRaw(channel, peqIndex, raw);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqGain(channel, peqIndex, gainDb);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqGainCode(String channelId, int peqIndex, int code) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqGainCode(channel, peqIndex, code);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqType(String channelId, int peqIndex, String filterType) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqType(channel, peqIndex, PeqFilterType.parse(filterType));
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setOutputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqBypass(channel, peqIndex, bypass);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setInputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                             double q, double gainDb, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        PeqFilterType type = PeqFilterType.parse(filterType);
        controller.setInputPeq(channel, peqIndex, type, frequencyHz, q, gainDb, bypass);
        return buildRawTxDto(DspProtocol.buildInputPeq(inputNumber(channel), peqIndex, gainDb, frequencyHz, q, type, bypass));
    }

    RawTxDto setInputPeqFrequency(String channelId, int peqIndex, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqFrequency(channel, peqIndex, frequencyHz);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setInputPeqQ(String channelId, int peqIndex, double q) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqQ(channel, peqIndex, q);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setInputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqGain(channel, peqIndex, gainDb);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setInputPeqType(String channelId, int peqIndex, String filterType) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqType(channel, peqIndex, PeqFilterType.parse(filterType));
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    RawTxDto setInputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqBypass(channel, peqIndex, bypass);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    List<RawTxDto> setAllPeqBypass(boolean bypass) throws Exception {
        controller.setAllPeqBypass(bypass);

        List<RawTxDto> payloads = new ArrayList<>(104);
        for (DspChannel input : List.of(
                DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D)) {
            for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
                payloads.add(buildInputPeqRawTxDto(input, peqIndex));
            }
        }
        for (DspChannel output : List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8)) {
            for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
                payloads.add(buildOutputPeqRawTxDto(output, peqIndex));
            }
        }
        return List.copyOf(payloads);
    }

    RawTxDto setInputGeq(String channelId, int bandIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputGeq(channel, bandIndex, gainDb);
        return buildRawTxDto(DspProtocol.buildInputGeq(inputNumber(channel), bandIndex, gainDb));
    }


    private RawTxDto buildRawTxDto(byte[] payload) {
        return dtoMapper.rawTx(payload);
    }

    private static int outputNumber(DspChannel channel) {
        return channel.index() - 3;
    }

    private static int inputNumber(DspChannel channel) {
        return channel.index();
    }

    private static double[] toDoubleArray(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("FIR coefficients are required");
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null) {
                throw new IllegalArgumentException(
                        "FIR coefficients must not contain null values"
                );
            }
            out[i] = value;
        }
        return out;
    }

    private static CrossoverSlope resolvedSlope(CrossoverFilterState state) {
        return state.slope() == null ? CrossoverSlope.LR_48 : state.slope();
    }

    private RawTxDto buildOutputPeqRawTxDto(DspChannel channel, int peqIndex) {
        PeqBandState band = controller.state().outputPeq(channel).band(peqIndex);
        if (band == null
                || band.gainDb() == null
                || band.frequencyHz() == null
                || band.q() == null
                || band.filterType() == null
                || band.bypass() == null) {
            throw new IllegalStateException(
                    "Output PEQ state is incomplete for "
                            + channel.displayName() + " PEQ" + peqIndex
            );
        }
        return buildRawTxDto(DspProtocol.buildOutputPeq(
                outputNumber(channel),
                peqIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType(),
                band.bypass()
        ));
    }

    private RawTxDto buildInputPeqRawTxDto(DspChannel channel, int peqIndex) {
        PeqBandState band = controller.state().inputPeq(channel).band(peqIndex);
        if (band == null
                || band.gainDb() == null
                || band.frequencyHz() == null
                || band.q() == null
                || band.filterType() == null
                || band.bypass() == null) {
            throw new IllegalStateException(
                    "Input PEQ state is incomplete for "
                            + channel.displayName() + " PEQ" + peqIndex
            );
        }
        return buildRawTxDto(DspProtocol.buildInputPeq(
                inputNumber(channel),
                peqIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType(),
                band.bypass()
        ));
    }
}


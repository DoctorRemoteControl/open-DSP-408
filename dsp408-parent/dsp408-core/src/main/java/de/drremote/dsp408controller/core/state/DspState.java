package de.drremote.dsp408controller.core.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

public final class DspState {
    private final Map<DspChannel, ChannelState> channels = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, InputPeqState> inputPeqs = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, InputGateState> inputGates = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, InputGeqState> inputGeqs = new EnumMap<>(DspChannel.class);

    private final Map<DspChannel, MatrixOutputState> matrixOutputs = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, OutputPeqState> outputPeqs = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, CrossoverState> crossovers = new EnumMap<>(DspChannel.class);

    private final Map<DspChannel, CompressorState> compressors = new EnumMap<>(DspChannel.class);
    private final Map<DspChannel, LimiterState> limiters = new EnumMap<>(DspChannel.class);
    private final MeterState meterState = new MeterState();
    private final TestToneState testToneState = new TestToneState();

    private final Map<Integer, byte[]> lastBlocks = new ConcurrentHashMap<>();

    public DspState() {
        reset();
    }

    public synchronized void reset() {
        channels.clear();
        for (DspChannel channel : DspChannel.values()) {
            channels.put(channel, new ChannelState(channel));
        }

        inputPeqs.clear();
        inputGates.clear();
        inputGeqs.clear();
        matrixOutputs.clear();
        outputPeqs.clear();
        crossovers.clear();
        compressors.clear();
        limiters.clear();

        for (DspChannel channel : DspChannel.values()) {
            crossovers.put(channel, new CrossoverState(channel));
            if (channel.index() < 4) {
                inputPeqs.put(channel, new InputPeqState(channel));
                inputGates.put(channel, new InputGateState(channel));
                inputGeqs.put(channel, new InputGeqState(channel));
                compressors.put(channel, new CompressorState(channel));
            } else {
                matrixOutputs.put(channel, new MatrixOutputState(channel));
                outputPeqs.put(channel, new OutputPeqState(channel));
                limiters.put(channel, new LimiterState(channel));
            }
        }

        meterState.reset();
        testToneState.reset();
        lastBlocks.clear();
    }

    public synchronized ChannelState channel(DspChannel channel) {
        return channels.get(channel);
    }

    public synchronized Collection<ChannelState> allChannels() {
        return new ArrayList<>(channels.values());
    }

    public synchronized MatrixOutputState matrixOutput(DspChannel output) {
        return matrixOutputs.get(output);
    }

    public synchronized InputPeqState inputPeq(DspChannel input) {
        return inputPeqs.get(input);
    }

    public synchronized InputGateState inputGate(DspChannel input) {
        return inputGates.get(input);
    }

    public synchronized Collection<MatrixOutputState> allMatrixOutputs() {
        return new ArrayList<>(matrixOutputs.values());
    }

    public synchronized InputGeqState inputGeq(DspChannel input) {
        return inputGeqs.get(input);
    }

    public synchronized OutputPeqState outputPeq(DspChannel output) {
        return outputPeqs.get(output);
    }

    public synchronized CrossoverState crossover(DspChannel channel) {
        return crossovers.get(channel);
    }

    public synchronized CompressorState compressor(DspChannel channel) {
        return compressors.get(channel);
    }

    public synchronized LimiterState limiter(DspChannel output) {
        return limiters.get(output);
    }

    public synchronized MeterState meterState() {
        return meterState;
    }

    public synchronized TestToneState testToneState() {
        return testToneState;
    }

    public synchronized boolean hasAnyValues() {
        for (ChannelState state : channels.values()) {
            if (state.gainDb() != null
                    || state.muted() != null
                    || state.phaseInverted() != null
                    || state.delayMs() != null) {
                return true;
            }
        }

        for (MatrixOutputState state : matrixOutputs.values()) {
            if (!state.routedInputsSnapshot().isEmpty()) {
                return true;
            }
            for (Double value : state.crosspointGainsSnapshot().values()) {
                if (value != null) {
                    return true;
                }
            }
        }

        for (OutputPeqState state : outputPeqs.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (InputPeqState state : inputPeqs.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (InputGateState state : inputGates.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (InputGeqState state : inputGeqs.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (CrossoverState state : crossovers.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (CompressorState state : compressors.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }
        for (LimiterState state : limiters.values()) {
            if (state.hasAnyValues()) {
                return true;
            }
        }

        return meterState.hasAnyValues() || testToneState.hasAnyValues();
    }

    public synchronized void markGain(DspChannel channel, double db, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateGain(db, source, false);
        }
    }

    public synchronized void markGainFromDevice(DspChannel channel, double db, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateGain(db, source, true);
        }
    }

    public synchronized void markMuted(DspChannel channel, boolean muted, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateMuted(muted, source, false);
        }
    }

    public synchronized void markMutedFromDevice(DspChannel channel, boolean muted, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateMuted(muted, source, true);
        }
    }

    public synchronized void markPhase(DspChannel channel, boolean inverted, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updatePhase(inverted, source, false);
        }
    }

    public synchronized void markPhaseFromDevice(DspChannel channel, boolean inverted, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updatePhase(inverted, source, true);
        }
    }

    public synchronized void markDelay(DspChannel channel, double delayMs, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateDelay(delayMs, source, false);
        }
    }

    public synchronized void markDelayFromDevice(DspChannel channel, double delayMs, String source) {
        ChannelState state = channels.get(channel);
        if (state != null) {
            state.updateDelay(delayMs, source, true);
        }
    }

    public synchronized void markMatrixRoute(DspChannel output, DspChannel input) {
        markMatrixRoutes(output, java.util.List.of(input));
    }

    public synchronized void markMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) {
        MatrixOutputState state = matrixOutputs.get(output);
        if (state != null) {
            state.setRoutedInputs(inputs);
        }
    }

    public synchronized void markMatrixCrosspointGain(DspChannel output, DspChannel input, double db) {
        MatrixOutputState state = matrixOutputs.get(output);
        if (state != null) {
            state.setCrosspointGain(input, db);
        }
    }

    public synchronized void markOutputPeqBand(DspChannel output,
                                               int bandIndex,
                                               double gainDb,
                                               double frequencyHz,
                                               double q,
                                               PeqFilterType filterType,
                                               String source) {
        OutputPeqState state = outputPeqs.get(output);
        if (state == null) {
            return;
        }

        PeqBandState band = state.ensureBand(bandIndex);
        band.updateGain(gainDb, source, false);
        band.updateFrequency(frequencyHz, source, false);
        band.updateQ(q, source, false);
        band.updateFilterType(filterType, source, false);
    }

    public synchronized void markOutputPeqFromDevice(DspChannel output,
                                                     int bandIndex,
                                                     double gainDb,
                                                     double frequencyHz,
                                                     double q,
                                                     PeqFilterType filterType,
                                                     boolean bypass,
                                                     String source) {
        OutputPeqState state = outputPeqs.get(output);
        if (state == null) {
            return;
        }

        PeqBandState band = state.ensureBand(bandIndex);
        band.updateGain(gainDb, source, true);
        band.updateFrequency(frequencyHz, source, true);
        band.updateQ(q, source, true);
        band.updateFilterType(filterType, source, true);
        band.updateBypass(bypass, source, true);
    }

    public synchronized void markInputPeqBand(DspChannel input,
                                              int bandIndex,
                                              double gainDb,
                                              double frequencyHz,
                                              double q,
                                              PeqFilterType filterType,
                                              boolean bypass,
                                              String source) {
        InputPeqState state = inputPeqs.get(input);
        if (state == null) {
            return;
        }

        PeqBandState band = state.ensureBand(bandIndex);
        band.updateGain(gainDb, source, false);
        band.updateFrequency(frequencyHz, source, false);
        band.updateQ(q, source, false);
        band.updateFilterType(filterType, source, false);
        band.updateBypass(bypass, source, false);
    }

    public synchronized void markInputPeqFromDevice(DspChannel input,
                                                    int bandIndex,
                                                    double gainDb,
                                                    double frequencyHz,
                                                    double q,
                                                    PeqFilterType filterType,
                                                    boolean bypass,
                                                    String source) {
        InputPeqState state = inputPeqs.get(input);
        if (state == null) {
            return;
        }

        PeqBandState band = state.ensureBand(bandIndex);
        band.updateGain(gainDb, source, true);
        band.updateFrequency(frequencyHz, source, true);
        band.updateQ(q, source, true);
        band.updateFilterType(filterType, source, true);
        band.updateBypass(bypass, source, true);
    }

    public synchronized void markInputGeqBand(DspChannel input,
                                              int bandIndex,
                                              double gainDb,
                                              double frequencyHz,
                                              String source) {
        InputGeqState state = inputGeqs.get(input);
        if (state == null) {
            return;
        }

        InputGeqBandState band = state.ensureBand(bandIndex);
        band.updateFrequency(frequencyHz, source, false);
        band.updateGain(gainDb, source, false);
    }

    public synchronized void markInputGeqBandFromDevice(DspChannel input,
                                                        int bandIndex,
                                                        double gainDb,
                                                        double frequencyHz,
                                                        String source) {
        InputGeqState state = inputGeqs.get(input);
        if (state == null) {
            return;
        }

        InputGeqBandState band = state.ensureBand(bandIndex);
        band.updateFrequency(frequencyHz, source, true);
        band.updateGain(gainDb, source, true);
    }

    public synchronized void markInputGate(DspChannel input,
                                           double thresholdDb,
                                           double holdMs,
                                           double attackMs,
                                           double releaseMs,
                                           String source) {
        InputGateState state = inputGates.get(input);
        if (state == null) {
            return;
        }
        state.updateThreshold(thresholdDb, source, false);
        state.updateHold(holdMs, source, false);
        state.updateAttack(attackMs, source, false);
        state.updateRelease(releaseMs, source, false);
    }

    public synchronized void markInputGateFromDevice(DspChannel input,
                                                     double thresholdDb,
                                                     double holdMs,
                                                     double attackMs,
                                                     double releaseMs,
                                                     String source) {
        InputGateState state = inputGates.get(input);
        if (state == null) {
            return;
        }
        state.updateThreshold(thresholdDb, source, true);
        state.updateHold(holdMs, source, true);
        state.updateAttack(attackMs, source, true);
        state.updateRelease(releaseMs, source, true);
    }

    public synchronized void markCrossoverHighPass(DspChannel channel,
                                                   double frequencyHz,
                                                   CrossoverSlope slope,
                                                   boolean bypass,
                                                   String source) {
        CrossoverState state = crossovers.get(channel);
        if (state == null) {
            return;
        }
        state.highPass().updateFrequency(frequencyHz, source, false);
        state.highPass().updateSlope(slope, source, false);
        state.highPass().updateBypass(bypass, source, false);
    }

    public synchronized void markCrossoverHighPassFromDevice(DspChannel channel,
                                                             double frequencyHz,
                                                             CrossoverSlope slope,
                                                             boolean bypass,
                                                             String source) {
        CrossoverState state = crossovers.get(channel);
        if (state == null) {
            return;
        }
        state.highPass().updateFrequency(frequencyHz, source, true);
        state.highPass().updateSlope(slope, source, true);
        state.highPass().updateBypass(bypass, source, true);
    }

    public synchronized void markCrossoverLowPass(DspChannel channel,
                                                  double frequencyHz,
                                                  CrossoverSlope slope,
                                                  boolean bypass,
                                                  String source) {
        CrossoverState state = crossovers.get(channel);
        if (state == null) {
            return;
        }
        state.lowPass().updateFrequency(frequencyHz, source, false);
        state.lowPass().updateSlope(slope, source, false);
        state.lowPass().updateBypass(bypass, source, false);
    }

    public synchronized void markCrossoverLowPassFromDevice(DspChannel channel,
                                                            double frequencyHz,
                                                            CrossoverSlope slope,
                                                            boolean bypass,
                                                            String source) {
        CrossoverState state = crossovers.get(channel);
        if (state == null) {
            return;
        }
        state.lowPass().updateFrequency(frequencyHz, source, true);
        state.lowPass().updateSlope(slope, source, true);
        state.lowPass().updateBypass(bypass, source, true);
    }

    public synchronized void markCompressorFromDevice(DspChannel output,
                                                      int ratioIndex,
                                                      String ratioLabel,
                                                      double attackMs,
                                                       double releaseMs,
                                                       double kneeDb,
                                                       double thresholdDb,
                                                       double frequencyHz,
                                                       int typeContextRaw,
                                                       int typeRaw,
                                                       String typeLabel,
                                                       String source) {
        CompressorState state = compressors.get(output);
        if (state == null) {
            return;
        }

        state.updateRatio(ratioIndex, ratioLabel, source, true);
        state.updateAttack(attackMs, source, true);
        state.updateRelease(releaseMs, source, true);
        state.updateKnee(kneeDb, source, true);
        state.updateThreshold(thresholdDb, source, true);
        state.updateFrequency(frequencyHz, source, true);
        state.updateTypeContext(typeContextRaw, source, true);
        state.updateType(typeRaw, typeLabel, source, true);
    }

    public synchronized void markCompressor(DspChannel output,
                                            int ratioIndex,
                                            String ratioLabel,
                                            double attackMs,
                                            double releaseMs,
                                            double kneeDb,
                                            double thresholdDb,
                                            double frequencyHz,
                                            int typeContextRaw,
                                            int typeRaw,
                                            String typeLabel,
                                            String source) {
        CompressorState state = compressors.get(output);
        if (state == null) {
            return;
        }

        state.updateRatio(ratioIndex, ratioLabel, source, false);
        state.updateAttack(attackMs, source, false);
        state.updateRelease(releaseMs, source, false);
        state.updateKnee(kneeDb, source, false);
        state.updateThreshold(thresholdDb, source, false);
        state.updateFrequency(frequencyHz, source, false);
        state.updateTypeContext(typeContextRaw, source, false);
        state.updateType(typeRaw, typeLabel, source, false);
    }

    public synchronized void markLimiterFromDevice(DspChannel output,
                                                   double attackMs,
                                                   double releaseMs,
                                                   double kneeDb,
                                                   double thresholdDb,
                                                   String source) {
        LimiterState state = limiters.get(output);
        if (state == null) {
            return;
        }

        state.updateAttack(attackMs, source, true);
        state.updateRelease(releaseMs, source, true);
        state.updateKnee(kneeDb, source, true);
        state.updateThreshold(thresholdDb, source, true);
    }

    public synchronized void markLimiter(DspChannel output,
                                         double attackMs,
                                         double releaseMs,
                                         double kneeDb,
                                         double thresholdDb,
                                         String source) {
        LimiterState state = limiters.get(output);
        if (state == null) {
            return;
        }

        state.updateAttack(attackMs, source, false);
        state.updateRelease(releaseMs, source, false);
        state.updateKnee(kneeDb, source, false);
        state.updateThreshold(thresholdDb, source, false);
    }

    public synchronized void markLimiterRuntimeActive(DspChannel output,
                                                      boolean active,
                                                      String source) {
        LimiterState state = limiters.get(output);
        if (state == null) {
            return;
        }
        state.updateRuntimeActive(active, source, true);
    }

    public synchronized void markMeterFromDevice(DspChannel channel,
                                                 int rawLowByte,
                                                 int rawHighByte,
                                                 int slotByte2,
                                                 String source) {
        MeterChannelState meter = meterState.channel(channel);
        if (meter != null) {
            meter.update(rawLowByte, rawHighByte, slotByte2, source, true);
        }
    }

    public synchronized void markMeterStatusByteFromDevice(int statusByteRaw, String source) {
        meterState.updateStatusByte(statusByteRaw, source, true);
    }

    public synchronized void markTestToneSource(int sourceIndex, String sourceLabel, String source) {
        testToneState.updateSource(sourceIndex, sourceLabel, source, false);
    }

    public synchronized void markTestToneSourceFromDevice(int sourceIndex, String sourceLabel, String source) {
        testToneState.updateSource(sourceIndex, sourceLabel, source, true);
    }

    public synchronized void markTestToneSineFrequency(int sineFrequencyRaw,
                                                       double sineFrequencyHz,
                                                       String source) {
        testToneState.updateSineFrequency(sineFrequencyRaw, sineFrequencyHz, source, false);
    }

    public synchronized void markTestToneSineFrequencyFromDevice(int sineFrequencyRaw,
                                                                 double sineFrequencyHz,
                                                                 String source) {
        testToneState.updateSineFrequency(sineFrequencyRaw, sineFrequencyHz, source, true);
    }

    public synchronized void storeBlock(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }
        lastBlocks.put(blockIndex & 0xFF, Arrays.copyOf(data, data.length));
    }

    public synchronized byte[] getBlock(int blockIndex) {
        byte[] data = lastBlocks.get(blockIndex & 0xFF);
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    public synchronized boolean hasCachedBlocks() {
        return !lastBlocks.isEmpty();
    }

    public synchronized List<Integer> cachedBlockIndices() {
        List<Integer> indices = new ArrayList<>(lastBlocks.keySet());
        indices.sort(Integer::compareTo);
        return indices;
    }
}

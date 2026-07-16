package de.drremote.dsp408controller.core.library;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class DspLibrary {
    private final String libraryType;
    private final int maxParameterBlockIndex;

    private final Map<Integer, List<DspFieldLocation>> gainReadLocationsByBlock;
    private final Map<Integer, List<DspFieldLocation>> phaseReadLocationsByBlock;
    private final Map<Integer, List<DspFieldLocation>> delayReadLocationsByBlock;

    private final Map<DspChannel, MatrixRouteLocation> matrixRouteLocations;
    private final Map<Integer, List<MatrixCrosspointLocation>> matrixCrosspointLocationsByBlock;
    private final Map<Integer, List<CrossoverChannelLocation>> crossoverLocationsByBlock;

    private final Map<Integer, List<OutputPeqBandLocation>> outputPeqLocationsByBlock;
    private final Map<Integer, OutputPeqBandDefaults> outputPeqDefaultsByBand;

    private final Map<Integer, List<InputPeqBandLocation>> inputPeqLocationsByBlock;
    private final Map<Integer, List<InputGateLocation>> inputGateLocationsByBlock;
    private final Map<Integer, List<InputGeqBandLocation>> inputGeqLocationsByBlock;
    private final Map<Integer, Double> inputGeqBandFrequenciesByBand;

    private final Map<DspChannel, List<InputPeqBypassLocation>> inputPeqBypassLocationsByChannel;
    private final Set<Integer> inputPeqBypassBlocks;

    private final Map<Integer, List<CompressorChannelLocation>> compressorLocationsByBlock;
    private final Map<Integer, List<LimiterChannelLocation>> limiterLocationsByBlock;
    private final Map<Integer, String> compressorRatioNamesByRaw;
    private final Map<Integer, String> inputCompressorTypeNamesByRaw;
    private final MeterLayout meterLayout;
    private final TestToneReadSpec testToneReadSpec;
    private final DspWriteCapabilities writeCapabilities;

    private final MuteReadSpec muteReadSpec;

    public DspLibrary(String libraryType,
                      int maxParameterBlockIndex,
                      Map<Integer, List<DspFieldLocation>> gainReadLocationsByBlock,
                      Map<Integer, List<DspFieldLocation>> phaseReadLocationsByBlock,
                      Map<Integer, List<DspFieldLocation>> delayReadLocationsByBlock,
                      Map<DspChannel, MatrixRouteLocation> matrixRouteLocations,
                      Map<Integer, List<MatrixCrosspointLocation>> matrixCrosspointLocationsByBlock,
                      Map<Integer, List<CrossoverChannelLocation>> crossoverLocationsByBlock,
                      Map<Integer, List<OutputPeqBandLocation>> outputPeqLocationsByBlock,
                      Map<Integer, OutputPeqBandDefaults> outputPeqDefaultsByBand,
                      Map<Integer, List<InputPeqBandLocation>> inputPeqLocationsByBlock,
                      Map<Integer, List<InputGateLocation>> inputGateLocationsByBlock,
                      Map<Integer, List<InputGeqBandLocation>> inputGeqLocationsByBlock,
                      Map<Integer, Double> inputGeqBandFrequenciesByBand,
                      Map<DspChannel, List<InputPeqBypassLocation>> inputPeqBypassLocationsByChannel,
                      Map<Integer, List<CompressorChannelLocation>> compressorLocationsByBlock,
                      Map<Integer, List<LimiterChannelLocation>> limiterLocationsByBlock,
                      Map<Integer, String> compressorRatioNamesByRaw,
                      Map<Integer, String> inputCompressorTypeNamesByRaw,
                      MeterLayout meterLayout,
                      TestToneReadSpec testToneReadSpec,
                      DspWriteCapabilities writeCapabilities,
                      MuteReadSpec muteReadSpec) {
        this.libraryType = libraryType == null || libraryType.isBlank() ? "unknown" : libraryType;
        this.maxParameterBlockIndex = maxParameterBlockIndex;

        this.gainReadLocationsByBlock = copyLocationsByBlock(gainReadLocationsByBlock);
        this.phaseReadLocationsByBlock = copyLocationsByBlock(phaseReadLocationsByBlock);
        this.delayReadLocationsByBlock = copyLocationsByBlock(delayReadLocationsByBlock);

        this.matrixRouteLocations = copyMatrixRouteLocations(matrixRouteLocations);
        this.matrixCrosspointLocationsByBlock = copyTypedLocationsByBlock(matrixCrosspointLocationsByBlock);
        this.crossoverLocationsByBlock = copyTypedLocationsByBlock(crossoverLocationsByBlock);

        this.outputPeqLocationsByBlock = copyTypedLocationsByBlock(outputPeqLocationsByBlock);
        this.outputPeqDefaultsByBand = Map.copyOf(new HashMap<>(outputPeqDefaultsByBand));

        this.inputPeqLocationsByBlock = copyTypedLocationsByBlock(inputPeqLocationsByBlock);
        this.inputGateLocationsByBlock = copyTypedLocationsByBlock(inputGateLocationsByBlock);
        this.inputGeqLocationsByBlock = copyTypedLocationsByBlock(inputGeqLocationsByBlock);
        this.inputGeqBandFrequenciesByBand = Map.copyOf(new HashMap<>(inputGeqBandFrequenciesByBand));

        this.inputPeqBypassLocationsByChannel = copyTypedLocationsByChannel(inputPeqBypassLocationsByChannel);
        this.inputPeqBypassBlocks = copyInputPeqBypassBlocks(inputPeqBypassLocationsByChannel);

        this.compressorLocationsByBlock = copyTypedLocationsByBlock(compressorLocationsByBlock);
        this.limiterLocationsByBlock = copyTypedLocationsByBlock(limiterLocationsByBlock);
        this.compressorRatioNamesByRaw = Map.copyOf(new HashMap<>(compressorRatioNamesByRaw));
        this.inputCompressorTypeNamesByRaw = Map.copyOf(new HashMap<>(inputCompressorTypeNamesByRaw));
        this.meterLayout = meterLayout;
        this.testToneReadSpec = testToneReadSpec;
        this.writeCapabilities = writeCapabilities;

        this.muteReadSpec = muteReadSpec;
    }

    public String libraryType() {
        return libraryType;
    }

    public int maxParameterBlockIndex() {
        return maxParameterBlockIndex;
    }

    public boolean hasFirFeatures() {
        return libraryType.toLowerCase(Locale.ROOT).contains("fir");
    }

    private static Map<Integer, List<DspFieldLocation>> copyLocationsByBlock(Map<Integer, List<DspFieldLocation>> in) {
        Map<Integer, List<DspFieldLocation>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<DspFieldLocation>> entry : in.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }

    private static <T> Map<Integer, List<T>> copyTypedLocationsByBlock(Map<Integer, List<T>> in) {
        Map<Integer, List<T>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<T>> entry : in.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }

    private static Map<DspChannel, MatrixRouteLocation> copyMatrixRouteLocations(
            Map<DspChannel, MatrixRouteLocation> in
    ) {
        Map<DspChannel, MatrixRouteLocation> copy = new EnumMap<>(DspChannel.class);
        copy.putAll(in);
        return Map.copyOf(copy);
    }

    private static <T> Map<DspChannel, List<T>> copyTypedLocationsByChannel(Map<DspChannel, List<T>> in) {
        Map<DspChannel, List<T>> copy = new EnumMap<>(DspChannel.class);
        for (Map.Entry<DspChannel, List<T>> entry : in.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }

    private static Set<Integer> copyInputPeqBypassBlocks(Map<DspChannel, List<InputPeqBypassLocation>> in) {
        Set<Integer> blocks = new java.util.TreeSet<>();
        for (List<InputPeqBypassLocation> locations : in.values()) {
            for (InputPeqBypassLocation location : locations) {
                blocks.add(location.blockIndex());
            }
        }
        return Set.copyOf(blocks);
    }

    public Map<Integer, List<DspFieldLocation>> gainReadLocationsByBlock() {
        return gainReadLocationsByBlock;
    }

    public List<DspFieldLocation> gainReadLocationsForBlock(int blockIndex) {
        return gainReadLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<DspFieldLocation> phaseReadLocationsForBlock(int blockIndex) {
        return phaseReadLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<DspFieldLocation> delayReadLocationsForBlock(int blockIndex) {
        return delayReadLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public MatrixRouteLocation matrixRouteLocation(DspChannel output) {
        return matrixRouteLocations.get(output);
    }

    public List<MatrixCrosspointLocation> matrixCrosspointLocationsForBlock(int blockIndex) {
        return matrixCrosspointLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<CrossoverChannelLocation> crossoverLocationsForBlock(int blockIndex) {
        return crossoverLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<OutputPeqBandLocation> outputPeqLocationsForBlock(int blockIndex) {
        return outputPeqLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public OutputPeqBandDefaults outputPeqDefault(int bandIndex) {
        return outputPeqDefaultsByBand.get(bandIndex);
    }

    public List<InputPeqBandLocation> inputPeqLocationsForBlock(int blockIndex) {
        return inputPeqLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<InputGateLocation> inputGateLocationsForBlock(int blockIndex) {
        return inputGateLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public List<InputGeqBandLocation> inputGeqLocationsForBlock(int blockIndex) {
        return inputGeqLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public Double inputGeqBandFrequency(int bandIndex) {
        return inputGeqBandFrequenciesByBand.get(bandIndex);
    }

    public List<InputPeqBypassLocation> inputPeqBypassLocations(DspChannel channel) {
        return inputPeqBypassLocationsByChannel.getOrDefault(channel, List.of());
    }

    public boolean isInputPeqBypassBlock(int blockIndex) {
        return inputPeqBypassBlocks.contains(blockIndex);
    }

    public List<CompressorChannelLocation> compressorLocationsForBlock(int blockIndex) {
        return compressorLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public boolean hasCompressorLocations() {
        return !compressorLocationsByBlock.isEmpty();
    }

    public boolean hasInputCompressorLocations() {
        return hasCompressorLocations();
    }

    public List<LimiterChannelLocation> limiterLocationsForBlock(int blockIndex) {
        return limiterLocationsByBlock.getOrDefault(blockIndex, List.of());
    }

    public String compressorRatioName(int raw) {
        return compressorRatioNamesByRaw.get(raw);
    }

    public Map<Integer, String> compressorRatioNamesByRaw() {
        return compressorRatioNamesByRaw;
    }

    public String inputCompressorTypeName(int raw) {
        return inputCompressorTypeNamesByRaw.get(raw);
    }

    public MeterLayout meterLayout() {
        return meterLayout;
    }

    public TestToneReadSpec testToneReadSpec() {
        return testToneReadSpec;
    }

    public DspWriteCapabilities writeCapabilities() {
        return writeCapabilities;
    }

    public MuteReadSpec muteReadSpec() {
        return muteReadSpec;
    }
}

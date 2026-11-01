package de.drremote.dsp408controller.core.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

public final class DspLibraryLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_RESOURCE = "/dsp/DspLib-408.json";
    private static final String FIR408_RESOURCE = "/dsp/DspLib-408-fir.json";

    private DspLibraryLoader() {
    }

    public static DspLibrary loadDefault() {
        return loadFromResourceOrFallback(
                DEFAULT_RESOURCE,
                List.of(
                        Path.of("DspLib-408.json"),
                        Path.of("docs", "protocol-spec", "DspLib-408.json")
                )
        );
    }

    public static DspLibrary loadFir408() {
        return loadFromResourceOrFallback(
                FIR408_RESOURCE,
                List.of(
                        Path.of("DspLib-408-fir.json"),
                        Path.of("docs", "protocol-spec", "DspLib-408-fir.json")
                )
        );
    }

    public static DspLibrary loadForDeviceVersion(String deviceVersion) {
        if (deviceVersion != null && deviceVersion.trim().toUpperCase().startsWith("FIR408")) {
            return loadFir408();
        }
        return loadDefault();
    }

    private static DspLibrary loadFromResourceOrFallback(String resource, List<Path> fallbackPaths) {
        try (InputStream in = DspLibraryLoader.class.getResourceAsStream(resource)) {
            if (in != null) {
                return parse(in);
            }
        } catch (IOException e) {
            throw new DspLibraryException("Failed to load library from classpath resource " + resource, e);
        }

        for (Path path : fallbackPaths) {
            if (Files.isRegularFile(path)) {
                return load(path);
            }
        }

        throw new DspLibraryException(
                "Could not find DSP library. Expected one of: "
                        + resource
                        + fallbackPaths.stream()
                        .map(Path::toString)
                        .reduce("", (a, b) -> a + ", ./" + b)
        );
    }

    public static DspLibrary load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new DspLibraryException("Failed to load library from file: " + path, e);
        }
    }

    private static DspLibrary parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);

        String libraryType = root.path("library_type").asText("unknown");
        int maxParameterBlockIndex = parseMaxParameterBlockIndex(root);

        Map<Integer, List<DspFieldLocation>> gainByBlock = parseGainReadLocations(root);
        Map<Integer, List<DspFieldLocation>> phaseByBlock = parsePhaseReadLocations(root);
        Map<Integer, List<DspFieldLocation>> delayByBlock = parseDelayReadLocations(root);

        Map<DspChannel, MatrixRouteLocation> matrixRouteLocations = parseMatrixRouteLocations(root);
        Map<Integer, List<MatrixCrosspointLocation>> matrixCrosspointLocations = parseMatrixCrosspointLocations(root);
        Map<Integer, List<CrossoverChannelLocation>> crossoverLocations = parseCrossoverLocations(root, matrixRouteLocations);

        Map<Integer, List<OutputPeqBandLocation>> outputPeqLocations = parseOutputPeqLocations(root, matrixRouteLocations);
        Map<Integer, OutputPeqBandDefaults> outputPeqDefaults = parseOutputPeqDefaults(root);

        Map<Integer, List<InputPeqBandLocation>> inputPeqLocations = parseInputPeqLocations(root);
        Map<Integer, List<InputGateLocation>> inputGateLocations = parseInputGateLocations(root);
        Map<Integer, List<InputGeqBandLocation>> inputGeqLocations = parseInputGeqLocations(root);
        Map<Integer, Double> inputGeqBandFrequencies = parseInputGeqBandFrequencies(root);
        Map<DspChannel, List<InputPeqBypassLocation>> inputPeqBypassLocations = parseInputPeqBypassLocations(root);

        Map<Integer, List<CompressorChannelLocation>> compressorLocations = parseCompressorLocations(root);
        Map<Integer, List<LimiterChannelLocation>> limiterLocations = parseLimiterLocations(root);
        Map<Integer, String> compressorRatiosByRaw = parseCompressorRatios(root);
        Map<Integer, String> inputCompressorTypesByRaw = parseInputCompressorTypes(root);
        MeterLayout meterLayout = parseMeterLayout(root);
        TestToneReadSpec testToneReadSpec = parseTestToneReadSpec(root);
        DspWriteCapabilities writeCapabilities = parseWriteCapabilities(root);

        MuteReadSpec muteReadSpec = parseMuteReadSpec(root);

        return new DspLibrary(
                libraryType,
                maxParameterBlockIndex,
                gainByBlock,
                phaseByBlock,
                delayByBlock,
                matrixRouteLocations,
                matrixCrosspointLocations,
                crossoverLocations,
                outputPeqLocations,
                outputPeqDefaults,
                inputPeqLocations,
                inputGateLocations,
                inputGeqLocations,
                inputGeqBandFrequencies,
                inputPeqBypassLocations,
                compressorLocations,
                limiterLocations,
                compressorRatiosByRaw,
                inputCompressorTypesByRaw,
                meterLayout,
                testToneReadSpec,
                writeCapabilities,
                muteReadSpec
        );
    }

    private static DspWriteCapabilities parseWriteCapabilities(JsonNode root) {
        if (!requiresStrictEvidence(root)) {
            return DspWriteCapabilities.permissive();
        }

        Map<Operation, Set<DspChannel>> supported = new EnumMap<>(Operation.class);
        JsonNode parameters = required(root, "parameters");

        supported.put(Operation.PHASE, confirmedChannelKeys(
                parameters.path("phase").path("write").path("channel_index_mapping"),
                parameters.path("phase").path("write")
        ));

        JsonNode outputPeqMap = parameters.path("peq_output").path("write").path("channel_index_full_map");
        supported.put(Operation.OUTPUT_PEQ, confirmedChannelKeys(outputPeqMap, outputPeqMap));

        JsonNode inputPeqChannels = parameters.path("peq_input").path("write")
                .path("predicted_all_inputs").path("inputs");
        supported.put(Operation.INPUT_PEQ, individuallyConfirmedChannelKeys(inputPeqChannels));

        supported.put(Operation.INPUT_GEQ, Set.of());

        JsonNode inputGateChannels = parameters.path("gate").path("write")
                .path("write_templates_by_input").path("inputs");
        supported.put(Operation.INPUT_GATE, individuallyConfirmedChannelKeys(inputGateChannels));

        supported.put(Operation.CROSSOVER_HIGH_PASS, confirmedLinearOutputCrossoverChannels(
                parameters.path("crossover").path("write").path("high_pass")
        ));
        supported.put(Operation.CROSSOVER_LOW_PASS, confirmedLinearOutputCrossoverChannels(
                parameters.path("crossover").path("write").path("low_pass")
        ));

        JsonNode compressorWrite = parameters.path("input_compressor").path("write");
        supported.put(Operation.INPUT_COMPRESSOR, readEvidenceAllowed(root, compressorWrite)
                ? individuallyConfirmedChannelKeys(compressorWrite.path("predicted_all_inputs").path("inputs"))
                : Set.of());

        EnumSet<DspChannel> firModes = EnumSet.noneOf(DspChannel.class);
        JsonNode firModeWrite = parameters.path("fir_processing_mode").path("write");
        if (readEvidenceAllowed(root, firModeWrite.path("fresh_out1_mode_toggle_capture"))) {
            firModes.add(DspChannel.OUT_1);
        }
        if (readEvidenceAllowed(root, firModeWrite.path("fresh_out8_mode_toggle_capture"))) {
            firModes.add(DspChannel.OUT_8);
        }
        if (firModes.contains(DspChannel.OUT_1) && firModes.contains(DspChannel.OUT_8)) {
            firModes.addAll(EnumSet.range(DspChannel.OUT_1, DspChannel.OUT_8));
        }
        supported.put(Operation.FIR_PROCESSING_MODE, firModes);

        JsonNode firGeneratorChannels = parameters.path("fir_generator").path("read_storage")
                .path("full_output_read_map").path("outputs");
        supported.put(Operation.FIR_GENERATOR, individuallyConfirmedChannelKeys(firGeneratorChannels));

        JsonNode externalFirSelectors = parameters.path("external_fir_upload")
                .path("selector_interpretation");
        supported.put(Operation.EXTERNAL_FIR_UPLOAD,
                readEvidenceAllowed(root, externalFirSelectors)
                        ? channelKeys(externalFirSelectors.path("observed_selector_map"))
                        : Set.of());

        return new DspWriteCapabilities(supported);
    }

    private static Set<DspChannel> confirmedChannelKeys(JsonNode channels, JsonNode evidenceNode) {
        return evidenceStatusConfirmed(evidenceNode.path("status").asText(null))
                ? channelKeys(channels)
                : Set.of();
    }

    private static Set<DspChannel> individuallyConfirmedChannelKeys(JsonNode channels) {
        EnumSet<DspChannel> result = EnumSet.noneOf(DspChannel.class);
        if (channels == null || !channels.isObject()) {
            return result;
        }
        channels.fields().forEachRemaining(entry -> {
            if (evidenceStatusConfirmed(entry.getValue().path("status").asText(null))) {
                addChannel(result, entry.getKey());
            }
        });
        return result;
    }

    private static Set<DspChannel> confirmedLinearOutputCrossoverChannels(JsonNode writeNode) {
        if (!evidenceStatusConfirmed(writeNode.path("status").asText(null))) {
            return Set.of();
        }

        Set<DspChannel> observed = channelKeys(writeNode.path("output_channel_index_observed"));
        if (!observed.contains(DspChannel.OUT_1) || !observed.contains(DspChannel.OUT_8)) {
            return observed;
        }

        return EnumSet.range(DspChannel.OUT_1, DspChannel.OUT_8);
    }

    private static Set<DspChannel> channelKeys(JsonNode channels) {
        EnumSet<DspChannel> result = EnumSet.noneOf(DspChannel.class);
        if (channels == null || !channels.isObject()) {
            return result;
        }
        channels.fieldNames().forEachRemaining(name -> addChannel(result, name));
        return result;
    }

    private static void addChannel(Set<DspChannel> channels, String channelName) {
        try {
            channels.add(DspChannel.parse(channelName));
        } catch (IllegalArgumentException ignored) {
            // Metadata objects may also contain fields such as "status" or "rule".
        }
    }

    private static TestToneReadSpec parseTestToneReadSpec(JsonNode root) {
        JsonNode read = required(root, "parameters", "test_tone_generator", "read");
        if (!readEvidenceAllowed(root, read)) {
            return null;
        }
        int blockIndex = parseHex(requiredText(read, "block_hex"));

        JsonNode fieldLocations = required(read, "field_locations");
        int sourceDataOffset = parseBlockDumpDataOffset(required(fieldLocations, "source"));
        int sineFrequencyDataOffset = parseBlockDumpDataOffset(required(fieldLocations, "sine_frequency_raw"));

        return new TestToneReadSpec(blockIndex, sourceDataOffset, sineFrequencyDataOffset);
    }

    private static Map<Integer, List<DspFieldLocation>> parseGainReadLocations(JsonNode root) {
        JsonNode gainRead = required(root, "parameters", "gain", "read");
        if (!readEvidenceAllowed(root, gainRead)) {
            return Map.of();
        }

        Map<Integer, List<DspFieldLocation>> byBlock = new HashMap<>();
        mergeLocationMap(byBlock, parseChannelLocationMap(root, required(gainRead, "input_locations"), gainRead));
        mergeLocationMap(byBlock, parseChannelLocationMap(root, required(gainRead, "output_locations"), gainRead));
        return byBlock;
    }

    private static Map<Integer, List<DspFieldLocation>> parsePhaseReadLocations(JsonNode root) {
        JsonNode phaseRead = required(root, "parameters", "phase", "read");
        if (!readEvidenceAllowed(root, phaseRead)
                || !phaseRead.has("input_locations")
                || !phaseRead.has("output_locations")) {
            return Map.of();
        }

        Map<Integer, List<DspFieldLocation>> byBlock = new HashMap<>();
        mergeLocationMap(byBlock, parseChannelLocationMap(root, required(phaseRead, "input_locations"), phaseRead));
        mergeLocationMap(byBlock, parseChannelLocationMap(root, required(phaseRead, "output_locations"), phaseRead));
        return byBlock;
    }

    private static Map<Integer, List<DspFieldLocation>> parseDelayReadLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "delay", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode channels = required(read, "full_channel_read_map", "channels");
        if (!channels.isObject()) {
            throw new DspLibraryException("Expected object for delay read map channels");
        }

        Map<Integer, List<DspFieldLocation>> byBlock = new HashMap<>();
        channels.fields().forEachRemaining(entry -> {
            DspChannel channel = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            // Delay has explicit per-channel evidence in both libraries. The
            // DSP408 map includes two predictions despite its legacy permissive policy.
            if (!evidenceStatusConfirmed(node.path("status").asText(read.path("status").asText(null)))) {
                return;
            }

            int blockIndex = parseHex(requiredText(node, "block_hex"));
            int dataOffset = parseBlockDumpDataOffset(node);

            byBlock
                    .computeIfAbsent(blockIndex, k -> new ArrayList<>())
                    .add(new DspFieldLocation(channel, blockIndex, dataOffset));
        });
        return byBlock;
    }

    private static Map<Integer, List<DspFieldLocation>> parseChannelLocationMap(
            JsonNode root,
            JsonNode arrayNode,
            JsonNode fallbackEvidence
    ) {
        List<DspFieldLocation> all = new ArrayList<>();

        if (!arrayNode.isArray()) {
            throw new DspLibraryException("Expected array");
        }

        for (JsonNode node : arrayNode) {
            if (!readEvidenceAllowed(root, node, fallbackEvidence)) {
                continue;
            }
            DspChannel channel = DspChannel.parse(requiredText(node, "channel"));
            int blockIndex = parseHex(requiredText(node, "block_hex"));
            int dataOffset = parseBlockDumpDataOffset(node);

            all.add(new DspFieldLocation(channel, blockIndex, dataOffset));
        }

        Map<Integer, List<DspFieldLocation>> byBlock = new HashMap<>();
        for (DspFieldLocation location : all) {
            byBlock.computeIfAbsent(location.blockIndex(), k -> new ArrayList<>()).add(location);
        }
        return byBlock;
    }

    private static void mergeLocationMap(Map<Integer, List<DspFieldLocation>> into,
                                         Map<Integer, List<DspFieldLocation>> from) {
        for (Map.Entry<Integer, List<DspFieldLocation>> entry : from.entrySet()) {
            into.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    private static Map<DspChannel, MatrixRouteLocation> parseMatrixRouteLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "matrix_routing", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode mapNode = read.path("predicted_full_read_map");
        if (mapNode.isMissingNode() || mapNode.isNull()) {
            mapNode = required(read, "full_read_map");
        }
        if (mapNode.has("outputs")) {
            mapNode = required(mapNode, "outputs");
        }
        if (!mapNode.isObject()) {
            throw new DspLibraryException("Expected object for matrix routing read map");
        }

        Map<DspChannel, MatrixRouteLocation> result = new HashMap<>();
        mapNode.fields().forEachRemaining(entry -> {
            DspChannel output = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            if (!readEvidenceAllowed(root, node, read)) {
                return;
            }

            int blockIndex = parseHex(requiredText(node, "block_hex"));
            int dataOffset = parseBlockDumpDataOffset(node);

            result.put(output, new MatrixRouteLocation(output, blockIndex, dataOffset));
        });

        return Map.copyOf(result);
    }

    private static Map<Integer, List<MatrixCrosspointLocation>> parseMatrixCrosspointLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "matrix_crosspoint_gain", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode outputs = read.path("predicted_full_read_map");
        if (outputs.isMissingNode() || outputs.isNull()) {
            outputs = required(read, "full_read_map");
        }
        if (outputs.has("outputs")) {
            outputs = required(outputs, "outputs");
        }
        if (!outputs.isObject()) {
            throw new DspLibraryException("Expected object for matrix crosspoint gain read map");
        }

        Map<Integer, List<MatrixCrosspointLocation>> byBlock = new HashMap<>();
        outputs.fields().forEachRemaining(outputEntry -> {
            DspChannel output = DspChannel.parse(outputEntry.getKey());
            JsonNode inputs = outputEntry.getValue();
            if (!inputs.isObject()) {
                throw new DspLibraryException("Expected object for matrix crosspoint gain inputs of " + outputEntry.getKey());
            }

            inputs.fields().forEachRemaining(inputEntry -> {
                DspChannel input = DspChannel.parse(inputEntry.getKey());
                JsonNode node = inputEntry.getValue();
                if (!readEvidenceAllowed(root, node, read)) {
                    return;
                }

                int blockIndex = parseHex(requiredText(node, "block_hex"));
                int dataOffset = parseBlockDumpDataOffset(node);

                byBlock
                        .computeIfAbsent(blockIndex, k -> new ArrayList<>())
                        .add(new MatrixCrosspointLocation(output, input, blockIndex, dataOffset));
            });
        });
        return byBlock;
    }

    private static MuteReadSpec parseMuteReadSpec(JsonNode root) {
        JsonNode muteRead = required(root, "parameters", "mute", "read");
        JsonNode inputs = required(muteRead, "inputs");
        JsonNode outputs = required(muteRead, "outputs");
        String status = muteRead.path("status").asText("").trim().toLowerCase();

        int inputBlock = parseHex(requiredText(inputs, "block_hex"));
        int outputBlock = parseHex(requiredText(outputs, "block_hex"));

        if (inputBlock != outputBlock) {
            throw new DspLibraryException(
                    "Mute input/output blocks differ in library: inputs="
                            + inputBlock + " outputs=" + outputBlock
            );
        }

        int inputDataOffset = parseBlockDumpDataOffset(inputs);
        int outputDataOffset = parseBlockDumpDataOffset(outputs);
        boolean confirmedFromDevice = evidenceStatusConfirmed(status);

        return new MuteReadSpec(inputBlock, inputDataOffset, outputDataOffset, confirmedFromDevice);
    }

    private static Map<Integer, List<OutputPeqBandLocation>> parseOutputPeqLocations(
            JsonNode root,
            Map<DspChannel, MatrixRouteLocation> matrixRouteLocations
    ) {
        JsonNode explicitOutputs = root.path("parameters")
                .path("peq_output")
                .path("read")
                .path("full_output_read_map")
                .path("outputs");
        if (explicitOutputs.isObject()) {
            return parseExplicitOutputPeqLocations(root, explicitOutputs);
        }

        JsonNode read = required(root, "parameters", "peq_output", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode peq1 = required(read, "output_observations", "Out1", "PEQ1");

        int gainOffset = parseBlockDumpDataOffset(required(peq1, "gain"));
        int frequencyOffset = parseBlockDumpDataOffset(required(peq1, "frequency"));
        int qOffset = parseBlockDumpDataOffset(required(peq1, "q"));
        int recordSize = (qOffset - gainOffset) + 2;

        if (recordSize != 6 || frequencyOffset != gainOffset + 2 || qOffset != gainOffset + 4) {
            throw new DspLibraryException("Unexpected output PEQ read layout in library");
        }

        Map<Integer, List<OutputPeqBandLocation>> byBlock = new HashMap<>();
        for (DspChannel channel : DspChannel.values()) {
            if (channel.index() < 4) {
                continue;
            }

            MatrixRouteLocation routeLocation = matrixRouteLocations.get(channel);
            if (routeLocation == null) {
                throw new DspLibraryException("Missing matrix route location for output " + channel.displayName());
            }

            int startBlockIndex = routeLocation.blockIndex() + 1;
            for (int bandIndex = 1; bandIndex <= 9; bandIndex++) {
                int bandBaseOffset = gainOffset + ((bandIndex - 1) * recordSize);
                OutputPeqBandLocation location = new OutputPeqBandLocation(
                        channel,
                        bandIndex,
                        advanceOutputPeqField(startBlockIndex, bandBaseOffset),
                        advanceOutputPeqField(startBlockIndex, bandBaseOffset + 2),
                        advanceOutputPeqField(startBlockIndex, bandBaseOffset + 4),
                        advanceOutputPeqField(startBlockIndex, bandBaseOffset + 5),
                        null,
                        bandIndex <= 8 ? bandIndex - 1 : 0
                );
                addOutputPeqLocation(byBlock, location);
            }
        }

        return byBlock;
    }

    private static Map<Integer, List<OutputPeqBandLocation>> parseExplicitOutputPeqLocations(
            JsonNode root,
            JsonNode outputs
    ) {
        Map<Integer, List<OutputPeqBandLocation>> byBlock = new HashMap<>();

        outputs.fields().forEachRemaining(outputEntry -> {
            DspChannel channel = DspChannel.parse(outputEntry.getKey());
            JsonNode outputNode = outputEntry.getValue();
            if (!readEvidenceAllowed(root, outputNode)) {
                return;
            }
            JsonNode starts = required(outputNode, "value_record_starts");
            JsonNode bypass = required(outputNode, "bypass_bitmask");
            PeqFieldLocation lowBypassMask = parsePayloadFieldReference(
                    requiredText(bypass, "low_byte_peq1_to_peq8"),
                    0
            );
            PeqFieldLocation highBypassMask = parsePayloadFieldReference(
                    requiredText(bypass, "high_byte_peq9"),
                    0
            );

            for (int bandIndex = 1; bandIndex <= 9; bandIndex++) {
                String reference = requiredText(starts, "PEQ" + bandIndex);
                OutputPeqBandLocation location = new OutputPeqBandLocation(
                        channel,
                        bandIndex,
                        parsePayloadFieldReference(reference, 0),
                        parsePayloadFieldReference(reference, 2),
                        parsePayloadFieldReference(reference, 4),
                        parsePayloadFieldReference(reference, 5),
                        bandIndex <= 8 ? lowBypassMask : highBypassMask,
                        bandIndex <= 8 ? bandIndex - 1 : 0
                );
                addOutputPeqLocation(byBlock, location);
            }
        });

        return byBlock;
    }

    private static void addOutputPeqLocation(Map<Integer, List<OutputPeqBandLocation>> byBlock,
                                             OutputPeqBandLocation location) {
        addOutputPeqLocation(byBlock, location.gain().blockIndex(), location);
        addOutputPeqLocation(byBlock, location.frequency().blockIndex(), location);
        addOutputPeqLocation(byBlock, location.q().blockIndex(), location);
        addOutputPeqLocation(byBlock, location.filterType().blockIndex(), location);
        if (location.bypassMask() != null) {
            addOutputPeqLocation(byBlock, location.bypassMask().blockIndex(), location);
        }
    }

    private static void addOutputPeqLocation(Map<Integer, List<OutputPeqBandLocation>> byBlock,
                                             int blockIndex,
                                             OutputPeqBandLocation location) {
        List<OutputPeqBandLocation> locations = byBlock.computeIfAbsent(blockIndex, key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static PeqFieldLocation advanceOutputPeqField(int startBlockIndex, int absoluteByteIndex) {
        int blockIndex = startBlockIndex;
        int position = absoluteByteIndex;

        while (true) {
            int blockSize = outputPeqBlockDataSize(blockIndex);
            if (position < blockSize) {
                return new PeqFieldLocation(blockIndex, position);
            }

            position -= blockSize;
            blockIndex++;

            if (blockIndex > 0x1C) {
                throw new DspLibraryException("Output PEQ mapping overflow beyond block 1C");
            }
        }
    }

    private static int outputPeqBlockDataSize(int blockIndex) {
        return blockIndex == 0x1C ? 48 : 50;
    }

    private static PeqFieldLocation parsePayloadFieldReference(String reference, int relativeOffset) {
        String[] parts = reference.trim().split(":");
        if (parts.length != 2) {
            throw new DspLibraryException("Invalid payload field reference: " + reference);
        }

        int blockIndex = parseHex(parts[0]);
        int dataOffset = parseHex(parts[1]) - 5 + relativeOffset;
        while (dataOffset >= parameterBlockDataSize(blockIndex)) {
            dataOffset -= parameterBlockDataSize(blockIndex);
            blockIndex++;
        }

        if (dataOffset < 0) {
            throw new DspLibraryException("Payload field reference became negative: " + reference);
        }
        return new PeqFieldLocation(blockIndex, dataOffset);
    }

    private static int parameterBlockDataSize(int blockIndex) {
        return blockIndex == 0x1F ? 6 : 50;
    }

    private static Map<Integer, List<CrossoverChannelLocation>> parseCrossoverLocations(
            JsonNode root,
            Map<DspChannel, MatrixRouteLocation> matrixRouteLocations
    ) {
        JsonNode read = required(root, "parameters", "crossover", "read");
        if (!readEvidenceAllowed(root, read) && !requiresStrictEvidence(root)) {
            return Map.of();
        }

        Map<Integer, List<CrossoverChannelLocation>> byBlock = new HashMap<>();
        JsonNode inputObservations = required(read, "input_observations");
        JsonNode inA = required(inputObservations, "InA");
        if (crossoverNodeConfirmed(root, inA)) {
            addObservedInputCrossoverLocation(byBlock, DspChannel.IN_A, inA);
        }

        JsonNode predictedInputs = required(read, "predicted_input_read_maps");
        addPredictedInputCrossoverLocationIfConfirmed(
                root, byBlock, DspChannel.IN_B, required(predictedInputs, "InB")
        );
        addPredictedInputCrossoverLocationIfConfirmed(
                root, byBlock, DspChannel.IN_C, required(predictedInputs, "InC")
        );
        addPredictedInputCrossoverLocationIfConfirmed(
                root, byBlock, DspChannel.IN_D, required(predictedInputs, "InD")
        );

        JsonNode fullOutputReadMap = read.path("full_output_read_map").path("outputs");
        if (fullOutputReadMap.isObject()) {
            addExplicitOutputCrossoverLocations(root, byBlock, fullOutputReadMap);
        } else {
            for (DspChannel channel : DspChannel.values()) {
                if (channel.index() < 4) {
                    continue;
                }

                MatrixRouteLocation routeLocation = matrixRouteLocations.get(channel);
                if (routeLocation == null) {
                    throw new DspLibraryException("Missing matrix route location for output crossover " + channel.displayName());
                }

                CrossoverChannelLocation location = new CrossoverChannelLocation(
                        channel,
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 10),
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 14),
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 14),
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 12),
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 15),
                        advanceOutputField(routeLocation.blockIndex(), routeLocation.dataOffset() + 15)
                );
                addCrossoverLocation(byBlock, location);
            }
        }

        return byBlock;
    }

    private static void addExplicitOutputCrossoverLocations(
            JsonNode root,
            Map<Integer, List<CrossoverChannelLocation>> byBlock,
            JsonNode outputs
    ) {
        outputs.fields().forEachRemaining(entry -> {
            DspChannel channel = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            if (!readEvidenceAllowed(root, node)) {
                return;
            }

            PeqFieldLocation highPassMode = parseInputPeqFieldLocation(
                    node.has("high_pass_mode")
                            ? required(node, "high_pass_mode")
                            : required(node, "high_pass_state_or_slope")
            );
            PeqFieldLocation lowPassMode = parseInputPeqFieldLocation(
                    node.has("low_pass_mode")
                            ? required(node, "low_pass_mode")
                            : required(node, "low_pass_state_or_slope")
            );

            CrossoverChannelLocation location = new CrossoverChannelLocation(
                    channel,
                    parseInputPeqFieldLocation(required(node, "high_pass_frequency")),
                    highPassMode,
                    highPassMode,
                    parseInputPeqFieldLocation(required(node, "low_pass_frequency")),
                    lowPassMode,
                    lowPassMode
            );
            addCrossoverLocation(byBlock, location);
        });
    }

    private static void addObservedInputCrossoverLocation(Map<Integer, List<CrossoverChannelLocation>> byBlock,
                                                          DspChannel channel,
                                                          JsonNode node) {
        CrossoverChannelLocation location = new CrossoverChannelLocation(
                channel,
                parseInputPeqFieldLocation(required(node, "high_pass_frequency")),
                parseInputPeqFieldLocation(required(node, "high_pass_slope_or_mode")),
                parseInputPeqFieldLocation(required(node, "high_pass_bypass")),
                parseInputPeqFieldLocation(required(node, "low_pass_frequency")),
                node.has("low_pass_mode") ? parseInputPeqFieldLocation(node.get("low_pass_mode")) : null,
                parseInputPeqFieldLocation(required(node, "low_pass_bypass"))
        );
        addCrossoverLocation(byBlock, location);
    }

    private static void addPredictedInputCrossoverLocation(Map<Integer, List<CrossoverChannelLocation>> byBlock,
                                                           DspChannel channel,
                                                           JsonNode node) {
        CrossoverChannelLocation location = new CrossoverChannelLocation(
                channel,
                parseInputPeqFieldLocation(required(node, "high_pass_frequency")),
                parseInputPeqFieldLocation(required(node, "high_pass_mode")),
                parseInputPeqFieldLocation(required(node, "high_pass_mode")),
                parseInputPeqFieldLocation(required(node, "low_pass_frequency")),
                null,
                parseInputPeqFieldLocation(required(node, "low_pass_bypass"))
        );
        addCrossoverLocation(byBlock, location);
    }

    private static void addPredictedInputCrossoverLocationIfConfirmed(
            JsonNode root,
            Map<Integer, List<CrossoverChannelLocation>> byBlock,
            DspChannel channel,
            JsonNode node
    ) {
        if (crossoverNodeConfirmed(root, node)) {
            addPredictedInputCrossoverLocation(byBlock, channel, node);
        }
    }

    private static void addCrossoverLocation(Map<Integer, List<CrossoverChannelLocation>> byBlock,
                                             CrossoverChannelLocation location) {
        addCrossoverLocation(byBlock, location.highPassFrequency(), location);
        addCrossoverLocation(byBlock, location.highPassMode(), location);
        addCrossoverLocation(byBlock, location.highPassBypass(), location);
        addCrossoverLocation(byBlock, location.lowPassFrequency(), location);
        addCrossoverLocation(byBlock, location.lowPassMode(), location);
        addCrossoverLocation(byBlock, location.lowPassBypass(), location);
    }

    private static void addCrossoverLocation(Map<Integer, List<CrossoverChannelLocation>> byBlock,
                                             PeqFieldLocation field,
                                             CrossoverChannelLocation location) {
        if (field == null) {
            return;
        }
        List<CrossoverChannelLocation> locations = byBlock.computeIfAbsent(field.blockIndex(), key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static PeqFieldLocation advanceOutputField(int startBlockIndex, int absoluteByteIndex) {
        int blockIndex = startBlockIndex;
        int position = absoluteByteIndex;

        while (true) {
            int blockSize = outputPeqBlockDataSize(blockIndex);
            if (position < blockSize) {
                return new PeqFieldLocation(blockIndex, position);
            }

            position -= blockSize;
            blockIndex++;

            if (blockIndex > 0x1C) {
                throw new DspLibraryException("Output field mapping overflow beyond block 1C");
            }
        }
    }

    private static Map<Integer, List<InputPeqBandLocation>> parseInputPeqLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "peq_input", "read");
        if (!readEvidenceAllowed(root, read) && !requiresStrictEvidence(root)) {
            return Map.of();
        }

        Map<Integer, List<InputPeqBandLocation>> byBlock = new HashMap<>();
        if (read.has("ina_full_read_map_observed")) {
            JsonNode observations = required(read, "input_observations");
            if (readEvidenceAllowed(root, required(observations, "InA"))) {
                parseInputPeqBandMap(byBlock, DspChannel.IN_A, required(read, "ina_full_read_map_observed"));
            }
            if (observations.has("InB") && readEvidenceAllowed(root, observations.get("InB"))) {
                parseInputPeqBandMap(
                        byBlock,
                        DspChannel.IN_B,
                        required(read, "inb_full_read_map_predicted_from_observed_ina_ind_stride")
                );
            }
            if (observations.has("InC") && readEvidenceAllowed(root, observations.get("InC"))) {
                parseInputPeqBandMap(
                        byBlock,
                        DspChannel.IN_C,
                        required(read, "inc_full_read_map_predicted_from_observed_ina_ind_stride")
                );
            }
            if (readEvidenceAllowed(root, required(observations, "InD"))) {
                parseInputPeqBandMap(byBlock, DspChannel.IN_D, required(read, "ind_full_read_map_observed"));
            }
        } else {
            JsonNode predictedMaps = required(read, "predicted_input_read_maps");
            parseInputPeqBandMap(byBlock, DspChannel.IN_A, required(read, "ina_full_read_prediction"));
            parseInputPeqBandMap(byBlock, DspChannel.IN_B, required(predictedMaps, "InB"));
            parseInputPeqBandMap(byBlock, DspChannel.IN_C, required(predictedMaps, "InC"));
            parseInputPeqBandMap(byBlock, DspChannel.IN_D, required(predictedMaps, "InD"));
        }

        return byBlock;
    }

    private static Map<Integer, List<InputGateLocation>> parseInputGateLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "gate", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode inputs = required(read, "full_input_read_map", "inputs");
        if (!inputs.isObject()) {
            throw new DspLibraryException("Expected object for gate input read map");
        }

        Map<Integer, List<InputGateLocation>> byBlock = new HashMap<>();
        inputs.fields().forEachRemaining(entry -> {
            DspChannel channel = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            if (!readEvidenceAllowed(root, node, read)) {
                return;
            }

            InputGateLocation location = new InputGateLocation(
                    channel,
                    parseInputPeqFieldLocation(required(node, "attack")),
                    parseInputPeqFieldLocation(required(node, "release")),
                    parseInputPeqFieldLocation(required(node, "hold")),
                    parseInputPeqFieldLocation(required(node, "threshold"))
            );
            addInputGateLocation(byBlock, location);
        });

        return byBlock;
    }

    private static Map<Integer, List<InputGeqBandLocation>> parseInputGeqLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "geq_input", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        Map<Integer, List<InputGeqBandLocation>> byBlock = new HashMap<>();

        for (DspChannel channel : List.of(DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D)) {
            int channelIndex = channel.index();
            int baseBlock = channelIndex * 3;
            int baseSlot = 16 - (channelIndex * 5);

            for (int bandIndex = 1; bandIndex <= 31; bandIndex++) {
                int slot = baseSlot + (bandIndex - 1);
                int blockIndex = baseBlock + Math.floorDiv(slot, 25);
                int dataOffset = (slot % 25) * 2;

                InputGeqBandLocation location = new InputGeqBandLocation(
                        channel,
                        bandIndex,
                        new PeqFieldLocation(blockIndex, dataOffset)
                );

                byBlock.computeIfAbsent(blockIndex, key -> new ArrayList<>()).add(location);
            }
        }

        return byBlock;
    }

    private static Map<Integer, Double> parseInputGeqBandFrequencies(JsonNode root) {
        JsonNode frequencies = required(root, "enums", "geq_band_freqs_hz");
        if (!frequencies.isArray()) {
            throw new DspLibraryException("Expected array for GEQ band frequencies");
        }

        Map<Integer, Double> byBand = new HashMap<>();
        for (int i = 0; i < frequencies.size(); i++) {
            byBand.put(i + 1, parseCompactFrequencyText(frequencies.get(i).asText()));
        }
        return byBand;
    }

    private static void parseInputPeqBandMap(Map<Integer, List<InputPeqBandLocation>> byBlock,
                                             DspChannel channel,
                                             JsonNode channelNode) {
        if (!channelNode.isObject()) {
            throw new DspLibraryException("Expected object for input PEQ read map of " + channel.displayName());
        }

        channelNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!key.toUpperCase().startsWith("PEQ")) {
                return;
            }

            int bandIndex = Integer.parseInt(key.substring(3));
            JsonNode bandNode = entry.getValue();

            InputPeqBandLocation location = new InputPeqBandLocation(
                    channel,
                    bandIndex,
                    parseInputPeqFieldLocation(required(bandNode, "gain")),
                    parseInputPeqFieldLocation(required(bandNode, "frequency")),
                    parseInputPeqFieldLocation(required(bandNode, "q_raw")),
                    parseInputPeqFieldLocation(required(bandNode, "filter_type"))
            );

            addInputPeqLocation(byBlock, location);
        });
    }

    private static PeqFieldLocation parseInputPeqFieldLocation(JsonNode node) {
        if (node.isArray() && node.size() == 2) {
            int blockIndex = parseHex(node.get(0).asText());
            int dataOffset = node.get(1).asInt() - 5;
            if (dataOffset < 0) {
                throw new DspLibraryException("Input PEQ data offset became negative");
            }
            return new PeqFieldLocation(blockIndex, dataOffset);
        }

        int blockIndex = parseHex(requiredText(node, node.has("block_hex") ? "block_hex" : "predicted_block_hex"));
        int dataOffset = parseBlockDumpDataOffset(node);
        return new PeqFieldLocation(blockIndex, dataOffset);
    }

    private static void addInputPeqLocation(Map<Integer, List<InputPeqBandLocation>> byBlock,
                                            InputPeqBandLocation location) {
        addInputPeqLocation(byBlock, location.gain().blockIndex(), location);
        addInputPeqLocation(byBlock, location.frequency().blockIndex(), location);
        addInputPeqLocation(byBlock, location.q().blockIndex(), location);
        addInputPeqLocation(byBlock, location.filterType().blockIndex(), location);
    }

    private static void addInputPeqLocation(Map<Integer, List<InputPeqBandLocation>> byBlock,
                                            int blockIndex,
                                            InputPeqBandLocation location) {
        List<InputPeqBandLocation> locations = byBlock.computeIfAbsent(blockIndex, key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static void addInputGateLocation(Map<Integer, List<InputGateLocation>> byBlock,
                                             InputGateLocation location) {
        addInputGateLocation(byBlock, location.attack(), location);
        addInputGateLocation(byBlock, location.release(), location);
        addInputGateLocation(byBlock, location.hold(), location);
        addInputGateLocation(byBlock, location.threshold(), location);
    }

    private static void addInputGateLocation(Map<Integer, List<InputGateLocation>> byBlock,
                                             PeqFieldLocation field,
                                             InputGateLocation location) {
        List<InputGateLocation> locations = byBlock.computeIfAbsent(field.blockIndex(), key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static Map<DspChannel, List<InputPeqBypassLocation>> parseInputPeqBypassLocations(JsonNode root) {
        JsonNode observedInputs = root.path("parameters")
                .path("peq_input")
                .path("read")
                .path("bypass_read_maps_observed")
                .path("inputs");
        if (observedInputs.isObject()) {
            Map<DspChannel, List<InputPeqBypassLocation>> byChannel = new HashMap<>();
            observedInputs.fields().forEachRemaining(entry -> {
                DspChannel channel = DspChannel.parse(entry.getKey());
                JsonNode node = entry.getValue();
                if (!readEvidenceAllowed(root, node)) {
                    return;
                }
                int blockIndex = parseHex(requiredText(node, "block_hex"));
                int dataOffset = parseBlockDumpDataOffset(node);

                List<InputPeqBypassLocation> locations = new ArrayList<>();
                for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                    locations.add(new InputPeqBypassLocation(blockIndex, dataOffset, bitIndex));
                }
                byChannel.put(channel, List.copyOf(locations));
            });
            return byChannel;
        }

        JsonNode prediction = required(root, "parameters", "peq_input", "read", "bypass_prediction");
        int blockIndex = parseHex(requiredText(prediction, "block_hex"));
        int dataOffset = parseBlockDumpDataOffset(prediction);

        Map<DspChannel, List<InputPeqBypassLocation>> byChannel = new HashMap<>();
        List<InputPeqBypassLocation> inALocations = new ArrayList<>();
        for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
            inALocations.add(new InputPeqBypassLocation(blockIndex, dataOffset, bitIndex));
        }
        byChannel.put(DspChannel.IN_A, List.copyOf(inALocations));
        return byChannel;
    }

    private static Map<Integer, OutputPeqBandDefaults> parseOutputPeqDefaults(JsonNode root) {
        JsonNode baseline = required(root, "parameters", "peq_output", "ui", "decoderPres_baseline_out1");
        if (!baseline.isArray()) {
            throw new DspLibraryException("Expected array for output PEQ baseline");
        }

        Map<Integer, OutputPeqBandDefaults> defaults = new HashMap<>();
        for (JsonNode node : baseline) {
            if (!readEvidenceAllowed(root, node)) {
                continue;
            }
            int bandIndex = required(node, "band").asInt();
            double frequencyHz = parseFrequencyText(requiredText(node, "frequency_gui"));
            double q = Double.parseDouble(requiredText(node, "q_gui"));
            double gainDb = parseDecibelText(requiredText(node, "gain_gui"));
            PeqFilterType filterType = parsePeqFilterType(requiredText(node, "type_gui"));
            defaults.put(bandIndex, new OutputPeqBandDefaults(bandIndex, frequencyHz, q, gainDb, filterType));
        }
        return defaults;
    }

    private static Map<Integer, List<CompressorChannelLocation>> parseCompressorLocations(JsonNode root) {
        JsonNode compressor = root.path("parameters").path("input_compressor");
        if (compressor.isMissingNode() || compressor.isNull()) {
            return Map.of();
        }

        JsonNode read = required(compressor, "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode inputs = required(read, "input_locations");
        if (!inputs.isObject()) {
            throw new DspLibraryException("Expected object for input compressor read map");
        }

        Map<Integer, List<CompressorChannelLocation>> byBlock = new HashMap<>();
        inputs.fields().forEachRemaining(entry -> {
            DspChannel channel = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            if (!readEvidenceAllowed(root, node, read)) {
                return;
            }

            CompressorChannelLocation location = new CompressorChannelLocation(
                    channel,
                    parseChildFieldLocation(node, "ratio"),
                    parseChildFieldLocation(node, "attack"),
                    parseChildFieldLocation(node, "release"),
                    parseChildFieldLocation(node, "knee"),
                    parseChildFieldLocation(node, "threshold"),
                    parseChildFieldLocation(node, "frequency"),
                    parseChildFieldLocation(node, "type_context"),
                    parseChildFieldLocation(node, "type")
            );
            addCompressorLocation(byBlock, location);
        });

        return byBlock;
    }

    private static void addCompressorLocation(Map<Integer, List<CompressorChannelLocation>> byBlock,
                                              CompressorChannelLocation location) {
        addCompressorLocation(byBlock, location.ratio(), location);
        addCompressorLocation(byBlock, location.attack(), location);
        addCompressorLocation(byBlock, location.release(), location);
        addCompressorLocation(byBlock, location.knee(), location);
        addCompressorLocation(byBlock, location.threshold(), location);
        addCompressorLocation(byBlock, location.frequency(), location);
        addCompressorLocation(byBlock, location.typeContext(), location);
        addCompressorLocation(byBlock, location.type(), location);
    }

    private static void addCompressorLocation(Map<Integer, List<CompressorChannelLocation>> byBlock,
                                              PeqFieldLocation field,
                                              CompressorChannelLocation location) {
        List<CompressorChannelLocation> locations = byBlock.computeIfAbsent(field.blockIndex(), key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static Map<Integer, List<LimiterChannelLocation>> parseLimiterLocations(JsonNode root) {
        JsonNode read = required(root, "parameters", "limiter", "read");
        if (!readEvidenceAllowed(root, read)) {
            return Map.of();
        }
        JsonNode outputs = read.path("full_output_read_map").path("outputs");
        if (!outputs.isObject()) {
            outputs = required(read, "output_locations");
        }
        if (!outputs.isObject()) {
            throw new DspLibraryException("Expected object for limiter output read map");
        }

        Map<Integer, List<LimiterChannelLocation>> byBlock = new HashMap<>();
        outputs.fields().forEachRemaining(entry -> {
            DspChannel channel = DspChannel.parse(entry.getKey());
            JsonNode node = entry.getValue();
            if (!readEvidenceAllowed(root, node, read)) {
                return;
            }

            LimiterChannelLocation location = new LimiterChannelLocation(
                    channel,
                    parseChildFieldLocation(node, "attack"),
                    parseChildFieldLocation(node, "release"),
                    node.has("unknown")
                            ? parseChildFieldLocation(node, "unknown")
                            : parseChildFieldLocation(node, "knee"),
                    parseChildFieldLocation(node, "threshold")
            );
            addLimiterLocation(byBlock, location);
        });

        return byBlock;
    }

    private static PeqFieldLocation parseChildFieldLocation(JsonNode parent, String fieldName) {
        JsonNode field = required(parent, fieldName);
        String blockHex = field.has("block_hex")
                ? requiredText(field, "block_hex")
                : requiredText(parent, "block_hex");
        int dataOffset = parseBlockDumpDataOffset(field);
        return new PeqFieldLocation(parseHex(blockHex), dataOffset);
    }

    private static void addLimiterLocation(Map<Integer, List<LimiterChannelLocation>> byBlock,
                                           LimiterChannelLocation location) {
        addLimiterLocation(byBlock, location.attack(), location);
        addLimiterLocation(byBlock, location.release(), location);
        addLimiterLocation(byBlock, location.knee(), location);
        addLimiterLocation(byBlock, location.threshold(), location);
    }

    private static void addLimiterLocation(Map<Integer, List<LimiterChannelLocation>> byBlock,
                                           PeqFieldLocation field,
                                           LimiterChannelLocation location) {
        List<LimiterChannelLocation> locations = byBlock.computeIfAbsent(field.blockIndex(), key -> new ArrayList<>());
        if (!locations.contains(location)) {
            locations.add(location);
        }
    }

    private static Map<Integer, String> parseCompressorRatios(JsonNode root) {
        JsonNode ratios = required(root, "enums", "compressor_ratios");
        if (!ratios.isArray()) {
            throw new DspLibraryException("Expected array for compressor ratios");
        }

        Map<Integer, String> byRaw = new HashMap<>();
        for (int i = 0; i < ratios.size(); i++) {
            byRaw.put(i, ratios.get(i).asText());
        }
        return byRaw;
    }

    private static Map<Integer, String> parseInputCompressorTypes(JsonNode root) {
        JsonNode types = root.path("enums").path("input_compressor_types");
        if (types.isMissingNode() || types.isNull()) {
            return Map.of();
        }
        if (!types.isArray()) {
            throw new DspLibraryException("Expected array for input compressor types");
        }

        Map<Integer, String> byRaw = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            byRaw.put(i, types.get(i).asText());
        }
        return byRaw;
    }

    private static MeterLayout parseMeterLayout(JsonNode root) {
        JsonNode read = required(root, "parameters", "meters", "read");
        JsonNode slotsNode = required(read, "slot_mapping", "slots");
        if (!slotsNode.isArray()) {
            throw new DspLibraryException("Expected array for meter slots");
        }

        List<MeterSlotLocation> slots = new ArrayList<>();
        for (JsonNode slotNode : slotsNode) {
            int slotIndex = required(slotNode, "slot").asInt();
            DspChannel channel = DspChannel.parse(requiredText(slotNode, "channel"));
            String range = requiredText(slotNode, "offset_range");

            int start = parseOffsetRangeStart(range);
            int width = parseOffsetRangeWidth(range);

            slots.add(new MeterSlotLocation(slotIndex, channel, start, width));
        }

        int responseLengthBytes = read.path("response_length_bytes_observed").asInt(42);

        JsonNode tailBytes = required(read, "response_structure", "tail_bytes");
        int statusByteOffset = findTailByteOffset(
                tailBytes,
                "input_runtime_status",
                findTailByteOffset(tailBytes, "status_byte_40", 40)
        );
        int limiterMaskOffset = findTailByteOffset(tailBytes, "output_limiter_mask", 41);
        JsonNode clipIndicator = read.path("clip_indicator_runtime");
        Double clipThresholdRaw = clipIndicator.has("threshold_raw_precise")
                && readEvidenceAllowed(root, clipIndicator)
                ? clipIndicator.get("threshold_raw_precise").asDouble()
                : null;
        Set<DspChannel> confirmedClipThresholdChannels = EnumSet.noneOf(DspChannel.class);
        JsonNode confirmedClipChannels = clipIndicator.path("confirmed_channels");
        if (confirmedClipChannels.isArray()) {
            for (JsonNode channel : confirmedClipChannels) {
                DspChannel parsed = DspChannel.parse(channel.asText());
                if (readEvidenceAllowed(root, clipIndicator)) {
                    confirmedClipThresholdChannels.add(parsed);
                }
            }
        }

        JsonNode slotMapping = read.path("slot_mapping");
        JsonNode levelCalibration = read.path("value_encoding").path("physical_level_calibration");
        JsonNode limiterMapping = read.path("limiter_indicator_runtime");
        boolean channelMappingConfirmed = !slotMapping.isMissingNode()
                && readEvidenceAllowed(root, slotMapping);
        boolean levelCalibrationConfirmed = !levelCalibration.isMissingNode()
                && readEvidenceAllowed(root, levelCalibration);
        boolean limiterMappingConfirmed = !limiterMapping.isMissingNode()
                && readEvidenceAllowed(root, limiterMapping);

        return new MeterLayout(
                slots,
                responseLengthBytes,
                statusByteOffset,
                limiterMaskOffset,
                clipThresholdRaw,
                confirmedClipThresholdChannels,
                channelMappingConfirmed,
                levelCalibrationConfirmed,
                limiterMappingConfirmed
        );
    }

    private static int findTailByteOffset(JsonNode tailBytes, String name, int fallback) {
        if (!tailBytes.isArray()) {
            return fallback;
        }

        for (JsonNode node : tailBytes) {
            String currentName = node.path("name").asText();
            if (name.equals(currentName) && node.has("offset")) {
                return node.get("offset").asInt();
            }
        }

        return fallback;
    }

    private static int parseOffsetRangeStart(String value) {
        String[] parts = value.trim().split("\\.\\.");
        if (parts.length != 2) {
            throw new DspLibraryException("Invalid offset_range: " + value);
        }
        return Integer.parseInt(parts[0].trim());
    }

    private static int parseOffsetRangeWidth(String value) {
        String[] parts = value.trim().split("\\.\\.");
        if (parts.length != 2) {
            throw new DspLibraryException("Invalid offset_range: " + value);
        }
        int start = Integer.parseInt(parts[0].trim());
        int end = Integer.parseInt(parts[1].trim());
        if (end < start) {
            throw new DspLibraryException("Invalid offset_range end before start: " + value);
        }
        return (end - start) + 1;
    }

    private static PeqFilterType parsePeqFilterType(String value) {
        try {
            return PeqFilterType.parse(value);
        } catch (IllegalArgumentException e) {
            throw new DspLibraryException("Unknown PEQ filter type: " + value, e);
        }
    }

    private static double parseFrequencyText(String value) {
        String normalized = value.trim().toUpperCase();
        if (normalized.endsWith("KHZ")) {
            String numeric = normalized.substring(0, normalized.length() - 3).trim();
            return Double.parseDouble(numeric) * 1000.0;
        }
        if (normalized.endsWith("HZ")) {
            String numeric = normalized.substring(0, normalized.length() - 2).trim();
            return Double.parseDouble(numeric);
        }
        throw new DspLibraryException("Unsupported frequency text: " + value);
    }

    private static double parseCompactFrequencyText(String value) {
        String normalized = value.trim().toUpperCase();
        if (normalized.endsWith("KHZ")) {
            String numeric = normalized.substring(0, normalized.length() - 3).trim();
            return Double.parseDouble(numeric) * 1000.0;
        }
        if (normalized.endsWith("K")) {
            String numeric = normalized.substring(0, normalized.length() - 1).trim();
            return Double.parseDouble(numeric) * 1000.0;
        }
        return Double.parseDouble(normalized);
    }

    private static double parseDecibelText(String value) {
        String normalized = value.trim().toUpperCase();
        if (normalized.endsWith("DB")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        return Double.parseDouble(normalized);
    }

    private static int parseBlockDumpDataOffset(JsonNode node) {
        int payloadOffset;
        if (node.has("offset_dec")) {
            payloadOffset = node.get("offset_dec").asInt();
        } else if (node.has("predicted_offset_dec")) {
            payloadOffset = node.get("predicted_offset_dec").asInt();
        } else if (node.has("offset_hex")) {
            payloadOffset = parseHex(node.get("offset_hex").asText());
        } else if (node.has("predicted_offset_hex")) {
            payloadOffset = parseHex(node.get("predicted_offset_hex").asText());
        } else {
            throw new DspLibraryException("Missing offset_dec/offset_hex in node: " + node);
        }

        int dataOffset = payloadOffset - 5;
        if (dataOffset < 0) {
            throw new DspLibraryException(
                    "Normalized block data offset became negative. payloadOffset=" + payloadOffset
            );
        }
        return dataOffset;
    }

    private static boolean crossoverNodeConfirmed(JsonNode root, JsonNode node) {
        if (!requiresStrictEvidence(root)) {
            return true;
        }
        if (node.has("status")) {
            return readEvidenceAllowed(root, node);
        }
        for (String field : List.of(
                "high_pass_frequency",
                "high_pass_slope_or_mode",
                "high_pass_mode",
                "high_pass_bypass",
                "low_pass_frequency",
                "low_pass_mode",
                "low_pass_bypass"
        )) {
            if (node.has(field) && !readEvidenceAllowed(root, node.get(field))) {
                return false;
            }
        }
        return true;
    }

    private static boolean readEvidenceAllowed(JsonNode root, JsonNode node, JsonNode... fallbacks) {
        if (!requiresStrictEvidence(root)) {
            return true;
        }
        JsonNode statusNode = node == null ? null : node.get("status");
        if (statusNode != null && statusNode.isTextual()) {
            return evidenceStatusConfirmed(statusNode.asText());
        }
        for (JsonNode fallback : fallbacks) {
            JsonNode fallbackStatus = fallback == null ? null : fallback.get("status");
            if (fallbackStatus != null && fallbackStatus.isTextual()) {
                return evidenceStatusConfirmed(fallbackStatus.asText());
            }
        }
        return false;
    }

    private static boolean requiresStrictEvidence(JsonNode root) {
        return root.path("library_type")
                .asText("")
                .toLowerCase()
                .contains("fir");
    }

    static boolean evidenceStatusConfirmed(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        for (String rejected : List.of(
                "predicted",
                "inferred",
                "derived",
                "hypothesis",
                "absent",
                "unsupported",
                "unknown",
                "not_",
                "stale",
                "partial",
                "mixed"
        )) {
            if (normalized.contains(rejected)) {
                return false;
            }
        }
        return normalized.contains("observed")
                || normalized.contains("confirmed")
                || normalized.contains("verified");
    }

    private static int parseMaxParameterBlockIndex(JsonNode root) {
        JsonNode count = root.path("parameters").path("config_dump").path("block_count_observed");
        if (count.canConvertToInt() && count.asInt() > 0) {
            return count.asInt() - 1;
        }

        JsonNode range = root.path("commands").path("0x27").path("observed_block_range");
        if (range.isTextual()) {
            String text = range.asText().trim();
            int separator = text.indexOf("..");
            if (separator >= 0) {
                return parseHex(text.substring(separator + 2).trim());
            }
        }

        return 0x1C;
    }

    private static int parseHex(String value) {
        String cleaned = value.trim();
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty()) {
            throw new DspLibraryException("Hex value is empty");
        }
        return Integer.parseInt(cleaned, 16);
    }

    private static JsonNode required(JsonNode node, String... path) {
        JsonNode current = node;
        for (String part : path) {
            current = current.path(part);
            if (current.isMissingNode() || current.isNull()) {
                throw new DspLibraryException("Missing required JSON path: " + String.join(".", path));
            }
        }
        return current;
    }

    private static String requiredText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            throw new DspLibraryException("Missing required text field: " + fieldName);
        }
        String text = field.asText();
        if (text == null || text.isBlank()) {
            throw new DspLibraryException("Empty required text field: " + fieldName);
        }
        return text;
    }
}

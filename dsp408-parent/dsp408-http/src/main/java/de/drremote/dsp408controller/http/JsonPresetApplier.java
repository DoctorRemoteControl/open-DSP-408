package de.drremote.dsp408controller.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.drremote.dsp408.api.DspService;

final class JsonPresetApplier {
    private static final List<String> INPUT_IDS = List.of("ina", "inb", "inc", "ind");
    private static final List<String> OUTPUT_IDS =
            List.of("out1", "out2", "out3", "out4", "out5", "out6", "out7", "out8");
    private static final List<String> ALL_CHANNEL_IDS =
            List.of("ina", "inb", "inc", "ind", "out1", "out2", "out3", "out4", "out5", "out6", "out7", "out8");
    private static final Set<String> SUPPORTED_SLOPES = Set.of(
            "bw6", "bs6",
            "bw12", "bs12", "lr12",
            "bw18", "bs18",
            "bw24", "bs24", "lr24",
            "bw30", "bs30",
            "bw36", "bs36", "lr36",
            "bw42", "bs42",
            "bw48", "bs48", "lr48"
    );

    PresetPlan validate(Map<String, Object> document) {
        if (document == null || document.isEmpty()) {
            throw new IllegalArgumentException("Preset JSON object is required.");
        }
        requireOnlyFields(
                document,
                Set.of("schemaVersion", "presetId", "title", "description", "delayUnit", "inputs", "outputs", "postApply"),
                "$"
        );

        String schemaVersion = requiredString(document, "schemaVersion", "$");
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "$.schemaVersion must be \"1.0\", got \"" + schemaVersion + "\".");
        }

        String presetId = requiredString(document, "presetId", "$");
        if (!presetId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "$.presetId must contain 1..64 letters, digits, dot, underscore or hyphen.");
        }

        String title = optionalString(document, "title", presetId);
        String delayUnit = optionalString(document, "delayUnit", "ms").toLowerCase(Locale.ROOT);
        if (!Set.of("ms", "m", "ft").contains(delayUnit)) {
            throw new IllegalArgumentException("$.delayUnit must be ms, m or ft.");
        }

        List<ChannelPlan> inputs = parseChannels(document.get("inputs"), true, "$.inputs");
        List<ChannelPlan> outputs = parseChannels(document.get("outputs"), false, "$.outputs");
        requireExactChannels(inputs, INPUT_IDS, "$.inputs");
        requireExactChannels(outputs, OUTPUT_IDS, "$.outputs");

        List<String> warnings = new ArrayList<>();
        Object postApplyValue = document.get("postApply");
        if (postApplyValue != null) {
            Map<String, Object> postApply = objectValue(postApplyValue, "$.postApply");
            String warning = optionalString(postApply, "warning", "");
            if (!warning.isBlank()) {
                warnings.add(warning);
            }
        }
        for (ChannelPlan output : outputs) {
            if (output.limiter() != null
                    && output.limiter().status() != null
                    && !output.limiter().status().isBlank()) {
                String warning = output.id() + " limiter status: " + output.limiter().status();
                if (!warnings.contains(warning)) {
                    warnings.add(warning);
                }
            }
        }

        return new PresetPlan(
                schemaVersion,
                presetId,
                title,
                delayUnit,
                List.copyOf(inputs),
                List.copyOf(outputs),
                List.copyOf(warnings)
        );
    }

    Map<String, Object> validationResponse(String dspId, PresetPlan plan) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("valid", true);
        response.put("dspId", dspId);
        response.put("schemaVersion", plan.schemaVersion());
        response.put("presetId", plan.presetId());
        response.put("title", plan.title());
        response.put("inputChannels", plan.inputs().size());
        response.put("outputChannels", plan.outputs().size());
        response.put("selectedForUnmute", selectedForUnmute(plan));
        response.put("warnings", plan.warnings());
        return response;
    }

    Map<String, Object> apply(DspService dsp, PresetPlan plan, boolean unmuteSelected, boolean refresh)
            throws PresetApplyException {
        if (!dsp.isConnected()) {
            throw new IllegalStateException("DSP " + dsp.dspId() + " is not connected.");
        }

        List<String> completed = new ArrayList<>();

        synchronized (dsp) {
            try {
                for (String channelId : ALL_CHANNEL_IDS) {
                    runOperation(completed, "mute " + channelId, () -> dsp.mute(channelId));
                }

                runOperation(
                        completed,
                        "set delay unit " + plan.delayUnit(),
                        () -> dsp.setDelayUnit(plan.delayUnit())
                );

                for (ChannelPlan input : plan.inputs()) {
                    applyBaseChannel(dsp, input, completed);
                }

                for (ChannelPlan output : plan.outputs()) {
                    applyOutput(dsp, output, completed);
                }

                if (refresh) {
                    runOperation(completed, "scan parameter blocks", dsp::scanBlocks);
                }

                List<String> unmuted = new ArrayList<>();
                if (unmuteSelected) {
                    for (ChannelPlan input : plan.inputs()) {
                        if (input.unmuteAfterApply()) {
                            runOperation(completed, "unmute " + input.id(), () -> dsp.unmute(input.id()));
                            unmuted.add(input.id());
                        }
                    }
                    for (ChannelPlan output : plan.outputs()) {
                        if (output.unmuteAfterApply()) {
                            runOperation(completed, "unmute " + output.id(), () -> dsp.unmute(output.id()));
                            unmuted.add(output.id());
                        }
                    }
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", true);
                response.put("applied", true);
                response.put("dspId", dsp.dspId());
                response.put("presetId", plan.presetId());
                response.put("title", plan.title());
                response.put("operationsApplied", completed.size());
                response.put("lastOperation", completed.isEmpty() ? "" : completed.get(completed.size() - 1));
                response.put("refreshed", refresh);
                response.put("unmuteSelectedRequested", unmuteSelected);
                response.put("unmutedChannels", List.copyOf(unmuted));
                response.put("allChannelsRemainMuted", unmuted.isEmpty());
                response.put("warnings", plan.warnings());
                return response;
            } catch (StepFailure e) {
                List<String> emergencyMuteFailures = emergencyMuteAll(dsp);
                throw new PresetApplyException(
                        plan.presetId(),
                        e.operation(),
                        List.copyOf(completed),
                        emergencyMuteFailures,
                        e.getCause()
                );
            }
        }
    }

    private void applyBaseChannel(DspService dsp, ChannelPlan channel, List<String> completed) throws StepFailure {
        runOperation(completed, "set name " + channel.id(),
                () -> dsp.setChannelName(channel.id(), channel.name()));
        runOperation(completed, "set gain " + channel.id(),
                () -> dsp.setGain(channel.id(), channel.gainDb()));
        runOperation(completed, "set phase " + channel.id(),
                () -> dsp.setPhase(channel.id(), channel.phaseInverted()));
        runOperation(completed, "set delay " + channel.id(),
                () -> dsp.setDelay(channel.id(), channel.delayMs()));
    }

    private void applyOutput(DspService dsp, ChannelPlan output, List<String> completed) throws StepFailure {
        applyBaseChannel(dsp, output, completed);

        runOperation(completed, "set processing mode " + output.id(),
                () -> dsp.setFirProcessingMode(output.id(), output.processingMode()));

        runOperation(completed, "set matrix routes " + output.id(),
                () -> dsp.setMatrixRoutes(
                        output.id(),
                        output.routes().stream().map(RoutePlan::input).toList()
                ));
        for (RoutePlan route : output.routes()) {
            runOperation(completed, "set matrix gain " + output.id() + " <- " + route.input(),
                    () -> dsp.setMatrixCrosspointGain(
                            output.id(),
                            route.input(),
                            route.crosspointGainDb()
                    ));
        }

        if (output.crossover() != null) {
            FilterPlan highPass = output.crossover().highPass();
            runOperation(completed, "set high-pass " + output.id(),
                    () -> dsp.setCrossoverHighPass(
                            output.id(),
                            highPass.hz(),
                            highPass.slope(),
                            !highPass.enabled()
                    ));

            FilterPlan lowPass = output.crossover().lowPass();
            runOperation(completed, "set low-pass " + output.id(),
                    () -> dsp.setCrossoverLowPass(
                            output.id(),
                            lowPass.hz(),
                            lowPass.slope(),
                            !lowPass.enabled()
                    ));
        }

        if (output.limiter() != null && output.limiter().enabled()) {
            runOperation(completed, "set limiter " + output.id(),
                    () -> dsp.setLimiter(
                            output.id(),
                            output.limiter().thresholdDb(),
                            output.limiter().attackMs(),
                            output.limiter().releaseMs()
                    ));
        }
    }

    private void runOperation(List<String> completed, String label, DspOperation operation) throws StepFailure {
        try {
            operation.run();
            completed.add(label);
        } catch (Exception e) {
            throw new StepFailure(label, e);
        }
    }

    private List<String> emergencyMuteAll(DspService dsp) {
        List<String> failures = new ArrayList<>();
        for (String channelId : ALL_CHANNEL_IDS) {
            try {
                dsp.mute(channelId);
            } catch (Exception muteFailure) {
                failures.add(channelId + ": " + safeMessage(muteFailure));
            }
        }
        return List.copyOf(failures);
    }

    private List<ChannelPlan> parseChannels(Object value, boolean inputs, String path) {
        List<Object> items = listValue(value, path);
        List<ChannelPlan> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (int index = 0; index < items.size(); index++) {
            String itemPath = path + "[" + index + "]";
            Map<String, Object> item = objectValue(items.get(index), itemPath);
            requireOnlyFields(
                    item,
                    inputs
                            ? Set.of("id", "name", "gainDb", "phaseInverted", "delayMs", "unmuteAfterApply")
                            : Set.of(
                                    "id", "name", "gainDb", "phaseInverted", "delayMs", "unmuteAfterApply",
                                    "processingMode", "route", "routes", "crossover", "limiter"
                            ),
                    itemPath
            );
            String id = requiredString(item, "id", itemPath).toLowerCase(Locale.ROOT);
            Set<String> allowedIds = inputs ? Set.copyOf(INPUT_IDS) : Set.copyOf(OUTPUT_IDS);
            if (!allowedIds.contains(id)) {
                throw new IllegalArgumentException(itemPath + ".id is not a valid "
                        + (inputs ? "input" : "output") + " channel: " + id + ".");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException(path + " contains duplicate channel " + id + ".");
            }

            String name = requiredString(item, "name", itemPath);
            validateAsciiName(name, itemPath + ".name");
            double gainDb = rangedDouble(item, "gainDb", -60.0, 12.0, itemPath);
            boolean phaseInverted = requiredBoolean(item, "phaseInverted", itemPath);
            double delayMs = rangedDouble(item, "delayMs", 0.0, 680.0, itemPath);
            boolean unmuteAfterApply = optionalBoolean(item, "unmuteAfterApply", false, itemPath);

            if (inputs) {
                result.add(new ChannelPlan(
                        id, name, gainDb, phaseInverted, delayMs, unmuteAfterApply,
                        null, null, null, null
                ));
                continue;
            }

            String processingMode = requiredString(item, "processingMode", itemPath).toLowerCase(Locale.ROOT);
            if (!Set.of("iir", "fir").contains(processingMode)) {
                throw new IllegalArgumentException(itemPath + ".processingMode must be iir or fir.");
            }

            List<RoutePlan> routes = parseRoutes(item, itemPath);
            CrossoverPlan crossover = parseCrossover(item.get("crossover"), itemPath + ".crossover");
            LimiterPlan limiter = parseLimiter(item.get("limiter"), itemPath + ".limiter");

            result.add(new ChannelPlan(
                    id, name, gainDb, phaseInverted, delayMs, unmuteAfterApply,
                    processingMode, routes, crossover, limiter
            ));
        }

        return result;
    }

    private RoutePlan parseRoute(Object value, String path) {
        if (value == null) {
            return null;
        }
        Map<String, Object> route = objectValue(value, path);
        requireOnlyFields(route, Set.of("input", "crosspointGainDb"), path);
        String input = requiredString(route, "input", path).toLowerCase(Locale.ROOT);
        if (!INPUT_IDS.contains(input)) {
            throw new IllegalArgumentException(path + ".input is not a valid input channel: " + input + ".");
        }
        double gainDb = rangedDouble(route, "crosspointGainDb", -60.0, 0.0, path);
        return new RoutePlan(input, gainDb);
    }

    private List<RoutePlan> parseRoutes(Map<String, Object> item, String path) {
        if (item.containsKey("route") && item.containsKey("routes")) {
            throw new IllegalArgumentException(path + " must not contain both route and routes.");
        }
        if (item.containsKey("route")) {
            RoutePlan route = parseRoute(item.get("route"), path + ".route");
            return route == null ? List.of() : List.of(route);
        }
        if (!item.containsKey("routes")) {
            return List.of();
        }

        List<Object> values = listValue(item.get("routes"), path + ".routes");
        if (values.size() > INPUT_IDS.size()) {
            throw new IllegalArgumentException(path + ".routes must contain at most four inputs.");
        }
        List<RoutePlan> routes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            RoutePlan route = parseRoute(values.get(index), path + ".routes[" + index + "]");
            if (!seen.add(route.input())) {
                throw new IllegalArgumentException(path + ".routes contains duplicate input " + route.input() + ".");
            }
            routes.add(route);
        }
        return List.copyOf(routes);
    }

    private CrossoverPlan parseCrossover(Object value, String path) {
        if (value == null) {
            return null;
        }
        Map<String, Object> crossover = objectValue(value, path);
        requireOnlyFields(crossover, Set.of("highPass", "lowPass"), path);
        FilterPlan highPass = parseFilter(crossover.get("highPass"), path + ".highPass");
        FilterPlan lowPass = parseFilter(crossover.get("lowPass"), path + ".lowPass");
        return new CrossoverPlan(highPass, lowPass);
    }

    private FilterPlan parseFilter(Object value, String path) {
        Map<String, Object> filter = objectValue(value, path);
        requireOnlyFields(filter, Set.of("enabled", "hz", "slope"), path);
        boolean enabled = requiredBoolean(filter, "enabled", path);
        double hz = rangedDouble(filter, "hz", 19.7, 20160.0, path);
        String slope = requiredString(filter, "slope", path);
        if (!SUPPORTED_SLOPES.contains(normalizeSlope(slope))) {
            throw new IllegalArgumentException(path + ".slope is unsupported: " + slope + ".");
        }
        return new FilterPlan(enabled, hz, slope);
    }

    private LimiterPlan parseLimiter(Object value, String path) {
        if (value == null) {
            return null;
        }
        Map<String, Object> limiter = objectValue(value, path);
        requireOnlyFields(limiter, Set.of("enabled", "thresholdDb", "attackMs", "releaseMs", "status"), path);
        boolean enabled = requiredBoolean(limiter, "enabled", path);
        double thresholdDb = rangedDouble(limiter, "thresholdDb", -90.0, 20.0, path);
        double attackMs = rangedDouble(limiter, "attackMs", 1.0, 999.0, path);
        double releaseMs = rangedDouble(limiter, "releaseMs", 10.0, 3000.0, path);
        String status = optionalString(limiter, "status", "");
        return new LimiterPlan(enabled, thresholdDb, attackMs, releaseMs, status);
    }

    private void requireExactChannels(List<ChannelPlan> channels, List<String> expected, String path) {
        Set<String> actual = new LinkedHashSet<>();
        for (ChannelPlan channel : channels) {
            actual.add(channel.id());
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(path + " is missing channels: " + String.join(", ", missing) + ".");
        }
        if (channels.size() != expected.size()) {
            throw new IllegalArgumentException(path + " must contain exactly " + expected.size() + " channels.");
        }
    }

    private List<String> selectedForUnmute(PresetPlan plan) {
        List<String> selected = new ArrayList<>();
        for (ChannelPlan input : plan.inputs()) {
            if (input.unmuteAfterApply()) {
                selected.add(input.id());
            }
        }
        for (ChannelPlan output : plan.outputs()) {
            if (output.unmuteAfterApply()) {
                selected.add(output.id());
            }
        }
        return List.copyOf(selected);
    }

    private Map<String, Object> objectValue(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(path + " must be a JSON object.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<Object> listValue(Object value, String path) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(path + " must be a JSON array.");
        }
        return new ArrayList<>(raw);
    }

    private String requiredString(Map<String, Object> object, String field, String path) {
        Object value = object.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(path + "." + field + " is required.");
        }
        return String.valueOf(value).trim();
    }

    private String optionalString(Map<String, Object> object, String field, String fallback) {
        Object value = object.get(field);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private boolean requiredBoolean(Map<String, Object> object, String field, String path) {
        if (!object.containsKey(field)) {
            throw new IllegalArgumentException(path + "." + field + " is required.");
        }
        return booleanValue(object.get(field), path + "." + field);
    }

    private boolean optionalBoolean(Map<String, Object> object, String field, boolean fallback, String path) {
        if (!object.containsKey(field)) {
            return fallback;
        }
        return booleanValue(object.get(field), path + "." + field);
    }

    private boolean booleanValue(Object value, String path) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(path + " must be a boolean.");
        };
    }

    private double rangedDouble(
            Map<String, Object> object,
            String field,
            double minimum,
            double maximum,
            String path
    ) {
        if (!object.containsKey(field)) {
            throw new IllegalArgumentException(path + "." + field + " is required.");
        }
        Object value = object.get(field);
        double number;
        try {
            number = value instanceof Number numeric
                    ? numeric.doubleValue()
                    : Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + "." + field + " must be numeric.");
        }
        if (!Double.isFinite(number) || number < minimum || number > maximum) {
            throw new IllegalArgumentException(path + "." + field + " must be between "
                    + minimum + " and " + maximum + ".");
        }
        return number;
    }

    private void validateAsciiName(String name, String path) {
        if (name.length() > 8) {
            throw new IllegalArgumentException(path + " must contain at most 8 ASCII characters.");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException(path + " must contain printable ASCII characters only.");
            }
        }
    }

    private void requireOnlyFields(Map<String, Object> object, Set<String> allowed, String path) {
        Set<String> unsupported = new LinkedHashSet<>(object.keySet());
        unsupported.removeAll(allowed);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    path + " contains unsupported fields: " + String.join(", ", unsupported) + ".");
        }
    }

    private String normalizeSlope(String slope) {
        return slope.trim().toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    record PresetPlan(
            String schemaVersion,
            String presetId,
            String title,
            String delayUnit,
            List<ChannelPlan> inputs,
            List<ChannelPlan> outputs,
            List<String> warnings
    ) {
    }

    record ChannelPlan(
            String id,
            String name,
            double gainDb,
            boolean phaseInverted,
            double delayMs,
            boolean unmuteAfterApply,
            String processingMode,
            List<RoutePlan> routes,
            CrossoverPlan crossover,
            LimiterPlan limiter
    ) {
        ChannelPlan {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    record RoutePlan(String input, double crosspointGainDb) {
    }

    record CrossoverPlan(FilterPlan highPass, FilterPlan lowPass) {
    }

    record FilterPlan(boolean enabled, double hz, String slope) {
    }

    record LimiterPlan(
            boolean enabled,
            double thresholdDb,
            double attackMs,
            double releaseMs,
            String status
    ) {
    }

    @FunctionalInterface
    private interface DspOperation {
        void run() throws Exception;
    }

    private static final class StepFailure extends Exception {
        private final String operation;

        StepFailure(String operation, Throwable cause) {
            super(operation, cause);
            this.operation = operation;
        }

        String operation() {
            return operation;
        }
    }

    static final class PresetApplyException extends Exception {
        private final String presetId;
        private final String failedOperation;
        private final List<String> completedOperations;
        private final List<String> emergencyMuteFailures;

        PresetApplyException(
                String presetId,
                String failedOperation,
                List<String> completedOperations,
                List<String> emergencyMuteFailures,
                Throwable cause
        ) {
            super("Preset apply failed during " + failedOperation + ".", cause);
            this.presetId = presetId;
            this.failedOperation = failedOperation;
            this.completedOperations = completedOperations;
            this.emergencyMuteFailures = emergencyMuteFailures;
        }

        Map<String, Object> response(String dspId) {
            int status = httpStatus();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("error", status == 409 ? "unsupported_operation" : "preset_apply_failed");
            response.put("message", getMessage());
            response.put("status", status);
            response.put("dspId", dspId);
            response.put("presetId", presetId);
            response.put("failedOperation", failedOperation);
            response.put("completedOperationCount", completedOperations.size());
            response.put("emergencyMuteAttempted", true);
            response.put("emergencyMuteFailures", emergencyMuteFailures);
            response.put("allChannelsEmergencyMuted", emergencyMuteFailures.isEmpty());
            return response;
        }

        int httpStatus() {
            return getCause() instanceof UnsupportedOperationException ? 409 : 500;
        }
    }
}

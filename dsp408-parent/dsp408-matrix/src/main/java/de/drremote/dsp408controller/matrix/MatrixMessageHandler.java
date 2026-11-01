package de.drremote.dsp408controller.matrix;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;

import de.drremote.dsp408.api.DspService;

public final class MatrixMessageHandler {
    private static final String EVT_MACHINE_COMMAND = "de.drremote.dsp408.command";
    private static final String EVT_MACHINE_RESPONSE = "de.drremote.dsp408.response";

    private final MatrixRuntimeConfig config;
    private final DspService service;
    private final MatrixApiClient api;
    private final ExecutorService dspExecutor;
    private final String botUserId;
    private final LimiterWarningMonitor warningMonitor;
    private final Consumer<String> log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MatrixDspCommandDispatcher dispatcher;

    public MatrixMessageHandler(MatrixRuntimeConfig config,
                                DspService service,
                                MatrixApiClient api,
                                ExecutorService dspExecutor,
                                String botUserId,
                                LimiterWarningMonitor warningMonitor,
                                Consumer<String> log) {
        this.config = config;
        this.service = service;
        this.api = api;
        this.dspExecutor = dspExecutor;
        this.botUserId = botUserId;
        this.warningMonitor = warningMonitor;
        this.log = log;
        this.dispatcher = new MatrixDspCommandDispatcher(service);
    }

    public void handleRoomEvents(JsonNode joinedRooms, String roomId, String mode) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }

        JsonNode roomNode = joinedRooms.path(roomId);
        if (roomNode.isMissingNode()) {
            return;
        }

        JsonNode events = roomNode.path("timeline").path("events");
        if (!events.isArray()) {
            return;
        }

        if ("machine".equals(mode)) {
            handleMachineEvents(events, roomId);
            return;
        }

        for (JsonNode event : events) {
            String type = event.path("type").asText();
            if (!"m.room.message".equals(type)) {
                continue;
            }

            String sender = event.path("sender").asText();
            if (sender == null || sender.isBlank() || sender.equals(botUserId)) {
                continue;
            }

            boolean isAdmin = config.isAdmin(sender);

            JsonNode content = event.path("content");
            String msgtype = content.path("msgtype").asText();
            String body = content.path("body").asText();

            if (!"m.text".equals(msgtype) || body == null || body.isBlank()) {
                continue;
            }

            String commandText = body.trim();
            String targetRoomId = roomId;

            if ("control".equals(mode)) {
                if (!config.isUserAllowed(sender)) {
                    continue;
                }

                String lowerBody = commandText.toLowerCase(Locale.ROOT);
                if (!lowerBody.startsWith("!dsp")) {
                    continue;
                }

                dspExecutor.submit(() -> {
                    try {
                        log.accept("[control] " + sender + " -> " + commandText);
                        String response = dispatcher.execute(commandText, isAdmin);
                        if (response != null && !response.isBlank()) {
                            api.sendText(targetRoomId, response, response.contains("\n") || response.contains("\r"));
                        }
                    } catch (Exception e) {
                        sendError(targetRoomId, e);
                    }
                });
                continue;
            }

            if ("volume".equals(mode)) {
                if (!allowsVolumeCommand(
                        config.volumeRoomMembersAllowed,
                        config.isUserAllowed(sender)
                )) {
                    continue;
                }

                dspExecutor.submit(() -> {
                    try {
                        log.accept("[volume] " + sender + " -> " + commandText);
                        String response = service.executeVolumeRoom(commandText);
                        if (response != null && !response.isBlank()) {
                            api.sendText(targetRoomId, response, response.contains("\n") || response.contains("\r"));
                        }
                    } catch (Exception e) {
                        sendError(targetRoomId, e);
                    }
                });
                continue;
            }

            if ("warning".equals(mode)) {
                if (!config.isUserAllowed(sender) || warningMonitor == null) {
                    continue;
                }

                dspExecutor.submit(() -> {
                    try {
                        String response = warningMonitor.handleRoomCommand(commandText);
                        if (response != null && !response.isBlank()) {
                            log.accept("[warning] " + sender + " -> " + commandText);
                            api.sendText(targetRoomId, response, response.contains("\n") || response.contains("\r"));
                        }
                    } catch (Exception e) {
                        sendError(targetRoomId, e);
                    }
                });
            }
        }
    }

    static boolean allowsVolumeCommand(boolean roomMembersAllowed, boolean explicitlyAllowed) {
        return roomMembersAllowed || explicitlyAllowed;
    }

    private void handleMachineEvents(JsonNode events, String roomId) {
        for (JsonNode event : events) {
            String type = event.path("type").asText();
            if (!EVT_MACHINE_COMMAND.equals(type)) {
                continue;
            }

            String sender = event.path("sender").asText();
            if (sender == null || sender.isBlank() || sender.equals(botUserId)) {
                continue;
            }

            if (!config.isMachineUser(sender)) {
                continue;
            }

            JsonNode content = event.path("content");

            dspExecutor.submit(() -> {
                try {
                    handleMachineCommand(roomId, sender, content);
                } catch (Exception e) {
                    try {
                        ObjectNode response = mapper.createObjectNode();
                        response.put("apiVersion", "1.0");
                        response.put("requestId", content.path("requestId").asText(""));
                        response.put("ok", false);
                        String dspId = machineDspId(content);
                        if (dspId != null) {
                            response.put("dspId", dspId);
                        }

                        ObjectNode error = response.putObject("error");
                        error.put("message", e.getMessage());

                        api.sendEvent(roomId, EVT_MACHINE_RESPONSE, response);
                    } catch (Exception sendError) {
                        log.accept("Machine response error: " + sendError.getMessage());
                    }
                }
            });
        }
    }

    private void handleMachineCommand(String roomId, String sender, JsonNode content) throws Exception {
        String requestId = content.path("requestId").asText();
        String command = content.path("command").asText();
        JsonNode args = content.path("args");
        String dspId = machineDspId(content);

        String mappedCommand = mapMachineCommand(command, args);
        String resultText = dspId == null
                ? dispatcher.execute(mappedCommand, true)
                : dispatcher.executeForDsp(dspId, mappedCommand, true);

        ObjectNode response = mapper.createObjectNode();
        response.put("apiVersion", "1.0");
        response.put("requestId", requestId);
        response.put("ok", true);
        response.put("command", command);
        response.put("mappedCommand", mappedCommand);
        if (dspId != null) {
            response.put("dspId", dspId);
        }

        ObjectNode result = response.putObject("result");
        result.put("text", resultText == null ? "" : resultText);

        api.sendEvent(roomId, EVT_MACHINE_RESPONSE, response);
    }

    static String mapMachineCommand(String command, JsonNode args) {
        return switch (command) {
            case "connection.connect" -> "!dsp connect";
            case "connection.disconnect" -> "!dsp disconnect";
            case "connection.reconnect" -> "!dsp reconnect";

            case "dsp.instances.list" -> "!dsp dsps";

            case "device.info.get" -> "!dsp deviceinfo";
            case "state.get" -> "!dsp state";

            case "blocks.scan" -> "!dsp scanblocks";
            case "block.read" -> "!dsp readblock " + requireText(args, "blockIndex");

            case "channel.get" -> "!dsp get " + requireText(args, "channelId");
            case "channel.name.set" ->
                    "!dsp name " + requireText(args, "channelId") + " " + requireText(args, "name");

            case "channel.gain.set" ->
                    "!dsp gain " + requireText(args, "channelId") + " " + requireNumber(args, "db");

            case "channel.mute.set" -> args.path("muted").asBoolean()
                    ? "!dsp mute " + requireText(args, "channelId")
                    : "!dsp unmute " + requireText(args, "channelId");

            case "channel.phase.set" ->
                    "!dsp phase " + requireText(args, "channelId") + " " + requireText(args, "phase");

            case "channel.delay.set" ->
                    "!dsp delay " + requireText(args, "channelId") + " " + requireNumber(args, "ms");

            case "delay.unit.set" -> "!dsp delayunit " + requireText(args, "unit");

            case "preset.load" -> args.has("index")
                    ? "!dsp loadpreset " + requireNumber(args, "index")
                    : "!dsp loadpreset " + requireText(args, "slot");

            case "preset.name.read" -> "!dsp readpresetname " + requireNumber(args, "index");

            case "meters.read" -> "!dsp meters";
            case "meters.get" -> "!dsp meterstate";
            case "meter.get" -> "!dsp meter " + requireText(args, "channelId");

            case "matrix.route.set" ->
                    "!dsp route " + requireText(args, "output") + " " + requireText(args, "input");

            case "matrix.crosspoint.gain.set" ->
                    "!dsp xgain " + requireText(args, "output")
                            + " " + requireText(args, "input")
                            + " " + requireNumber(args, "db");

            case "crossover.hp.set" ->
                    "!dsp xhpset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "hz")
                            + " " + requireText(args, "slope")
                            + " " + requireOnOff(args, "bypass");

            case "crossover.hp.frequency.set" ->
                    "!dsp xhpfreq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "hz");

            case "crossover.hp.slope.set" ->
                    "!dsp xhpslope " + requireText(args, "channelId")
                            + " " + requireText(args, "slope");

            case "crossover.hp.bypass.set" ->
                    "!dsp xhpbypass " + requireText(args, "channelId")
                            + " " + requireOnOff(args, "bypass");

            case "crossover.lp.set" ->
                    "!dsp xlpset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "hz")
                            + " " + requireText(args, "slope")
                            + " " + requireOnOff(args, "bypass");

            case "crossover.lp.frequency.set" ->
                    "!dsp xlpfreq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "hz");

            case "crossover.lp.slope.set" ->
                    "!dsp xlpslope " + requireText(args, "channelId")
                            + " " + requireText(args, "slope");

            case "crossover.lp.bypass.set" ->
                    "!dsp xlpbypass " + requireText(args, "channelId")
                            + " " + requireOnOff(args, "bypass");

            case "fir.mode.set" ->
                    "!dsp firmode " + requireAnyText(args, "outputId", "output", "channelId")
                            + " " + requireText(args, "mode");

            case "fir.generator.set", "fir.generate.set" ->
                    "!dsp firgen " + requireAnyText(args, "outputId", "output", "channelId")
                            + " " + requireText(args, "type")
                            + " " + requireText(args, "window")
                            + " " + requireAnyNumber(args, "hpHz", "highPassFrequencyHz", "highPassHz", "hp")
                            + " " + requireAnyNumber(args, "lpHz", "lowPassFrequencyHz", "lowPassHz", "lp")
                            + " " + requireNumber(args, "taps");

            case "fir.upload", "fir.upload.set" ->
                    "!dsp firupload " + requireAnyText(args, "channelId", "outputId", "output")
                            + " " + requireAnyText(args, "name", "filterName")
                            + " " + requireCoefficients(args);

            case "output.peq.set", "channel.peq.set" ->
                    "!dsp opeqset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireText(args, "type")
                            + " " + requireNumber(args, "hz")
                            + " " + requireNumber(args, "q")
                            + " " + requireNumber(args, "gainDb");

            case "output.peq.frequency.set", "channel.peq.frequency.set" ->
                    "!dsp opeqfreq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "hz");

            case "output.peq.q.set" ->
                    "!dsp opeqq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "q");

            case "output.peq.qraw.set", "channel.peq.qraw.set" ->
                    "!dsp opeqqraw " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "raw");

            case "output.peq.gain.set" ->
                    "!dsp opeqgain " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "gainDb");

            case "output.peq.gaincode.set", "channel.peq.gaincode.set" ->
                    "!dsp opeqgcode " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireText(args, "code");

            case "output.peq.type.set" ->
                    "!dsp opeqtype " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireText(args, "type");

            case "output.peq.bypass.set", "channel.peq.bypass.set" ->
                    "!dsp opeqbypass " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireOnOff(args, "bypass");

            case "peq.bypass_all.set", "peq.bypass-all.set" ->
                    "!dsp peqallbypass " + requireOnOff(args, "bypass");

            case "input.peq.set" ->
                    "!dsp ipeqset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireText(args, "type")
                            + " " + requireNumber(args, "hz")
                            + " " + requireNumber(args, "q")
                            + " " + requireNumber(args, "gainDb")
                            + " " + requireOnOff(args, "bypass");

            case "input.peq.frequency.set" ->
                    "!dsp ipeqfreq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "hz");

            case "input.peq.q.set" ->
                    "!dsp ipeqq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "q");

            case "input.peq.gain.set" ->
                    "!dsp ipeqgain " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireNumber(args, "gainDb");

            case "input.peq.type.set" ->
                    "!dsp ipeqtype " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireText(args, "type");

            case "input.peq.bypass.set" ->
                    "!dsp ipeqbypass " + requireText(args, "channelId")
                            + " " + requireNumber(args, "peqIndex")
                            + " " + requireOnOff(args, "bypass");

            case "input.geq.gain.set" ->
                    "!dsp igeq " + requireText(args, "channelId")
                            + " " + requireNumber(args, "bandIndex")
                            + " " + requireNumber(args, "gainDb");

            case "input.gate.set" ->
                    "!dsp gateset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "thresholdDb")
                            + " " + requireNumber(args, "holdMs")
                            + " " + requireNumber(args, "attackMs")
                            + " " + requireNumber(args, "releaseMs");

            case "input.gate.threshold.set" ->
                    "!dsp gatethreshold " + requireText(args, "channelId")
                            + " " + requireNumber(args, "thresholdDb");

            case "input.gate.hold.set" ->
                    "!dsp gatehold " + requireText(args, "channelId")
                            + " " + requireNumber(args, "holdMs");

            case "input.gate.attack.set" ->
                    "!dsp gateattack " + requireText(args, "channelId")
                            + " " + requireNumber(args, "attackMs");

            case "input.gate.release.set" ->
                    "!dsp gaterelease " + requireText(args, "channelId")
                            + " " + requireNumber(args, "releaseMs");

            case "compressor.set" ->
                    "!dsp compset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "thresholdDb")
                            + " " + requireText(args, "ratio")
                            + " " + requireNumber(args, "attackMs")
                            + " " + requireNumber(args, "releaseMs")
                            + " " + requireNumber(args, "kneeDb");

            case "limiter.set" ->
                    "!dsp limitset " + requireText(args, "channelId")
                            + " " + requireNumber(args, "thresholdDb")
                            + " " + requireNumber(args, "attackMs")
                            + " " + requireNumber(args, "releaseMs");

            case "testtone.source.set" -> "!dsp tonesource " + requireText(args, "source");

            case "testtone.sine.set" -> args.has("raw")
                    ? "!dsp toneraw " + requireNumber(args, "raw")
                    : "!dsp tonefreq " + requireNumber(args, "hz");

            case "testtone.off" -> "!dsp toneoff";

            case "volume.command" -> "!dsp volume " + requireText(args, "text");

            default -> throw new IllegalArgumentException("Unsupported machine command: " + command);
        };
    }

    private static String machineDspId(JsonNode content) {
        String dspId = optionalText(content, "dspId");
        if (dspId != null) {
            return dspId;
        }
        return optionalText(content.path("args"), "dspId");
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        String value = node.get(field).asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String requireText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.trim();
    }

    private static String requireAnyText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = optionalText(node, field);
            if (value != null) {
                return value;
            }
        }
        throw new IllegalArgumentException("Missing field: " + String.join("|", fields));
    }

    private static String requireNumber(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return node.get(field).asText();
    }

    private static String requireAnyNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node != null && node.has(field)) {
                return node.get(field).asText();
            }
        }
        throw new IllegalArgumentException("Missing field: " + String.join("|", fields));
    }

    private static String requireCoefficients(JsonNode node) {
        JsonNode value = node.path("coefficients");
        if (value.isMissingNode() || value.isNull()) {
            value = node.path("coefficientsText");
        }
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Missing field: coefficients");
        }
        if (value.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode item : value) {
                if (!item.isNumber() && !item.isTextual()) {
                    throw new IllegalArgumentException("FIR coefficients must be numbers.");
                }
                if (!out.isEmpty()) {
                    out.append(',');
                }
                out.append(item.asText());
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("FIR coefficients are required.");
            }
            return out.toString();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("FIR coefficients are required.");
        }
        return text.trim();
    }

    private static String requireOnOff(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        JsonNode value = node.get(field);
        if (value.isBoolean()) {
            return value.asBoolean() ? "on" : "off";
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return text.trim();
    }

    private void sendError(String roomId, Exception e) {
        try {
            api.sendText(roomId, "Error: " + e.getMessage(), false);
        } catch (Exception sendError) {
            log.accept("Response error: " + sendError.getMessage());
        }
    }
}

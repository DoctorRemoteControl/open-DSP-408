package de.drremote.dsp408controller.http;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;

/** Routes FIR, crossover, PEQ and GEQ write endpoints. */
final class DspFilterPostHandler {
    private final DspHttpRequestParser requestParser;
    private final DspHttpResponses responses;

    DspFilterPostHandler(DspHttpRequestParser requestParser, DspHttpResponses responses) {
        this.requestParser = requestParser;
        this.responses = responses;
    }

    boolean handle(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (path.equals("/peq/bypass-all")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            boolean bypass = requestParser.requireBoolean(body, "bypass");
            responses.writeJson(response, 200, Map.of(
                    "bypass", bypass,
                    "count", 104,
                    "writes", dsp.setAllPeqBypass(bypass)
            ));
            return true;
        }

        if (path.equals("/fir/mode")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setFirProcessingMode(
                    requestParser.requireString(body, "output"),
                    requestParser.requireString(body, "mode")
            ));
            return true;
        }

        if (path.equals("/fir/generator")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setFirGenerator(
                    requestParser.requireString(body, "output"),
                    requestParser.requireString(body, "type"),
                    requestParser.requireString(body, "window"),
                    requestParser.requireDoubleAlias(body, "highPassFrequencyHz", "hpHz"),
                    requestParser.requireDoubleAlias(body, "lowPassFrequencyHz", "lpHz"),
                    requestParser.requireInt(body, "taps")
            ));
            return true;
        }

        if (path.equals("/fir/upload")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.uploadExternalFir(
                    requestParser.requireString(body, "channel"),
                    requestParser.requireString(body, "name"),
                    requestParser.requireDoubleList(body, "coefficients")
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.contains("/crossover/")) {
            if (handleCrossover(path, dsp, request, response)) {
                return true;
            }
        }

        if (path.startsWith("/channels/")
                && path.contains("/peq/")
                && !path.contains("/input-peq/")) {
            if (handleOutputPeq(path, dsp, request, response)) {
                return true;
            }
        }

        if (path.startsWith("/channels/") && path.contains("/peq/") && path.endsWith("/frequency")) {
            String[] parts = path.split("/");
            requirePathParts(parts, 6, "Invalid PEQ path.");
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setOutputPeqFrequency(
                    parts[2],
                    Integer.parseInt(parts[4]),
                    requestParser.requireDouble(body, "hz")
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.contains("/peq/") && path.endsWith("/q/raw")) {
            String[] parts = path.split("/");
            requirePathParts(parts, 7, "Invalid PEQ path.");
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setOutputPeqQRaw(
                    parts[2],
                    Integer.parseInt(parts[4]),
                    requestParser.requireInt(body, "raw")
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.contains("/peq/") && path.endsWith("/gain/code")) {
            String[] parts = path.split("/");
            requirePathParts(parts, 7, "Invalid PEQ path.");
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setOutputPeqGainCode(
                    parts[2],
                    Integer.parseInt(parts[4]),
                    requestParser.requireInt(body, "code")
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.contains("/input-peq/")) {
            if (handleInputPeq(path, dsp, request, response)) {
                return true;
            }
        }

        if (path.startsWith("/channels/") && path.contains("/geq/") && path.endsWith("/gain")) {
            String[] parts = path.split("/");
            requirePathParts(parts, 6, "Invalid GEQ path.");
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setInputGeq(
                    parts[2],
                    Integer.parseInt(parts[4]),
                    requestParser.requireDouble(body, "gainDb")
            ));
            return true;
        }

        return false;
    }

    private boolean handleCrossover(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        String[] parts = path.split("/");
        requirePathParts(parts, 5, "Invalid crossover path.");
        String channelId = parts[2];
        String filter = parts[4];
        String field = parts.length > 5 ? parts[5] : "";
        Map<String, Object> body = requestParser.readJsonBody(request);
        boolean highPass = "hp".equalsIgnoreCase(filter) || "high-pass".equalsIgnoreCase(filter);

        if (field.isBlank()) {
            double hz = requestParser.requireDouble(body, "hz");
            String slope = requestParser.stringValue(body.get("slope"));
            boolean bypass = requestParser.requireBoolean(body, "bypass");
            responses.writeJson(response, 200, highPass
                    ? dsp.setCrossoverHighPass(channelId, hz, slope, bypass)
                    : dsp.setCrossoverLowPass(channelId, hz, slope, bypass));
            return true;
        }
        if ("frequency".equalsIgnoreCase(field)) {
            double hz = requestParser.requireDouble(body, "hz");
            responses.writeJson(response, 200, highPass
                    ? dsp.setCrossoverHighPassFrequency(channelId, hz)
                    : dsp.setCrossoverLowPassFrequency(channelId, hz));
            return true;
        }
        if ("slope".equalsIgnoreCase(field)) {
            String slope = requestParser.requireString(body, "slope");
            responses.writeJson(response, 200, highPass
                    ? dsp.setCrossoverHighPassSlope(channelId, slope)
                    : dsp.setCrossoverLowPassSlope(channelId, slope));
            return true;
        }
        if ("bypass".equalsIgnoreCase(field)) {
            boolean bypass = requestParser.requireBoolean(body, "bypass");
            responses.writeJson(response, 200, highPass
                    ? dsp.setCrossoverHighPassBypass(channelId, bypass)
                    : dsp.setCrossoverLowPassBypass(channelId, bypass));
            return true;
        }
        return false;
    }

    private boolean handleOutputPeq(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        String[] parts = path.split("/");
        requirePathParts(parts, 5, "Invalid PEQ path.");
        String channelId = parts[2];
        int peqIndex = Integer.parseInt(parts[4]);
        String field = parts.length > 5 ? parts[5] : "";
        Map<String, Object> body = requestParser.readJsonBody(request);

        if (field.isBlank()) {
            responses.writeJson(response, 200, dsp.setOutputPeq(
                    channelId,
                    peqIndex,
                    requestParser.requireString(body, "type"),
                    requestParser.requireDouble(body, "hz"),
                    requestParser.requireDouble(body, "q"),
                    requestParser.requireDouble(body, "gainDb")
            ));
            return true;
        }
        if ("q".equalsIgnoreCase(field) && parts.length == 6) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setOutputPeqQ(channelId, peqIndex, requestParser.requireDouble(body, "q"))
            );
            return true;
        }
        if ("gain".equalsIgnoreCase(field) && parts.length == 6) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setOutputPeqGain(channelId, peqIndex, requestParser.requireDouble(body, "gainDb"))
            );
            return true;
        }
        if ("type".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setOutputPeqType(channelId, peqIndex, requestParser.requireString(body, "type"))
            );
            return true;
        }
        if ("bypass".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setOutputPeqBypass(channelId, peqIndex, requestParser.requireBoolean(body, "bypass"))
            );
            return true;
        }
        return false;
    }

    private boolean handleInputPeq(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        String[] parts = path.split("/");
        requirePathParts(parts, 5, "Invalid input PEQ path.");
        String channelId = parts[2];
        int peqIndex = Integer.parseInt(parts[4]);
        String field = parts.length > 5 ? parts[5] : "";
        Map<String, Object> body = requestParser.readJsonBody(request);

        if (field.isBlank()) {
            responses.writeJson(response, 200, dsp.setInputPeq(
                    channelId,
                    peqIndex,
                    requestParser.requireString(body, "type"),
                    requestParser.requireDouble(body, "hz"),
                    requestParser.requireDouble(body, "q"),
                    requestParser.requireDouble(body, "gainDb"),
                    requestParser.requireBoolean(body, "bypass")
            ));
            return true;
        }
        if ("frequency".equalsIgnoreCase(field)) {
            responses.writeJson(response, 200, dsp.setInputPeqFrequency(
                    channelId,
                    peqIndex,
                    requestParser.requireDouble(body, "hz")
            ));
            return true;
        }
        if ("q".equalsIgnoreCase(field)) {
            responses.writeJson(response, 200, dsp.setInputPeqQ(
                    channelId,
                    peqIndex,
                    requestParser.requireDouble(body, "q")
            ));
            return true;
        }
        if ("gain".equalsIgnoreCase(field)) {
            responses.writeJson(response, 200, dsp.setInputPeqGain(
                    channelId,
                    peqIndex,
                    requestParser.requireDouble(body, "gainDb")
            ));
            return true;
        }
        if ("type".equalsIgnoreCase(field)) {
            responses.writeJson(response, 200, dsp.setInputPeqType(
                    channelId,
                    peqIndex,
                    requestParser.requireString(body, "type")
            ));
            return true;
        }
        if ("bypass".equalsIgnoreCase(field)) {
            responses.writeJson(response, 200, dsp.setInputPeqBypass(
                    channelId,
                    peqIndex,
                    requestParser.requireBoolean(body, "bypass")
            ));
            return true;
        }
        return false;
    }

    private static void requirePathParts(String[] parts, int required, String message) {
        if (parts.length < required) {
            throw new IllegalArgumentException(message);
        }
    }
}

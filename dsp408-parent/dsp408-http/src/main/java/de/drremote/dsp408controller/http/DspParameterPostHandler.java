package de.drremote.dsp408controller.http;

import java.util.Iterator;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;

/** Strict request handling for the additive parameter-write endpoint. */
final class DspParameterPostHandler {
    private static final Set<String> FIELDS = Set.of("channelId", "parameter", "value", "verify");
    private static final Set<String> PARAMETERS = Set.of("gain", "mute", "phase", "delay");

    private final ObjectMapper bodyMapper;
    private final DspHttpResponses responses;

    DspParameterPostHandler(ObjectMapper mapper, DspHttpResponses responses) {
        // Keep legacy endpoints' coercion and duplicate-key behavior unchanged.
        bodyMapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.responses = responses;
    }

    boolean handle(String path, DspService dsp, HttpServletRequest request,
                   HttpServletResponse response) throws Exception {
        if (!path.equals("/parameters/write")) return false;

        ParameterWriteRequestDto write = readRequest(request);
        ParameterWriteResultDto result = dsp.writeParameter(write);
        responses.writeJson(response, httpStatus(result), result);
        return true;
    }

    private ParameterWriteRequestDto readRequest(HttpServletRequest request) throws Exception {
        JsonNode body = bodyMapper.readTree(request.getInputStream());
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown field: " + field + ".");
            }
        }

        String channelId = requireString(body, "channelId");
        String parameter = requireString(body, "parameter");
        if (!PARAMETERS.contains(parameter)) {
            throw new IllegalArgumentException("Unknown parameter: " + parameter + ".");
        }
        boolean verify = !body.has("verify") || requireBoolean(body, "verify");
        Object value;
        if (parameter.equals("mute") || parameter.equals("phase")) {
            value = requireBoolean(body, "value");
        } else {
            JsonNode number = body.get("value");
            if (number == null || !number.isNumber() || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("value must be a finite JSON number.");
            }
            value = number.doubleValue();
        }
        return new ParameterWriteRequestDto(channelId, parameter, value, verify);
    }

    private static String requireString(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty JSON string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a JSON boolean.");
        }
        return value.booleanValue();
    }

    private static int httpStatus(ParameterWriteResultDto result) {
        return switch (result.status()) {
            case ACKNOWLEDGED, VERIFIED -> 200;
            case REJECTED -> "dsp_overloaded".equals(result.errorCode()) ? 503
                    : "invalid_input".equals(result.errorCode()) ? 422 : 409;
            case UNCERTAIN -> "dsp_overloaded".equals(result.errorCode()) ? 503
                    : "readback_mismatch".equals(result.errorCode()) ? 409 : 502;
        };
    }
}

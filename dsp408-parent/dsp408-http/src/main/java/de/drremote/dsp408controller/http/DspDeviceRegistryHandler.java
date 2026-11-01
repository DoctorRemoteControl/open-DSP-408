package de.drremote.dsp408controller.http;

import java.util.Iterator;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspDeviceRegistrationDto;
import de.drremote.dsp408.api.DspService;

/** Registry definitions are separate from selected devices and never imply a connection. */
final class DspDeviceRegistryHandler {
    private static final Set<String> FIELDS = Set.of("id", "host", "port", "library", "autoConnect");
    private final ObjectMapper bodyMapper;
    private final DspHttpResponses responses;

    DspDeviceRegistryHandler(ObjectMapper mapper, DspHttpResponses responses) {
        bodyMapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.responses = responses;
    }

    boolean get(String path, DspService registry, HttpServletResponse response) throws Exception {
        if (path.equals("/devices")) {
            responses.writeJson(response, 200, registry.getDeviceRegistry());
            return true;
        }
        String id = deviceId(path);
        if (id == null) return false;
        responses.writeJson(response, 200, registry.getDeviceDefinition(id));
        return true;
    }

    boolean post(String path, DspService registry, HttpServletRequest request,
                 HttpServletResponse response) throws Exception {
        if (!path.equals("/devices")) return false;
        DspDeviceRegistrationDto registration = registry.registerDevice(readDefinition(request));
        responses.writeJson(response, registration.created() ? 201 : 200, registration);
        return true;
    }

    boolean delete(String path, DspService registry, HttpServletResponse response) {
        String id = deviceId(path);
        if (id == null) return false;
        registry.removeDevice(id);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        return true;
    }

    private static String deviceId(String path) {
        if (!path.startsWith("/devices/")) return null;
        String id = path.substring("/devices/".length());
        return id.isEmpty() || id.contains("/") ? null : id;
    }

    private DspDeviceDefinitionDto readDefinition(HttpServletRequest request) throws Exception {
        JsonNode body = bodyMapper.readTree(request.getInputStream());
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown field: " + field + ".");
        }
        int port = 9761;
        if (body.has("port")) {
            JsonNode value = body.get("port");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new IllegalArgumentException("port must be a JSON integer between 1 and 65535.");
            }
            port = value.intValue();
        }
        boolean autoConnect = false;
        if (body.has("autoConnect")) {
            JsonNode value = body.get("autoConnect");
            if (!value.isBoolean()) throw new IllegalArgumentException("autoConnect must be a JSON boolean.");
            autoConnect = value.booleanValue();
        }
        return new DspDeviceDefinitionDto(requireString(body, "id"), requireString(body, "host"), port,
                body.has("library") ? requireString(body, "library") : "auto", autoConnect);
    }

    private static String requireString(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty JSON string.");
        }
        return value.textValue();
    }
}

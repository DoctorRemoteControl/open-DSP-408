package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiRegistryContractTest {
    @Test void documentsRegistryCrudStatusesAndStrictRegistrationDefaults() throws Exception {
        JsonNode root = specification();
        JsonNode collection = root.path("paths").path("/api/v1/devices");
        assertEquals("#/components/schemas/DspDeviceRegistryDto", collection.path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText());
        for (String code : List.of("200", "201", "400", "403", "409", "422", "500"))
            assertTrue(collection.path("post").path("responses").has(code), "POST /devices " + code);
        JsonNode item = root.path("paths").path("/api/v1/devices/{id}");
        assertTrue(item.path("get").path("responses").has("200"));
        assertTrue(item.path("get").path("responses").has("404"));
        for (String code : List.of("204", "403", "404", "409", "500"))
            assertTrue(item.path("delete").path("responses").has(code), "DELETE /devices/{id} " + code);
        assertFalse(item.path("delete").path("responses").path("204").has("content"));
        JsonNode body = root.path("components").path("schemas").path("DspDeviceRegistrationRequest");
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertEquals(List.of("id", "host"), new ObjectMapper().convertValue(body.path("required"), List.class));
        JsonNode fields = body.path("properties");
        assertEquals(9761, fields.path("port").path("default").asInt());
        assertEquals("auto", fields.path("library").path("default").asText());
        assertFalse(fields.path("autoConnect").path("default").asBoolean(true));
        assertEquals(64, fields.path("id").path("maxLength").asInt());
        assertEquals(1, fields.path("port").path("minimum").asInt());
        assertEquals(65535, fields.path("port").path("maximum").asInt());
    }

    @Test void selectedRoutesDocumentUnknownTargetsAndRootRoutesDocumentManagedConflict() throws Exception {
        JsonNode root = specification();
        var paths = root.path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            for (String method : List.of("get", "post", "delete")) {
                if (!path.getValue().has(method)) continue;
                if (path.getKey().startsWith("/api/v1/dsps/{dspId}"))
                    assertTrue(path.getValue().path(method).path("responses").has("404"), path.getKey());
            }
        }
        assertTrue(root.path("paths").path("/api/v1/state").path("get").path("responses").has("409"));
        assertTrue(root.path("paths").path("/api/v1/connection/connect").path("post").path("responses").has("409"));
        String keyDescription = root.path("components").path("parameters").path("parameterRequestKey").path("description").asText();
        assertTrue(keyDescription.contains("service identity"));
    }

    private JsonNode specification() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(input);
            return new ObjectMapper().readTree(input);
        }
    }
}

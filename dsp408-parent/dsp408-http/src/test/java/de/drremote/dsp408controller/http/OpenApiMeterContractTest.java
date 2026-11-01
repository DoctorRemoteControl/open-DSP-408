package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiMeterContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/channels/{channelId}/gain", "/api/v1/dsps/{dspId}/channels/{channelId}/gain",
            "/api/v1/raw", "/api/v1/blocks/{blockIndex}/read", "/api/v1/preset/apply",
            "/api/v1/dsps/{dspId}/preset/apply", "/api/v1/preset/export"})
    void commandEndpointsDocumentStructuredOverload(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);
            String method = path.endsWith("/export") ? "get" : "post";
            JsonNode overload = root.path("paths").path(path).path(method).path("responses").path("503");
            assertEquals("#/components/responses/DspOverloadedResponse", overload.path("$ref").asText());
            JsonNode example = root.path("components").path("responses").path("DspOverloadedResponse")
                    .path("content").path("application/json").path("example");
            assertEquals("dsp_overloaded", example.path("error").asText());
            assertEquals(503, example.path("status").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/meters/read", "/api/v1/dsps/{dspId}/meters/read"})
    void documentsDisconnectedMeterPollingAsConflict(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode conflict = root.path("paths").path(path).path("post").path("responses").path("409");

            assertEquals("dsp_not_connected", conflict.path("content").path("application/json")
                    .path("example").path("error").asText());
            assertEquals(409, conflict.path("content").path("application/json")
                    .path("example").path("status").asInt());
        }
    }

    @Test
    void documentsRuntimeMeterEndpointsAndDtos() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1meters/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1meters~1read/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1traffic/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1settings-cache/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1lock/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1lock/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1status/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1start/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1cancel/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1auto-limit~1undo/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1meter/get").get("responses"));
            assertNotNull(root.at("/components/schemas/MeterDto").get("properties"));
            assertNotNull(root.at("/components/schemas/MeterDto/properties/clipActive"));
            assertNotNull(root.at("/components/schemas/MeterDto/properties/clipThresholdConfirmed"));
            assertNotNull(root.at("/components/schemas/MeterStateDto").get("properties"));
            assertNotNull(root.at("/components/schemas/MeterStateDto/properties/rawPayloadHex"));
            assertNotNull(root.at("/components/schemas/MeterStateDto/properties/tailBytesRaw"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/meters"));
            assertNotNull(root.at("/components/schemas/SettingsCacheDto/properties/ready"));
            assertNotNull(root.at("/components/schemas/SettingsCacheDto/properties/missingBlockIndices"));
        }
    }
}

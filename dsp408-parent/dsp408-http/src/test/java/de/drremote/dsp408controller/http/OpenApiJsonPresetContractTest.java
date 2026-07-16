package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiJsonPresetContractTest {

    @Test
    void documentsJsonPresetValidationAndApplyEndpoints() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1preset~1validate/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1preset~1apply/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1dsps~1{dspId}~1preset~1apply/post").get("responses"));
            assertNotNull(root.at("/components/schemas/JsonPreset").get("properties"));
            assertNotNull(root.at("/components/schemas/JsonPresetOutput").get("properties"));
            assertNotNull(root.at("/components/schemas/JsonPresetApplyResult").get("properties"));
        }
    }

    @Test
    void documentsEvidenceGateConflictResponses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1input-peq~1{peqIndex}/post/responses")
                    .get("409"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1gate/post/responses")
                    .get("409"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1crossover~1{filter}/post/responses")
                    .get("409"));
            assertNotNull(root.at("/paths/~1api~1v1~1fir~1generator/post/responses").get("409"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1compressor/post/responses")
                    .get("409"));
        }
    }
}

package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiNativePresetContractTest {
    @Test
    void documentsCompleteJsonExportAndNativeStoreEndpoints() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            JsonNode root = new ObjectMapper().readTree(stream);
            assertNotNull(root.at("/paths/~1api~1v1~1preset~1export/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1preset~1store/post").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1preset~1load/post").get("responses"));
        }
    }
}

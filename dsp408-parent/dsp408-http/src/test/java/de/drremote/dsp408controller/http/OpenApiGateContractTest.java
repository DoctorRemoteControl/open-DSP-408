package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiGateContractTest {

    @Test
    void documentsGateReadEndpointAndStateDto() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1gate/get").get("responses"));
            assertNotNull(root.at("/components/schemas/InputGateDto").get("properties"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/inputGates"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1compressor/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1limiter/get").get("responses"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/inputCompressors"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/limiters"));
        }
    }
}

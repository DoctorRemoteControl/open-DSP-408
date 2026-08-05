package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiMeterContractTest {

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

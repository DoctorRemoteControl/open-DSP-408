package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiPeqBypassContractTest {

    @Test
    void documentsOutputAndAllBandPeqBypassWrites() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1peq/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1peq~1{channelId}/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1channels~1{channelId}~1peq~1{peqIndex}~1bypass/post")
                    .get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1peq~1bypass-all/post").get("responses"));
            assertNotNull(root.at("/components/schemas/PeqBandDto/properties/bypass"));
            assertNotNull(root.at("/components/schemas/PeqBandDto/properties/bypassConfirmedFromDevice"));
            assertNotNull(root.at("/components/schemas/PeqChannelDto/properties/bands"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/peqChannels"));
        }
    }
}

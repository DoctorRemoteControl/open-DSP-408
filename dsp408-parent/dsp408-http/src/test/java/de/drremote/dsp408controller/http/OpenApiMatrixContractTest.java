package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiMatrixContractTest {

    @Test
    void documentsReadableMatrixStateAndAllCrosspointGains() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openapi/dsp408-openapi.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);

            assertNotNull(root.at("/paths/~1api~1v1~1matrix/get").get("responses"));
            assertNotNull(root.at("/paths/~1api~1v1~1matrix~1{outputId}/get").get("responses"));
            assertNotNull(root.at("/components/schemas/MatrixOutputDto/properties/crosspoints"));
            assertNotNull(root.at("/components/schemas/MatrixCrosspointDto/properties/gainDb"));
            assertNotNull(root.at("/components/schemas/MatrixCrosspointDto/properties/gainConfirmedFromDevice"));
            assertNotNull(root.at("/components/schemas/StateDto/properties/matrixOutputs"));
        }
    }
}

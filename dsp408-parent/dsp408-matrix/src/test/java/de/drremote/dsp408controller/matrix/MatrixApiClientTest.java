package de.drremote.dsp408controller.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MatrixApiClientTest {

    @Test
    void initialSyncUsesBoundedDefaultTimeout() {
        assertEquals(Duration.ofSeconds(15), MatrixApiClient.syncRequestTimeout(0));
    }

    @Test
    void longPollTimeoutIncludesNetworkGracePeriod() {
        assertEquals(Duration.ofSeconds(35), MatrixApiClient.syncRequestTimeout(30_000));
    }

    @Test
    void syncRequestCarriesTheCalculatedClientTimeout() {
        MatrixApiClient client = new MatrixApiClient("https://matrix.example", "test-token");

        HttpRequest request = client.syncRequest("batch token", 30_000);

        assertEquals(Optional.of(Duration.ofSeconds(35)), request.timeout());
        assertEquals(
                "https://matrix.example/_matrix/client/v3/sync?timeout=30000&since=batch+token",
                request.uri().toString()
        );
    }
}

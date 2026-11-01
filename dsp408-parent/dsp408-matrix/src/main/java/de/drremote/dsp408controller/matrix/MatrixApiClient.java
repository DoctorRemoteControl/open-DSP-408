package de.drremote.dsp408controller.matrix;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class MatrixApiClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SYNC_TIMEOUT_GRACE = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String accessToken;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MatrixApiClient(String baseUrl, String accessToken) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.accessToken = accessToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public JsonNode whoAmI() throws IOException, InterruptedException {
        HttpRequest request = authorizedGet("/_matrix/client/v3/account/whoami");
        return sendJson(request);
    }

    public JsonNode sync(String since, int timeoutMs) throws IOException, InterruptedException {
        return sendJson(syncRequest(since, timeoutMs));
    }

    HttpRequest syncRequest(String since, int timeoutMs) {
        StringBuilder path = new StringBuilder("/_matrix/client/v3/sync?timeout=").append(timeoutMs);
        if (since != null && !since.isBlank()) {
            path.append("&since=").append(url(since));
        }
        return authorizedGet(path.toString(), syncRequestTimeout(timeoutMs));
    }

    public void sendText(String roomId, String body) throws IOException, InterruptedException {
        sendText(roomId, body, true);
    }

    public void sendText(String roomId, String body, boolean useHtml) throws IOException, InterruptedException {
        String txnId = UUID.randomUUID().toString();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("msgtype", "m.text");
        payload.put("body", body);

        if (useHtml) {
            payload.put("format", "org.matrix.custom.html");
            payload.put("formatted_body", "<pre>" + escapeHtml(body) + "</pre>");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_matrix/client/v3/rooms/" + url(roomId)
                        + "/send/m.room.message/" + url(txnId)))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        sendNoBody(request);
    }

    public void sendEvent(String roomId, String eventType, JsonNode content) throws IOException, InterruptedException {
        String txnId = UUID.randomUUID().toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_matrix/client/v3/rooms/" + url(roomId)
                        + "/send/" + url(eventType) + "/" + url(txnId)))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(content.toString(), StandardCharsets.UTF_8))
                .build();

        sendNoBody(request);
    }

    public JsonNode getState(String roomId, String eventType, String stateKey)
            throws IOException, InterruptedException {
        String path = "/_matrix/client/v3/rooms/" + url(roomId)
                + "/state/" + url(eventType) + "/" + url(stateKey);
        return sendJson(authorizedGet(path));
    }

    public void sendState(String roomId, String eventType, String stateKey, JsonNode content)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/_matrix/client/v3/rooms/" + url(roomId)
                        + "/state/" + url(eventType) + "/" + url(stateKey)))
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(content.toString(), StandardCharsets.UTF_8))
                .build();
        sendNoBody(request);
    }

    private HttpRequest authorizedGet(String path) {
        return authorizedGet(path, DEFAULT_REQUEST_TIMEOUT);
    }

    private HttpRequest authorizedGet(String path, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
    }

    static Duration syncRequestTimeout(int serverTimeoutMs) {
        if (serverTimeoutMs <= 0) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
        return Duration.ofMillis(serverTimeoutMs).plus(SYNC_TIMEOUT_GRACE);
    }

    private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private void sendNoBody(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be empty");
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

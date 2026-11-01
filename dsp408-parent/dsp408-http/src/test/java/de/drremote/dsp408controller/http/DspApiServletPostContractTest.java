package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.RejectedExecutionException;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.DspDeviceNotFoundException;
import de.drremote.dsp408.api.StateDto;

class DspApiServletPostContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "/channels/out1/gain, gain",
            "/dsps/room2/channels/out1/gain, gain",
            "/delay-unit, unit",
            "/dsps/room2/delay-unit, unit",
            "/raw, payloadHex",
            "/dsps/room2/raw, payloadHex",
            "/command, line",
            "/dsps/room2/command, line",
            "/volume/set, gain",
            "/dsps/room2/volume/set, gain",
            "/blocks/00/read, empty",
            "/dsps/room2/blocks/00/read, empty"
    })
    void overloadedCommandPathsReturnStructuredServiceUnavailable(String path, String field) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.failure = new RejectedExecutionException("DSP command queue is full (64 pending commands)");
        String body = switch (field) {
            case "gain" -> "{\"db\":-20}";
            case "line" -> "{\"line\":\"gain out1 -20\"}";
            case "unit" -> "{\"unit\":\"ms\"}";
            case "payloadHex" -> "{\"payloadHex\":\"00 01 01 12\"}";
            default -> "";
        };

        Response response = fixture.post(path, body, Map.of());

        assertOverloaded(response);
        assertTrue(fixture.target.connected);
        assertEquals(1, fixture.target.calls.size());
        if (path.startsWith("/dsps/")) assertTrue(fixture.root.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preset/export", "/dsps/room2/preset/export"})
    void overloadedGetCommandReturnsStructuredServiceUnavailable(String path) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.failure = new RejectedExecutionException("DSP command queue is full (64 pending commands)");
        Response response = new Response();

        fixture.servlet.doGet(request(path, "", Map.of()), response.proxy);

        assertOverloaded(response);
        assertTrue(fixture.target.connected);
        assertEquals(List.of("exportPresetSnapshot"), fixture.target.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preset/apply", "/dsps/room2/preset/apply"})
    void presetOverloadRetainsFailureDetailsAndReturnsServiceUnavailable(String path) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.failure = new RejectedExecutionException("DSP command queue is full (64 pending commands)");

        Response response = fixture.post(path, minimalPreset(), Map.of());

        assertOverloaded(response);
        assertTrue(response.json().get("emergencyMuteAttempted").asBoolean());
        assertEquals(0, response.json().get("completedOperationCount").asInt());
        assertFalse(response.json().get("allChannelsEmergencyMuted").asBoolean());
        assertTrue(fixture.target.connected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed", "replaced"})
    void presetFailureKeepsOriginalContextWhenTargetChangesDuringApply(String mutation) throws Exception {
        Fixture fixture = new Fixture("/dsps/room2/preset/apply");
        fixture.target.failure = new IOException("Target was removed during a preset operation.");
        RecordingDsp replacement = new RecordingDsp("room2");
        fixture.target.beforeFailure = () -> fixture.root.selected = mutation.equals("removed") ? null : replacement;

        Response response = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> fixture.post("/dsps/room2/preset/apply", minimalPreset(), Map.of()));

        assertEquals(500, response.status);
        assertEquals("preset_apply_failed", response.json().path("error").asText());
        assertEquals("room2", response.json().path("dspId").asText());
        assertTrue(response.json().path("emergencyMuteAttempted").asBoolean());
        assertEquals(1, fixture.root.selections, "A failure must retain the originally resolved target.");
        assertTrue(replacement.calls.isEmpty());
    }

    private static void assertOverloaded(Response response) throws Exception {
        assertEquals(503, response.status);
        assertEquals("application/json", response.contentType);
        assertEquals("UTF-8", response.encoding);
        assertEquals("dsp_overloaded", response.json().get("error").asText());
        assertEquals(503, response.json().get("status").asInt());
        assertFalse(response.json().has("ok"));
        assertFalse(response.json().get("message").asText().isBlank());
    }

    @ParameterizedTest
    @CsvSource({
            "/preset/apply, omitted, true",
            "/preset/apply, true, true",
            "/preset/apply, false, false",
            "/dsps/room2/preset/apply, omitted, true",
            "/dsps/room2/preset/apply, true, true",
            "/dsps/room2/preset/apply, false, false"
    })
    void presetApplyScansByDefaultAndHonorsExplicitRefresh(String path, String refresh, boolean expectedRefresh)
            throws Exception {
        Fixture fixture = new Fixture(path);
        Map<String, String> query = refresh.equals("omitted") ? Map.of() : Map.of("refresh", refresh);

        Response response = fixture.post(path, minimalPreset(), query);

        assertEquals(200, response.status);
        assertEquals(fixture.target.id, response.json().get("dspId").asText());
        assertEquals(expectedRefresh, response.json().get("refreshed").asBoolean());
        assertEquals(expectedRefresh ? 1 : 0,
                fixture.target.calls.stream().filter("scanBlocks"::equals).count());
        assertFalse(fixture.target.calls.contains("unmute"));
        if (path.startsWith("/dsps/")) {
            assertTrue(fixture.root.calls.isEmpty(), "A selected DSP must not change the default DSP.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/meters/read", "/dsps/room2/meters/read"})
    void disconnectedMeterPollingReturnsConflictWithoutCallingRuntime(String path) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.connected = false;
        fixture.target.failure = new IllegalStateException("Not connected. Run 'connect' first.");

        // Repeated polling must remain an ordinary response, without runtime exceptions.
        for (int poll = 0; poll < 3; poll++) {
            Response response = fixture.post(path, "", Map.of());
            assertDisconnected(response, fixture.target.id);
        }
        assertTrue(fixture.target.calls.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/meters/read", "/dsps/room2/meters/read"})
    void meterDisconnectDuringRequestReturnsTheSameConflict(String path) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.disconnectDuringRequest = true;
        fixture.target.failure = new IllegalStateException("Not connected. Run 'connect' first.");

        Response response = fixture.post(path, "", Map.of());

        assertDisconnected(response, fixture.target.id);
        assertEquals(List.of("requestRuntimeMeters"), fixture.target.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/meters/read", "/dsps/room2/meters/read"})
    void cachedDisconnectedResultReturnsConflictWithoutAnException(String path) throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.disconnectDuringRequest = true;
        Response response = fixture.post(path, "", Map.of());
        assertDisconnected(response, fixture.target.id);
        assertEquals(List.of("requestRuntimeMeters"), fixture.target.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/meters/read", "/dsps/room2/meters/read"})
    void connectedMeterPollingReturnsTheRuntimeSnapshot(String path) throws Exception {
        Fixture fixture = new Fixture(path);

        Response response = fixture.post(path, "", Map.of());

        assertEquals(200, response.status);
        assertTrue(response.json().get("connected").asBoolean());
        assertEquals("2A", response.json().get("cachedBlockIndices").get(0).asText());
        assertEquals(List.of("requestRuntimeMeters"), fixture.target.calls);
    }

    @ParameterizedTest
    @CsvSource({
            "/meters/read, state, 409, conflict",
            "/dsps/room2/meters/read, state, 409, conflict",
            "/meters/read, io, 500, internal_error",
            "/dsps/room2/meters/read, io, 500, internal_error",
            "/delay-unit, state, 409, conflict",
            "/dsps/room2/delay-unit, state, 409, conflict",
            "/delay-unit, io, 500, internal_error",
            "/dsps/room2/delay-unit, io, 500, internal_error"
    })
    void otherFailuresKeepTheExistingServletErrorMapping(String path, String failureType,
                                                        int expectedStatus, String expectedError)
            throws Exception {
        Fixture fixture = new Fixture(path);
        fixture.target.failure = failureType.equals("state")
                ? new IllegalStateException("Unexpected runtime state.")
                : new IOException("Unexpected transport failure.");

        Response response = fixture.post(path, "{\"unit\":\"ms\"}", Map.of());

        assertEquals(expectedStatus, response.status);
        assertEquals(expectedError, response.json().get("error").asText());
        assertEquals(1, fixture.target.calls.size());
    }

    private static void assertDisconnected(Response response, String dspId) throws Exception {
        assertEquals(409, response.status);
        assertEquals("application/json", response.contentType);
        assertEquals("UTF-8", response.encoding);
        assertEquals("dsp_not_connected", response.json().get("error").asText());
        assertEquals(409, response.json().get("status").asInt());
        assertEquals("DSP " + dspId + " is not connected.", response.json().get("message").asText());
    }

    private static String minimalPreset() throws Exception {
        List<Map<String, Object>> inputs = Stream.of("ina", "inb", "inc", "ind")
                .map(id -> Map.<String, Object>of(
                        "id", id, "name", id, "gainDb", -20,
                        "phaseInverted", false, "delayMs", 0))
                .toList();
        List<Map<String, Object>> outputs = Stream.of("out1", "out2", "out3", "out4",
                        "out5", "out6", "out7", "out8")
                .map(id -> Map.<String, Object>of(
                        "id", id, "name", id, "gainDb", -20,
                        "phaseInverted", false, "delayMs", 0, "processingMode", "iir"))
                .toList();
        return MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "1.0", "presetId", "http-contract",
                "inputs", inputs, "outputs", outputs));
    }

    private static final class Fixture {
        final RecordingDsp root = new RecordingDsp("main");
        final RecordingDsp selected = new RecordingDsp("room2");
        final RecordingDsp target;
        final DspApiServlet servlet = new DspApiServlet();

        Fixture(String path) throws Exception {
            root.selected = selected;
            target = path.startsWith("/dsps/") ? selected : root;
            Field dspField = DspApiServlet.class.getDeclaredField("dsp");
            dspField.setAccessible(true);
            dspField.set(servlet, root.service);
        }

        Response post(String path, String body, Map<String, String> query) throws Exception {
            Response response = new Response();
            servlet.doPost(request(path, body, query), response.proxy);
            return response;
        }
    }

    private static final class RecordingDsp implements InvocationHandler {
        final String id;
        final List<String> calls = new ArrayList<>();
        final DspService service = (DspService) Proxy.newProxyInstance(
                DspService.class.getClassLoader(), new Class<?>[]{DspService.class}, this);
        RecordingDsp selected;
        boolean connected = true;
        boolean disconnectDuringRequest;
        Exception failure;
        Runnable beforeFailure;
        int selections;

        RecordingDsp(String id) {
            this.id = id;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "dspId" -> id;
                case "isConnected" -> connected;
                case "forDsp" -> {
                    assertEquals("room2", args[0]);
                    selections++;
                    if (selected == null) throw new DspDeviceNotFoundException("Removed target.");
                    yield selected.service;
                }
                case "requestRuntimeMeters", "setDelayUnit", "scanBlocks", "mute", "unmute",
                     "setChannelName", "setGain", "setPhase", "setDelay", "setFirProcessingMode",
                     "setMatrixRoutes", "sendRawHex", "readBlock", "exportPresetSnapshot",
                     "executeShell", "executeMatrix", "executeVolumeRoom" -> {
                    calls.add(method.getName());
                    if (disconnectDuringRequest) {
                        connected = false;
                    }
                    if (failure != null) {
                        if (beforeFailure != null) beforeFailure.run();
                        throw failure;
                    }
                    yield method.getName().equals("requestRuntimeMeters")
                            ? new StateDto(connected, null, List.of(), List.of("2A")) : null;
                }
                default -> throw new AssertionError("Unexpected DSP method: " + method.getName());
            };
        }
    }

    private static HttpServletRequest request(String path, String body, Map<String, String> query) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        ServletInputStream stream = new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> path;
                    case "getHeader" -> null;
                    case "getContentLengthLong" -> (long) bytes.length;
                    case "getInputStream" -> stream;
                    case "getParameter" -> query.get(args[0]);
                    default -> throw new AssertionError("Unexpected request method: " + method.getName());
                });
    }

    private static final class Response {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ServletOutputStream output = new ServletOutputStream() {
            @Override public void write(int value) { bytes.write(value); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
        int status;
        String contentType;
        String encoding;
        final HttpServletResponse proxy = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> status = (int) args[0];
                        case "setContentType" -> contentType = (String) args[0];
                        case "setCharacterEncoding" -> encoding = (String) args[0];
                        case "getOutputStream" -> { return output; }
                        default -> throw new AssertionError("Unexpected response method: " + method.getName());
                    }
                    return null;
                });

        JsonNode json() throws Exception {
            return MAPPER.readTree(bytes.toByteArray());
        }
    }
}

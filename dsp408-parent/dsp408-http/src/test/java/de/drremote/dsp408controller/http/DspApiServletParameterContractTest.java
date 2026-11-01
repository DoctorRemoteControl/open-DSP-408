package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.CapabilitiesDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.ParameterReadbackDto;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;

/** Exercises the actual servlet, JSON parser and response serialization without hardware. */
class DspApiServletParameterContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> PREFIXES = List.of("", "/dsps/room2");

    @ParameterizedTest
    @ValueSource(strings = {"", "/dsps/room2"})
    void capabilitiesUseTheSelectedServiceWithoutRequestingHardware(String prefix) throws Exception {
        Fixture fixture = new Fixture(prefix);

        Response response = fixture.get(prefix + "/capabilities");

        assertEquals(200, response.status);
        assertEquals(fixture.target.id, response.json().get("dspId").asText());
        assertTrue(response.json().get("firmwareRecognized").asBoolean());
        assertEquals(List.of("getCapabilities"), fixture.target.calls);
        fixture.assertOtherServiceUnused();
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    void writesPassStrictValuesAndDefaultVerificationToTheSelectedService(
            String prefix, String body, String parameter, Object value, boolean verify) throws Exception {
        Fixture fixture = new Fixture(prefix);

        Response response = fixture.post(prefix + "/parameters/write", body);

        assertEquals(200, response.status);
        assertEquals("ACKNOWLEDGED", response.json().get("status").asText());
        assertTrue(response.json().get("acknowledged").asBoolean());
        assertFalse(response.json().has("ok"));
        assertEquals("out1", fixture.target.request.channelId());
        assertEquals(parameter, fixture.target.request.parameter());
        assertEquals(value, fixture.target.request.value());
        assertEquals(verify, fixture.target.request.verify());
        assertEquals(List.of("writeParameter"), fixture.target.calls);
        fixture.assertOtherServiceUnused();
    }

    private static Stream<Arguments> validRequests() {
        return PREFIXES.stream().flatMap(prefix -> Stream.of(
                Arguments.of(prefix, "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":-12.3}", "gain", -12.3, true),
                Arguments.of(prefix, "{\"channelId\":\"out1\",\"parameter\":\"delay\",\"value\":1.25,\"verify\":false}", "delay", 1.25, false),
                Arguments.of(prefix, "{\"channelId\":\"out1\",\"parameter\":\"mute\",\"value\":false,\"verify\":true}", "mute", false, true),
                Arguments.of(prefix, "{\"channelId\":\"out1\",\"parameter\":\"phase\",\"value\":true}", "phase", true, true)));
    }

    @ParameterizedTest
    @MethodSource("documentedRequests")
    void publishedRequestExamplesWorkThroughTheServlet(String prefix, JsonNode example) throws Exception {
        Fixture fixture = new Fixture(prefix);

        Response response = fixture.post(prefix + "/parameters/write", example.toString());

        assertEquals(200, response.status);
        assertEquals(example.get("parameter").asText(), response.json().get("parameter").asText());
        assertEquals(example.path("verify").asBoolean(true), fixture.target.request.verify());
        fixture.assertOtherServiceUnused();
    }

    private static Stream<Arguments> documentedRequests() throws Exception {
        JsonNode root;
        try (var input = DspApiServletParameterContractTest.class
                .getResourceAsStream("/openapi/dsp408-openapi.json")) {
            root = MAPPER.readTree(input);
        }
        JsonNode examples = root.at("/components/requestBodies/ParameterWriteBody/content/application~1json/examples");
        assertTrue(examples.isObject(), "Parameter request examples must be published.");
        return PREFIXES.stream().flatMap(prefix -> StreamSupport.stream(examples.spliterator(), false)
                .map(example -> Arguments.of(prefix, example.get("value"))));
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    void resultStatusAndErrorCodeDetermineHttpStatusWithoutChangingTheOutcome(
            String prefix, String status, String code, int httpStatus, boolean acknowledged,
            Boolean transmitted, boolean readback) throws Exception {
        Fixture fixture = new Fixture(prefix);
        fixture.target.resultStatus = status;
        fixture.target.errorCode = code;
        fixture.target.acknowledged = acknowledged;
        fixture.target.transmitted = transmitted;
        fixture.target.readback = readback;

        Response response = fixture.post(prefix + "/parameters/write", gainBody());

        assertEquals(httpStatus, response.status);
        JsonNode body = response.json();
        assertEquals(status, body.get("status").asText());
        assertEquals(acknowledged, body.get("acknowledged").asBoolean());
        assertEquals(transmitted == null, body.get("transmitted").isNull());
        if (transmitted != null) assertEquals(transmitted, body.get("transmitted").asBoolean());
        assertEquals(fixture.target.id, body.get("dspId").asText());
        assertEquals(-12.34, body.get("requestedValue").asDouble());
        assertEquals(-12.3, body.get("normalizedValue").asDouble());
        assertEquals("http-correlation", body.get("requestId").asText());
        assertEquals(42, body.get("completedAtEpochMs").asLong());
        if (code != null) assertEquals(code, body.get("errorCode").asText());
        if (readback) {
            assertEquals("readback_mismatch".equals(code) ? -10.0 : -12.3,
                    body.get("readback").get("value").asDouble());
            assertEquals(9, body.get("readback").get("receiveRevision").asLong());
        } else {
            assertTrue(body.get("readback") == null || body.get("readback").isNull());
        }
        assertEquals("application/json", response.contentType);
        assertEquals("UTF-8", response.encoding);
        fixture.assertOtherServiceUnused();
    }

    private static Stream<Arguments> outcomes() {
        return PREFIXES.stream().flatMap(prefix -> Stream.of(
                Arguments.of(prefix, "ACKNOWLEDGED", null, 200, true, true, false),
                Arguments.of(prefix, "VERIFIED", null, 200, true, true, true),
                Arguments.of(prefix, "REJECTED", "dsp_overloaded", 503, false, false, false),
                Arguments.of(prefix, "REJECTED", "dsp_not_connected", 409, false, false, false),
                Arguments.of(prefix, "REJECTED", "unsupported_firmware", 409, false, false, false),
                Arguments.of(prefix, "REJECTED", "unsupported_parameter", 409, false, false, false),
                Arguments.of(prefix, "REJECTED", "readback_unsupported", 409, false, false, false),
                Arguments.of(prefix, "REJECTED", "invalid_input", 422, false, false, false),
                Arguments.of(prefix, "UNCERTAIN", "write_failed", 502, false, null, false),
                Arguments.of(prefix, "UNCERTAIN", "readback_failed", 502, true, true, false),
                Arguments.of(prefix, "UNCERTAIN", "session_changed", 502, true, true, false),
                Arguments.of(prefix, "UNCERTAIN", "readback_mismatch", 409, true, true, true),
                Arguments.of(prefix, "UNCERTAIN", "dsp_overloaded", 503, true, true, false)));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyIsRejectedBeforeInvokingTheParameterService(String prefix, String body, int status)
            throws Exception {
        Fixture fixture = new Fixture(prefix);

        Response response = fixture.post(prefix + "/parameters/write", body);

        assertEquals(status, response.status, body);
        assertNotNull(response.json().get("error"));
        assertTrue(fixture.target.calls.isEmpty(), body);
        fixture.assertOtherServiceUnused();
    }

    private static Stream<Arguments> invalidBodies() {
        List<String> invalid = List.of(
                "", "null", "[]", "true", "3", "\"gain\"", "{}",
                "{\"channelId\":null,\"parameter\":\"gain\",\"value\":-12}",
                "{\"channelId\":3,\"parameter\":\"gain\",\"value\":-12}",
                "{\"channelId\":\"\",\"parameter\":\"gain\",\"value\":-12}",
                "{\"channelId\":\"out1\",\"parameter\":null,\"value\":-12}",
                "{\"channelId\":\"out1\",\"parameter\":true,\"value\":-12}",
                "{\"channelId\":\"out1\",\"parameter\":\"peq\",\"value\":-12}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":null}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":\"-12\"}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":true}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":[]}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":{}}",
                "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":1e999}",
                "{\"channelId\":\"out1\",\"parameter\":\"delay\",\"value\":\"1\"}",
                "{\"channelId\":\"out1\",\"parameter\":\"mute\",\"value\":\"true\"}",
                "{\"channelId\":\"out1\",\"parameter\":\"mute\",\"value\":1}",
                "{\"channelId\":\"out1\",\"parameter\":\"phase\",\"value\":0}",
                "{\"channelId\":\"out1\",\"parameter\":\"phase\",\"value\":\"false\"}",
                gainBody().replace("}", ",\"verify\":null}"),
                gainBody().replace("}", ",\"verify\":\"false\"}"),
                gainBody().replace("}", ",\"verify\":0}"),
                gainBody().replace("}", ",\"verify\":[]}"),
                gainBody().replace("}", ",\"unexpected\":true}"));
        return PREFIXES.stream().flatMap(prefix -> Stream.concat(
                invalid.stream().map(body -> Arguments.of(prefix, body, 422)),
                Stream.of(
                        Arguments.of(prefix, "{", 400),
                        Arguments.of(prefix, gainBody() + "{}", 400),
                        Arguments.of(prefix, gainBody().replace("}", ",\"verify\":true,\"verify\":false}"), 400),
                        Arguments.of(prefix, gainBody().replace("}", ",\"value\":-10}"), 400))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/dsps/room2"})
    void legacyGainAndPhaseKeepTheirResponseShapeAndCoercion(String prefix) throws Exception {
        Fixture fixture = new Fixture(prefix);

        Response gain = fixture.post(prefix + "/channels/out1/gain", "{\"db\":\"-12.3\"}");
        Response phase = fixture.post(prefix + "/channels/out1/phase", "{\"inverted\":\"yes\"}");

        assertEquals(200, gain.status);
        assertEquals(200, phase.status);
        assertEquals(List.of("setGain", "setPhase"), fixture.target.calls);
        assertTrue(gain.json().get("ok").asBoolean());
        assertEquals("Gain set.", gain.json().get("message").asText());
        assertEquals(-12.3, gain.json().get("channel").get("gainDb").asDouble());
        assertEquals(3, gain.json().size());
        assertEquals("Phase set.", phase.json().get("message").asText());
        assertTrue(phase.json().get("channel").get("phaseInverted").asBoolean());
        assertEquals(3, phase.json().size());
        assertFalse(gain.json().has("requestId"));
        fixture.assertOtherServiceUnused();
    }

    private static String gainBody() {
        return "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":-12.34}";
    }

    private static final class Fixture {
        final RecordingDsp root = new RecordingDsp("main");
        final RecordingDsp selected = new RecordingDsp("room2");
        final RecordingDsp target;
        final DspApiServlet servlet = new DspApiServlet();

        Fixture(String prefix) throws Exception {
            root.selected = selected;
            target = prefix.isEmpty() ? root : selected;
            Field dsp = DspApiServlet.class.getDeclaredField("dsp");
            dsp.setAccessible(true);
            dsp.set(servlet, root.service);
        }

        Response get(String path) throws Exception {
            Response response = new Response();
            servlet.doGet(request(path, ""), response.proxy);
            return response;
        }

        Response post(String path, String body) throws Exception {
            Response response = new Response();
            servlet.doPost(request(path, body), response.proxy);
            return response;
        }

        void assertOtherServiceUnused() {
            assertTrue((target == root ? selected : root).calls.isEmpty());
        }
    }

    private static final class RecordingDsp implements InvocationHandler {
        final String id;
        final List<String> calls = new ArrayList<>();
        final DspService service = (DspService) Proxy.newProxyInstance(
                DspService.class.getClassLoader(), new Class<?>[]{DspService.class}, this);
        RecordingDsp selected;
        ParameterWriteRequestDto request;
        String resultStatus = "ACKNOWLEDGED";
        String errorCode;
        boolean acknowledged = true;
        Boolean transmitted = true;
        boolean readback;

        RecordingDsp(String id) { this.id = id; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "forDsp":
                    assertEquals("room2", args[0]);
                    return selected.service;
                case "getCapabilities":
                    calls.add(method.getName());
                    return MAPPER.readValue("""
                            {"dspId":"%s","connected":true,"firmwareRecognized":true,
                             "deviceVersion":"DSP408 V0104","libraryType":"DSP408",
                             "parameterNames":["gain","mute","phase","delay"],"channels":[],
                             "unavailableReason":null}
                            """.formatted(id), CapabilitiesDto.class);
                case "writeParameter":
                    calls.add(method.getName());
                    request = (ParameterWriteRequestDto) args[0];
                    Object normalized = request.parameter().equals("gain") ? -12.3 : request.value();
                    Object observed = "readback_mismatch".equals(errorCode) ? -10.0 : normalized;
                    return new ParameterWriteResultDto("http-correlation", id, request.channelId(),
                            request.parameter(), request.value(), normalized,
                            ParameterWriteStatus.valueOf(resultStatus), acknowledged, transmitted,
                            readback ? new ParameterReadbackDto(observed, "00", 9, 40) : null,
                            errorCode, "Parameter outcome.", 42);
                case "setGain", "setPhase":
                    calls.add(method.getName());
                    assertEquals("out1", args[0]);
                    if (method.getName().equals("setGain")) assertEquals(-12.3, args[1]);
                    else assertEquals(true, args[1]);
                    return new ChannelDto("out1", "Out1", 4, -12.3, false, true, 0.0,
                            false, false, false, false, false, false, false, false,
                            null, null, null, null);
                default:
                    throw new AssertionError("Unexpected DSP method: " + method.getName());
            }
        }
    }

    private static HttpServletRequest request(String path, String body) {
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
                        case "setHeader" -> { }
                        case "getOutputStream" -> { return output; }
                        default -> throw new AssertionError("Unexpected response method: " + method.getName());
                    }
                    return null;
                });

        JsonNode json() throws Exception { return MAPPER.readTree(bytes.toByteArray()); }
    }
}

package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.ControlStateDto;
import de.drremote.dsp408.api.DspControlConflictException;
import de.drremote.dsp408.api.SettingsCacheDto;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;

/** Retransmitted HTTP requests must not issue another physical parameter write. */
class DspParameterPostHandlerDeliveryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String BODY = "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":-12}";

    @Test
    void concurrentRetransmissionsWaitForAndReturnTheOriginalOutcome() throws Exception {
        Fixture fixture = new Fixture();
        fixture.entered = new CountDownLatch(1);
        fixture.release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var original = executor.submit(() -> fixture.post("", BODY, KEY, null));
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
            var duplicate = executor.submit(() -> fixture.post("", BODY, KEY, null));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> duplicate.get(100, TimeUnit.MILLISECONDS));
            fixture.release.countDown();

            Response first = original.get(5, TimeUnit.SECONDS);
            Response second = duplicate.get(5, TimeUnit.SECONDS);
            assertEquals(200, first.status);
            assertEquals(first.json(), second.json());
            assertEquals(1, fixture.writes.get());
        } finally {
            fixture.release.countDown();
        }
    }

    @Test
    void parsedEquivalentBodyReturnsTheOriginalResultWithoutAnotherWrite() throws Exception {
        Fixture fixture = new Fixture();
        Response first = fixture.post("", BODY, KEY, null);

        Response retry = fixture.post("", "{\"verify\":true,\"value\":-12.0,\"parameter\":\"gain\",\"channelId\":\"out1\"}", KEY, null);

        assertEquals(first.json(), retry.json());
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void keyWithAnotherBodyOrTargetConflictsBeforeWriting() throws Exception {
        Fixture fixture = new Fixture();
        fixture.post("", BODY, KEY, null);

        Response bodyConflict = fixture.post("", BODY.replace("-12", "-10"), KEY, null);
        Response verifyConflict = fixture.post("", BODY.replace("}", ",\"verify\":false}"), KEY, null);
        Response targetConflict = fixture.post("/dsps/room2", BODY, KEY, null);

        assertEquals(409, bodyConflict.status);
        assertEquals("conflict", bodyConflict.json().path("error").asText());
        assertEquals(409, verifyConflict.status);
        assertEquals(409, targetConflict.status);
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void originalProgrammingFailureIsReturnedAgainWithoutReexecutingTheWrite() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failure = new NullPointerException("synthetic programming fault");

        Response first = fixture.post("", BODY, KEY, null);
        Response retry = fixture.post("", BODY, KEY, null);

        assertEquals(500, first.status);
        assertEquals("internal_error", first.json().path("error").asText());
        assertEquals(first.json(), retry.json());
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void requestsWithoutAKeyKeepTheirLegacyExecutionBehavior() throws Exception {
        Fixture fixture = new Fixture();
        fixture.post("", BODY, null, null);
        fixture.post("", BODY, null, null);

        assertEquals(2, fixture.writes.get());
        assertEquals(2, fixture.normalWrites.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "0123456789abcdef 123", "0123456789abcdef\n", "0123456789abcdeé", "0123456789abcdef,second"})
    void malformedKeysAreRejectedBeforeAnyWrite(String key) throws Exception {
        Fixture fixture = new Fixture();

        Response response = fixture.post("", BODY, key, null);

        assertEquals(422, response.status);
        assertEquals(0, fixture.writes.get());
    }

    @Test
    void oversizedKeysAreRejectedBeforeAnyWrite() throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(422, fixture.post("", BODY, "a".repeat(129), null).status);
        assertEquals(0, fixture.writes.get());
    }

    @Test
    void fullCacheRejectsNewKeysButStillReplaysExistingOutcomes() throws Exception {
        Fixture fixture = new Fixture();
        Response original = fixture.post("", BODY, KEY, null);
        for (int i = 1; i < 4096; i++) {
            assertEquals(200, fixture.post("", BODY, "request-key-%020d".formatted(i), null).status);
        }

        Response overflow = fixture.post("", BODY, "request-key-over-capacity", null);
        Response retry = fixture.post("", BODY, KEY, null);

        assertEquals(503, overflow.status);
        assertEquals("dsp_overloaded", overflow.json().path("error").asText());
        assertEquals(original.json(), retry.json());
        assertEquals(4096, fixture.writes.get());
    }

    @Test
    void explicitWaitZeroUsesInteractiveAdmissionAndRetransmissionKeepsOriginalMode() throws Exception {
        Fixture fixture = new Fixture();

        Response first = fixture.post("", BODY, KEY, "respond-async, wait=0");
        Response retry = fixture.post("", BODY, KEY, "wait=10");

        assertEquals(200, first.status);
        assertEquals(first.json(), retry.json());
        assertEquals(1, fixture.interactiveWrites.get());
        assertEquals(0, fixture.normalWrites.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wait=10", "wait=01", "x-wait=0", "wait=0.0", "respond-async"})
    void otherPreferencesKeepTheLegacyWriteRoute(String prefer) throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(200, fixture.post("", BODY, null, prefer).status);
        assertEquals(1, fixture.normalWrites.get());
        assertEquals(0, fixture.interactiveWrites.get());
    }

    @Test
    void busyRejectionAndUncertainResultsBothUse503WithoutChangingTheirBodies() throws Exception {
        Fixture fixture = new Fixture();
        fixture.status = ParameterWriteStatus.REJECTED;
        fixture.errorCode = "dsp_busy";
        Response rejected = fixture.post("", BODY, null, "wait=0");
        fixture.status = ParameterWriteStatus.UNCERTAIN;
        Response uncertain = fixture.post("", BODY, null, "wait=0");

        assertEquals(503, rejected.status);
        assertEquals("REJECTED", rejected.json().path("status").asText());
        assertEquals(false, rejected.json().path("transmitted").asBoolean());
        assertEquals(503, uncertain.status);
        assertEquals("UNCERTAIN", uncertain.json().path("status").asText());
        assertEquals("dsp_busy", uncertain.json().path("errorCode").asText());
    }

    @Test
    void backendTimingIncludesServiceWaitAndAppearsOnCachedResponses() throws Exception {
        Fixture fixture = new Fixture();
        fixture.entered = new CountDownLatch(1);
        fixture.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = executor.submit(() -> fixture.post("", BODY, KEY, null));
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> original.get(120, TimeUnit.MILLISECONDS));
            fixture.release.countDown();
            Response first = original.get(5, TimeUnit.SECONDS);
            Response cached = fixture.post("", BODY, KEY, null);

            assertTrue(backendMilliseconds(first) >= 100);
            assertTrue(backendMilliseconds(cached) >= 0);
        } finally {
            fixture.release.countDown();
        }
    }

    @Test
    void staleConditionIs412DefinitelyUnsentAndIncludesFreshState() throws Exception {
        Fixture fixture = new Fixture();
        Response response = fixture.post("", BODY, KEY, "wait=0", "\"runtime.99\"");
        assertEquals(412, response.status);
        assertEquals("conflict", response.json().path("error").asText());
        assertEquals("revision_conflict", response.json().path("errorCode").asText());
        assertFalse(response.json().path("transmitted").asBoolean(true));
        assertEquals("runtime", response.json().at("/current/instanceId").asText());
        assertEquals(0, response.json().at("/current/revision").asLong());
        assertEquals(5, response.json().size());
        assertEquals(0, fixture.writes.get());
        assertEquals(1, fixture.conditionalAttempts.get());
    }

    @Test
    void conditionalRetryReplaysAckBeforeReevaluatingItsNowStaleCondition() throws Exception {
        Fixture fixture = new Fixture();
        Response first = fixture.post("", BODY, KEY, "wait=0", "\"runtime.0\"");
        Response retry = fixture.post("", BODY, KEY, null, "\"runtime.0\"");
        assertEquals(200, first.status);
        assertEquals(first.json(), retry.json());
        assertEquals(1, fixture.writes.get());
        assertEquals(1, fixture.conditionalAttempts.get());
        assertEquals(1, fixture.interactiveWrites.get());
        assertEquals(0, fixture.normalWrites.get());
    }

    @Test
    void changingTheConditionWithTheSameKeyConflictsBeforeAnotherAttempt() throws Exception {
        Fixture fixture = new Fixture();
        fixture.post("", BODY, KEY, null, "\"runtime.0\"");
        assertEquals(409, fixture.post("", BODY, KEY, null, "\"runtime.1\"").status);
        assertEquals(409, fixture.post("", BODY, KEY, null, null).status);
        assertEquals(1, fixture.conditionalAttempts.get());
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void retryCannotRetargetAReplacementRuntimeWithTheSameDspId() throws Exception {
        Fixture fixture = new Fixture();
        fixture.post("", BODY, KEY, null, "\"runtime.0\"");
        fixture.instanceId = "replacement";
        assertEquals(409, fixture.post("", BODY, KEY, null, "\"runtime.0\"").status);
        assertEquals(1, fixture.conditionalAttempts.get());
        assertEquals(1, fixture.writes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "runtime.0", "W/\"runtime.0\"", "*", "\"runtime.0\", \"other.1\"", "\"bad\nvalue\""})
    void invalidIfMatchRejectsBeforeAnyAttempt(String condition) throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(422, fixture.post("", BODY, KEY, null, condition).status);
        assertEquals(0, fixture.writes.get());
        assertEquals(0, fixture.conditionalAttempts.get());
    }

    @Test
    void controlStateReturnsTheSnapshotWithAStrongEtag() throws Exception {
        Fixture fixture = new Fixture();
        Response response = new Response();
        fixture.servlet.doGet(request("/control-state", "", null, null, null), response.proxy);
        assertEquals(200, response.status);
        assertEquals("runtime", response.json().path("instanceId").asText());
        assertEquals("\"runtime.0\"", response.headers.get("ETag"));
        assertEquals(0, fixture.writes.get());
    }

    private static double backendMilliseconds(Response response) {
        String timing = response.headers.get("Server-Timing");
        assertNotNull(timing);
        assertTrue(timing.matches("backend;dur=[0-9]+(?:\\.[0-9]+)?"), timing);
        return Double.parseDouble(timing.substring("backend;dur=".length()));
    }

    private static final class Fixture {
        final DspApiServlet servlet = new DspApiServlet();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger conditionalAttempts = new AtomicInteger();
        String instanceId = "runtime";
        final AtomicInteger normalWrites = new AtomicInteger();
        final AtomicInteger interactiveWrites = new AtomicInteger();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile RuntimeException failure;
        ParameterWriteStatus status = ParameterWriteStatus.ACKNOWLEDGED;
        String errorCode;

        Fixture() throws Exception {
            Field field = DspApiServlet.class.getDeclaredField("dsp");
            field.setAccessible(true);
            field.set(servlet, service("main"));
        }

        DspService service(String id) {
            return (DspService) Proxy.newProxyInstance(DspService.class.getClassLoader(),
                    new Class<?>[]{DspService.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "dspId" -> id;
                            case "forDsp" -> service((String) args[0]);
                            case "getControlState" -> new ControlStateDto(id, instanceId, writes.get(), true,
                                    java.util.List.of(), null, SettingsCacheDto.empty());
                            case "writeParameter", "writeParameterInteractive", "writeParameterConditional" -> {
                                boolean conditional = method.getName().equals("writeParameterConditional");
                                if (conditional) {
                                    conditionalAttempts.incrementAndGet();
                                    var current = new ControlStateDto(id, instanceId, writes.get(), true,
                                            java.util.List.of(), null, SettingsCacheDto.empty());
                                    if (!current.etag().equals(args[1])) throw new DspControlConflictException(current);
                                }
                                int count = writes.incrementAndGet();
                                if (conditional ? (boolean) args[2] : method.getName().equals("writeParameterInteractive")) {
                                    interactiveWrites.incrementAndGet();
                                } else normalWrites.incrementAndGet();
                                if (entered != null) entered.countDown();
                                if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Synthetic write was never released.");
                                }
                                if (failure != null) throw failure;
                                ParameterWriteRequestDto request = (ParameterWriteRequestDto) args[0];
                                boolean acknowledged = status == ParameterWriteStatus.ACKNOWLEDGED;
                                yield new ParameterWriteResultDto("original-" + count, id, request.channelId(),
                                        request.parameter(), request.value(), request.value(), status,
                                        acknowledged, acknowledged, null, errorCode, "Synthetic result.", 42);
                            }
                            default -> throw new AssertionError("Unexpected DSP method: " + method.getName());
                        };
                    });
        }

        Response post(String prefix, String body, String key, String prefer) throws Exception {
            return post(prefix, body, key, prefer, null);
        }

        Response post(String prefix, String body, String key, String prefer, String ifMatch) throws Exception {
            Response response = new Response();
            servlet.doPost(request(prefix + "/parameters/write", body, key, prefer, ifMatch), response.proxy);
            return response;
        }
    }

    private static HttpServletRequest request(String path, String body, String key, String prefer, String ifMatch) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        ServletInputStream stream = new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> path;
                    case "getHeader" -> switch ((String) args[0]) {
                        case "Idempotency-Key" -> key;
                        case "Prefer" -> prefer;
                        case "If-Match" -> ifMatch;
                        default -> null;
                    };
                    case "getHeaders" -> java.util.Collections.enumeration(ifMatch == null
                            ? java.util.List.of() : java.util.List.of(ifMatch));
                    case "getContentLengthLong" -> (long) bytes.length;
                    case "getInputStream" -> stream;
                    default -> throw new AssertionError("Unexpected request method: " + method.getName());
                });
    }

    private static final class Response {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Map<String, String> headers = new HashMap<>();
        int status;
        final ServletOutputStream output = new ServletOutputStream() {
            @Override public void write(int value) { bytes.write(value); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
        final HttpServletResponse proxy = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> status = (int) args[0];
                        case "setHeader" -> headers.put((String) args[0], (String) args[1]);
                        case "setContentType", "setCharacterEncoding" -> { }
                        case "getOutputStream" -> { return output; }
                        default -> throw new AssertionError("Unexpected response method: " + method.getName());
                    }
                    return null;
                });

        JsonNode json() throws Exception { return MAPPER.readTree(bytes.toByteArray()); }
    }
}

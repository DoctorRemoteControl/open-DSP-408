package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.servlet.*;
import javax.servlet.http.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.*;

class DspApiServletRegistryContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFINITION = "{\"id\":\"room2\",\"host\":\"dsp.local\"}";
    private static final String WRITE = "{\"channelId\":\"out1\",\"parameter\":\"gain\",\"value\":-12}";

    @Test void registersWithSafeDefaultsListsAndInspectsWithoutConnecting() throws Exception {
        Fixture fixture = new Fixture();
        Capture created = fixture.call("POST", "/devices", DEFINITION);
        assertEquals(201, created.status);
        assertTrue(created.json().path("created").asBoolean());
        JsonNode device = created.json().path("device");
        assertEquals("room2", device.path("id").asText());
        assertEquals("dsp.local", device.path("host").asText());
        assertEquals(9761, device.path("port").asInt());
        assertEquals("auto", device.path("library").asText());
        assertFalse(device.path("autoConnect").asBoolean());
        Capture listed = fixture.call("GET", "/devices", "");
        assertEquals(200, listed.status);
        assertEquals("managed", listed.json().path("mode").asText());
        assertTrue(listed.json().path("persistent").asBoolean());
        assertEquals(device, listed.json().path("devices").get(0));
        Capture inspected = fixture.call("GET", "/devices/room2", "");
        assertEquals(200, inspected.status);
        assertEquals(device, inspected.json());
        assertEquals(0, fixture.controls.get());
    }

    @Test void identicalRegistrationReturns200ButConflictingDefinitionReturns409() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(201, fixture.call("POST", "/devices", DEFINITION).status);
        Capture retry = fixture.call("POST", "/devices", DEFINITION);
        assertEquals(200, retry.status);
        assertFalse(retry.json().path("created").asBoolean());
        Capture conflict = fixture.call("POST", "/devices", DEFINITION.replace("dsp.local", "another.local"));
        assertEquals(409, conflict.status);
        assertEquals("conflict", conflict.json().path("error").asText());
    }

    @Test void explicitDefinitionOptionsReachTheRegistry() throws Exception {
        Fixture fixture = new Fixture();
        Capture created = fixture.call("POST", "/devices",
                "{\"id\":\"ROOM2\",\"host\":\"DSP.LOCAL.\",\"port\":12345,\"library\":\"fir408\",\"autoConnect\":true}");
        assertEquals(201, created.status);
        assertEquals(new DspDeviceDefinitionDto("room2", "dsp.local", 12345, "fir408", true), fixture.definition);
    }

    @Test void removalReturns204WithNoBodyAndLaterAccessIs404() throws Exception {
        Fixture fixture = new Fixture();
        fixture.call("POST", "/devices", DEFINITION);
        Capture deleted = fixture.call("DELETE", "/devices/room2", "");
        assertEquals(204, deleted.status);
        assertEquals(0, deleted.output.size());
        assertEquals(404, fixture.call("GET", "/devices/room2", "").status);
        assertEquals(404, fixture.call("DELETE", "/devices/room2", "").status);
        assertEquals(404, fixture.call("GET", "/dsps/room2/state", "").status);
    }

    @ParameterizedTest @MethodSource("invalidDefinitions")
    void invalidDefinitionsNeverReachRegistry(String body, int status) throws Exception {
        Fixture fixture = new Fixture();
        Capture response = fixture.call("POST", "/devices", body);
        assertEquals(status, response.status, body);
        assertEquals(status == 400 ? "invalid_json" : "unprocessable_entity", response.json().path("error").asText());
        assertEquals(0, fixture.registrations.get(), body);
    }

    static Stream<Arguments> invalidDefinitions() {
        List<String> invalid = List.of("", "null", "[]", "42", "true", "{}",
                "{\"id\":\"room2\"}", "{\"host\":\"dsp.local\"}",
                DEFINITION.replace("\"room2\"", "null"), DEFINITION.replace("\"room2\"", "12"),
                DEFINITION.replace("room2", "bad/id"), DEFINITION.replace("room2", "x".repeat(65)),
                DEFINITION.replace("\"dsp.local\"", "null"), DEFINITION.replace("\"dsp.local\"", "123"),
                DEFINITION.replace("dsp.local", "http://dsp.local"), DEFINITION.replace("dsp.local", "dsp.local/path"),
                DEFINITION.replace("dsp.local", "dsp local"), DEFINITION.replace("dsp.local", "dsp.local:9761"));
        Stream<Arguments> fields = Stream.of("\"port\":0", "\"port\":65536", "\"port\":9761.0", "\"port\":1e30",
                        "\"port\":\"9761\"", "\"port\":null", "\"port\":true", "\"port\":[]", "\"port\":2147483648",
                        "\"library\":null", "\"library\":42", "\"library\":\"other\"", "\"library\":{}",
                        "\"autoConnect\":null", "\"autoConnect\":\"true\"", "\"autoConnect\":1", "\"autoConnect\":[]",
                        "\"unexpected\":true")
                .map(field -> Arguments.of(DEFINITION.substring(0, DEFINITION.length() - 1) + "," + field + "}", 422));
        return Stream.concat(Stream.concat(invalid.stream().map(body -> Arguments.of(body, 422)), fields),
                Stream.of(Arguments.of("{", 400), Arguments.of(DEFINITION + "{}", 400),
                        Arguments.of(DEFINITION.replace("}", ",\"id\":\"room3\"}"), 400)));
    }

    @ParameterizedTest @ValueSource(strings={"GET", "POST", "DELETE"})
    void unknownDeviceIdsMapTo404AcrossReadWriteAndDelete(String method) throws Exception {
        Fixture fixture = new Fixture();
        String path = method.equals("DELETE") ? "/devices/missing" : "/dsps/missing/" + (method.equals("GET") ? "state" : "connection/connect");
        Capture response = fixture.call(method, path, "");
        assertEquals(404, response.status);
        assertEquals("not_found", response.json().path("error").asText());
        assertEquals(0, fixture.controls.get());
    }

    @ParameterizedTest @ValueSource(strings={"/state", "/status", "/channels", "/capabilities", "/parameters/write", "/connection/connect", "/raw"})
    void managedFacadeRejectsUnqualifiedTargetRoutes(String path) throws Exception {
        Fixture fixture = new Fixture();
        Capture response = fixture.call(path.equals("/parameters/write") || path.equals("/connection/connect") || path.equals("/raw") ? "POST" : "GET", path, WRITE);
        assertEquals(409, response.status, path);
        assertEquals(0, fixture.controls.get());
    }

    @ParameterizedTest @ValueSource(strings={"/health", "/help", "/openapi.json", "/dsps", "/devices"})
    void headlessRegistryKeepsDiscoveryRoutesAccessible(String path) throws Exception {
        assertEquals(200, new Fixture().call("GET", path, "").status);
    }

    @ParameterizedTest @ValueSource(strings={"POST", "DELETE"})
    void foreignOriginsCannotMutateRegistry(String method) throws Exception {
        Fixture fixture = new Fixture();
        Capture response = fixture.call(method, method.equals("POST") ? "/devices" : "/devices/room2", DEFINITION,
                Map.of("Origin", "http://foreign.local:8181"));
        assertEquals(403, response.status);
        assertEquals("cross_origin_request", response.json().path("error").asText());
        assertEquals(0, fixture.registrations.get());
    }

    @Test void sameOriginDeleteIsAllowed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.call("POST", "/devices", DEFINITION);
        assertEquals(204, fixture.call("DELETE", "/devices/room2", "", Map.of("Origin", "http://dsp.local:8181")).status);
    }

    @Test void persistenceFailureCannotReturnSuccess() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failure = new DspDeviceStorageException("Registry persistence failed", new IOException("Disk write failed"));
        assertEquals(500, fixture.call("POST", "/devices", DEFINITION).status);
        fixture.failure = null;
        fixture.call("POST", "/devices", DEFINITION);
        fixture.failure = new DspDeviceStorageException("Registry persistence failed", new IOException("Disk write failed"));
        assertEquals(500, fixture.call("DELETE", "/devices/room2", "").status);
    }

    @Test void replacingSameIdCannotReplayAcknowledgementOrIssueWriteWithOldKey() throws Exception {
        Fixture fixture = new Fixture();
        fixture.call("POST", "/devices", DEFINITION);
        Map<String,String> key = Map.of("Idempotency-Key", "same-device-key-0001");
        Capture first = fixture.call("POST", "/dsps/room2/parameters/write", WRITE, key);
        assertEquals(200, first.status);
        assertEquals(1, fixture.controls.get());
        assertEquals(200, fixture.call("POST", "/dsps/room2/parameters/write", WRITE, key).status);
        assertEquals(1, fixture.controls.get());
        fixture.call("DELETE", "/devices/room2", "");
        fixture.call("POST", "/devices", DEFINITION);
        Capture replay = fixture.call("POST", "/dsps/room2/parameters/write", WRITE, key);
        assertEquals(409, replay.status);
        assertFalse(replay.json().has("acknowledged"));
        assertEquals(1, fixture.controls.get(), "An old key must not execute a write on a replacement device.");
        assertEquals(200, fixture.call("POST", "/dsps/room2/parameters/write", WRITE,
                Map.of("Idempotency-Key", "new-device-key-00002")).status);
        assertEquals(2, fixture.controls.get());
    }

    @Test void cachedParameterResultBelongsToConcreteServiceIdentity() throws Exception {
        Fixture fixture = new Fixture();
        fixture.definition = new DspDeviceDefinitionDto("room2", "dsp.local", 9761, "auto", false);
        fixture.selected = fixture.newTarget();
        Map<String,String> key = Map.of("Idempotency-Key", "identity-key-000001");
        assertEquals(200, fixture.call("POST", "/dsps/room2/parameters/write", WRITE, key).status);
        fixture.selected = fixture.newTarget();
        Capture replay = fixture.call("POST", "/dsps/room2/parameters/write", WRITE, key);
        assertEquals(409, replay.status);
        assertEquals(1, fixture.controls.get());
    }

    @ParameterizedTest @ValueSource(strings={"GET", "POST"})
    void registryAdministrationIsOnlyExposedAtRoot(String method) throws Exception {
        Fixture fixture = new Fixture();
        fixture.definition = new DspDeviceDefinitionDto("room2", "dsp.local", 9761, "auto", false);
        fixture.selected = fixture.newTarget();
        Capture response = fixture.call(method, "/dsps/room2/devices", DEFINITION);
        assertEquals(404, response.status);
        assertEquals(0, fixture.registrations.get());
    }

    private static class Fixture implements InvocationHandler {
        final AtomicInteger registrations = new AtomicInteger();
        final AtomicInteger controls = new AtomicInteger();
        final DspApiServlet servlet = new DspApiServlet();
        final DspService service = proxy(this);
        DspDeviceDefinitionDto definition;
        DspService selected;
        RuntimeException failure;
        Fixture() throws Exception {
            Field field=DspApiServlet.class.getDeclaredField("dsp");field.setAccessible(true);field.set(servlet,service);
        }
        Capture call(String method,String path,String body)throws Exception{return call(method,path,body,Map.of());}
        Capture call(String method,String path,String body,Map<String,String> headers)throws Exception {
            Capture response=new Capture();HttpServletRequest request=request(method,path,body,headers);
            servlet.service((ServletRequest)request,response.response());
            return response;
        }
        @Override public Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
            return switch(method.getName()) {
                case "dspId" -> "";
                case "helpText" -> "DSP API help";
                case "isConnected" -> false;
                case "getDeviceRegistry" -> new DspDeviceRegistryDto("managed",true,definition==null?List.of():List.of(definition));
                case "getDeviceDefinition" -> InvocationHandler.invokeDefault(proxy,method,args);
                case "getDspInstances" -> List.of();
                case "registerDevice" -> {
                    registrations.incrementAndGet();if(failure!=null)throw failure;
                    DspDeviceDefinitionDto incoming=(DspDeviceDefinitionDto)args[0];
                    boolean created=definition==null;
                    if(!created&&!definition.equals(incoming))throw new DspDeviceConflictException("Definition conflicts");
                    definition=incoming;if(created)selected=newTarget();
                    yield new DspDeviceRegistrationDto(definition,created);
                }
                case "removeDevice" -> {
                    if(failure!=null)throw failure;
                    if(definition==null||!definition.id().equals(args[0]))throw new DspDeviceNotFoundException("Missing device");
                    definition=null;selected=null;yield null;
                }
                case "forDsp" -> {
                    if(definition==null||!definition.id().equals(args[0]))throw new DspDeviceNotFoundException("Missing device");
                    yield selected;
                }
                default -> throw new AssertionError("Unexpected facade operation: "+method.getName());
            };
        }
        DspService newTarget() {
            return proxy((p,m,a)->switch(m.getName()) {
                case "dspId" -> "room2";
                case "getDeviceRegistry" -> new DspDeviceRegistryDto("unavailable", false, List.of());
                case "registerDevice" -> throw new UnsupportedOperationException("Child registration unsupported");
                case "writeParameter" -> {
                    int count=controls.incrementAndGet();
                    yield new ParameterWriteResultDto("write-"+count,"room2","out1","gain",-12.0,-12.0,
                            ParameterWriteStatus.ACKNOWLEDGED,true,true,null,null,"Written",42);
                }
                default -> throw new AssertionError("Unexpected target operation: "+m.getName());
            });
        }
    }
    private static DspService proxy(InvocationHandler handler){return (DspService)Proxy.newProxyInstance(DspService.class.getClassLoader(),new Class[]{DspService.class},handler);}
    private static HttpServletRequest request(String method,String path,String body,Map<String,String> headers) {
        ByteArrayInputStream input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        ServletInputStream stream=new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){}};
        return (HttpServletRequest)Proxy.newProxyInstance(DspApiServletRegistryContractTest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()){
            case "getMethod" -> method;case "getPathInfo" -> path;case "getInputStream" -> stream;case "getContentLengthLong" -> (long)body.getBytes(StandardCharsets.UTF_8).length;
            case "getHeader" -> headers.get(a[0]);case "getScheme" -> "http";case "getServerName" -> "dsp.local";case "getServerPort" -> 8181;
            case "getProtocol" -> "HTTP/1.1";default -> primitive(m.getReturnType());
        });
    }
    private static Object primitive(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static class Capture {
        int status=200;final ByteArrayOutputStream output=new ByteArrayOutputStream();
        HttpServletResponse response(){
            ServletOutputStream stream=new ServletOutputStream(){public void write(int b){output.write(b);}public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}};
            return (HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->{
                if(m.getName().equals("setStatus")||m.getName().equals("sendError"))status=(int)a[0];
                if(m.getName().equals("getOutputStream"))return stream;return primitive(m.getReturnType());
            });
        }
        JsonNode json()throws Exception{return MAPPER.readTree(output.toByteArray());}
    }
}

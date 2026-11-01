package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.service.http.*;

class DspWebAppServletTest {
    @Test void servesOnlyPackagedAssetsWithExplicitMimeAndBrowserPolicy() throws Exception {
        Capture capture=new Capture();
        new DspWebAppServlet().doGet(request("/model.mjs",null),capture.response());
        assertEquals(200,capture.status);
        assertEquals("text/javascript",capture.type);
        assertTrue(capture.output.toString(StandardCharsets.UTF_8).contains("export class DspSession"));
        assertEquals("no-store",capture.headers.get("Cache-Control"));
        assertEquals("nosniff",capture.headers.get("X-Content-Type-Options"));
        assertTrue(capture.headers.get("Content-Security-Policy").contains("connect-src 'self'"));
        assertTrue(capture.headers.get("Content-Security-Policy").contains("frame-ancestors 'none'"));
    }
    @ParameterizedTest @ValueSource(strings={"/../pom.xml","/%2e%2e/pom.xml","/META-INF/MANIFEST.MF","//model.mjs","/unknown.mjs","/model.mjs/extra"})
    void unknownOrTraversingPathsNeverExposeResources(String path) throws Exception {
        Capture capture=new Capture();new DspWebAppServlet().doGet(request(path,null),capture.response());assertEquals(404,capture.status);
    }
    @Test void bareAliasRedirectsToTrailingSlashForRelativeModules() throws Exception {
        Capture capture=new Capture();new DspWebAppServlet().doGet(request(null,null),capture.response());assertEquals("/app/",capture.redirect);
    }
    @Test void ownsOnlyItsOwnHttpAlias() throws Exception {
        List<String> events=new ArrayList<>();
        HttpService service=(HttpService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpService.class},(p,m,a)->{
            if(m.getName().equals("registerServlet")){assertSame(DspWebAppServlet.class,a[1].getClass());events.add("register:"+a[0]);}
            if(m.getName().equals("unregister"))events.add("unregister:"+a[0]);return null;
        });
        DspWebAppServlet servlet=new DspWebAppServlet();servlet.bindHttpService(service);servlet.activate(Map.of("enabled",true));servlet.deactivate();servlet.deactivate();
        assertEquals(List.of("register:/app","unregister:/app"),events);
    }
    @Test void interfaceIsDisabledWhenNoConfigurationOptsIn() throws Exception {
        List<String> events=new ArrayList<>();
        HttpService service=httpService(events);
        DspWebAppServlet servlet=new DspWebAppServlet();servlet.bindHttpService(service);
        servlet.activate(Map.of());servlet.deactivate();
        assertTrue(events.isEmpty(),"A headless HTTP installation must not register /app.");
    }
    @Test void interfaceCanBeEnabledAndDisabledWithoutDuplicateAliasRegistrations() throws Exception {
        List<String> events=new ArrayList<>();
        DspWebAppServlet servlet=new DspWebAppServlet();servlet.bindHttpService(httpService(events));
        servlet.activate(Map.of());
        servlet.modified(Map.of("enabled",true));
        servlet.modified(Map.of("enabled",true));
        servlet.modified(Map.of("enabled",false));
        servlet.modified(Map.of());
        servlet.modified(Map.of("enabled",true));
        servlet.deactivate();servlet.deactivate();
        assertEquals(List.of("register:/app","unregister:/app","register:/app","unregister:/app"),events);
    }
    private static HttpService httpService(List<String> events) {
        return (HttpService)Proxy.newProxyInstance(DspWebAppServletTest.class.getClassLoader(),new Class[]{HttpService.class},(p,m,a)->{
            if(m.getName().equals("registerServlet"))events.add("register:"+a[0]);
            if(m.getName().equals("unregister"))events.add("unregister:"+a[0]);return null;
        });
    }
    @ParameterizedTest @ValueSource(strings={"http://other.local:8181","null","https://dsp.local:8181","http://dsp.local:8182","http://user@dsp.local:8181","http://dsp.local:8181/path"})
    void foreignBrowserWritesRejectBeforeResolvingAnyDevice(String origin) throws Exception {
        Capture capture=new Capture();new DspApiServlet().doPost(request("/parameters/write",origin),capture.response());
        assertEquals(403,capture.status);assertTrue(capture.output.toString(StandardCharsets.UTF_8).contains("cross_origin_request"));
    }
    @Test void directSameOriginAndNativeCallersRemainSupported() {
        assertTrue(DspBrowserOriginPolicy.allowed(request("/",null)));
        assertTrue(DspBrowserOriginPolicy.allowed(request("/","http://dsp.local:8181")));
        assertTrue(DspBrowserOriginPolicy.allowed(request("/","http://DSP.LOCAL:8181")));
    }
    private static HttpServletRequest request(String path,String origin) {
        return (HttpServletRequest)Proxy.newProxyInstance(DspWebAppServletTest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()){
            case "getPathInfo" -> path;
            case "getContextPath" -> "";
            case "getScheme" -> "http";
            case "getServerName" -> "dsp.local";
            case "getServerPort" -> 8181;
            case "getHeader" -> "Origin".equals(a[0])?origin:null;
            default -> primitive(m.getReturnType());
        });
    }
    private static Object primitive(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static class Capture {
        int status=200;String type,redirect;Map<String,String> headers=new HashMap<>();ByteArrayOutputStream output=new ByteArrayOutputStream();
        HttpServletResponse response() {
            ServletOutputStream stream=new ServletOutputStream(){public void write(int b){output.write(b);}public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}};
            return (HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->{
                switch(m.getName()){
                    case "setStatus","sendError" -> status=(Integer)a[0];
                    case "setContentType" -> type=(String)a[0];
                    case "setHeader" -> headers.put((String)a[0],(String)a[1]);
                    case "sendRedirect" -> redirect=(String)a[0];
                    case "getOutputStream" -> {return stream;}
                }
                return primitive(m.getReturnType());
            });
        }
    }
}

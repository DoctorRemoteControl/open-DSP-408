package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Dictionary;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

class DspApiServletRegistrationTest {
    @Test
    void registersAndUnregistersThroughTheBundleScopedHttpService() throws Exception {
        DspApiServlet servlet = new DspApiServlet();
        RecordingHttpService http = new RecordingHttpService();

        servlet.bindHttpService(http);
        servlet.activate();

        assertEquals("/api/v1", http.registeredAlias);
        assertSame(servlet, http.registeredServlet);

        servlet.deactivate();

        assertEquals("/api/v1", http.unregisteredAlias);
        assertNull(http.registeredServlet);
    }

    private static final class RecordingHttpService implements HttpService {
        private String registeredAlias;
        private Servlet registeredServlet;
        private String unregisteredAlias;

        @Override
        public void registerServlet(String alias,
                                    Servlet servlet,
                                    Dictionary<?, ?> initParams,
                                    HttpContext context)
                throws ServletException, NamespaceException {
            registeredAlias = alias;
            registeredServlet = servlet;
        }

        @Override
        public void registerResources(String alias, String name, HttpContext context)
                throws NamespaceException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unregister(String alias) {
            unregisteredAlias = alias;
            registeredServlet = null;
        }

        @Override
        public HttpContext createDefaultHttpContext() {
            throw new UnsupportedOperationException();
        }
    }
}

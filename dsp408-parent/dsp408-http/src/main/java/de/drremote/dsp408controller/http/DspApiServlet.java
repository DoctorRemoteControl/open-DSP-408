package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.DspDeviceNotFoundException;
import de.drremote.dsp408.api.DspDeviceStorageException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component(immediate = true, service = {})
public final class DspApiServlet extends HttpServlet {
    static final String HTTP_ALIAS = "/api/v1";

    private static final Logger LOG = LoggerFactory.getLogger(DspApiServlet.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DspHttpRequestParser requestParser = new DspHttpRequestParser(mapper);
    private final DspHttpResponses responses = new DspHttpResponses(mapper);
    private final DspGetRouteHandler getRoutes = new DspGetRouteHandler(responses);
    private final DspDeviceRegistryHandler registryRoutes = new DspDeviceRegistryHandler(mapper, responses);
    private final DspParameterPostHandler parameterPosts = new DspParameterPostHandler(mapper, responses);
    private final JsonPresetApplier presetApplier = new JsonPresetApplier();
    private final DspAdministrationPostHandler administrationPosts = new DspAdministrationPostHandler(
            requestParser,
            responses,
            presetApplier
    );
    private final DspChannelPostHandler channelPosts = new DspChannelPostHandler(
            requestParser,
            responses
    );
    private final DspFilterPostHandler filterPosts = new DspFilterPostHandler(
            requestParser,
            responses
    );
    private final DspDynamicsPostHandler dynamicsPosts = new DspDynamicsPostHandler(
            requestParser,
            responses
    );
    private final DspSystemPostHandler systemPosts = new DspSystemPostHandler(
            requestParser,
            responses
    );

    @Reference
    private DspService dsp;

    private HttpService httpService;
    private boolean httpRegistered;

    @Reference
    void bindHttpService(HttpService service) {
        httpService = service;
    }

    void unbindHttpService(HttpService service) {
        if (httpService == service) {
            httpService = null;
        }
    }

    @Activate
    void activate() throws ServletException, NamespaceException {
        httpService.registerServlet(HTTP_ALIAS, this, null, null);
        httpRegistered = true;
        LOG.info("DSP408 HTTP API registered at {}", HTTP_ALIAS);
    }

    @Deactivate
    void deactivate() {
        if (!httpRegistered || httpService == null) {
            return;
        }
        try {
            httpService.unregister(HTTP_ALIAS);
        } catch (IllegalArgumentException e) {
            LOG.debug("DSP408 HTTP alias was already unregistered", e);
        } finally {
            httpRegistered = false;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            RequestContext context = requestContext(req);
            if (!registryRoutes.get(context.path(), context.dsp(), resp)
                    && !getRoutes.handle(context.path(), context.dsp(), resp)) {
                sendError(resp, 404, "not_found", "Unknown endpoint.");
            }
        } catch (DspDeviceNotFoundException e) {
            sendError(resp, 404, "not_found", e.getMessage());
        } catch (DspDeviceStorageException e) {
            sendError(resp, 500, "internal_error", e.getMessage());
        } catch (RejectedExecutionException e) {
            sendError(resp, 503, "dsp_overloaded", e.getMessage());
        } catch (UnsupportedOperationException e) {
            sendError(resp, 409, "unsupported_operation", e.getMessage());
        } catch (IllegalStateException e) {
            sendError(resp, 409, "conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(resp, 422, "unprocessable_entity", e.getMessage());
        } catch (Exception e) {
            LOG.error("HTTP GET failed", e);
            sendError(resp, 500, "internal_error", "Unexpected server error.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!DspBrowserOriginPolicy.allowed(req)) {
            sendError(resp, 403, "cross_origin_request", "Browser writes must come from the DSP server origin.");
            return;
        }
        String selectedDspId = null;
        try {
            RequestContext context = requestContext(req);
            String path = context.path();
            DspService dsp = context.dsp();
            selectedDspId = dsp.dspId();

            if (registryRoutes.post(path, dsp, req, resp)) {
                return;
            }

            if (parameterPosts.handle(path, dsp, req, resp)) {
                return;
            }

            if (administrationPosts.handle(path, dsp, req, resp)) {
                return;
            }

            if (channelPosts.handle(path, dsp, req, resp)) {
                return;
            }

            if (filterPosts.handle(path, dsp, req, resp)) {
                return;
            }

            if (dynamicsPosts.handle(path, dsp, req, resp)) {
                return;
            }

            if (systemPosts.handle(path, dsp, req, resp)) {
                return;
            }

            sendError(resp, 404, "not_found", "Unknown endpoint.");
        } catch (JsonProcessingException e) {
            sendError(resp, 400, "invalid_json", "Request body is not valid JSON.");
        } catch (DspDeviceNotFoundException e) {
            sendError(resp, 404, "not_found", e.getMessage());
        } catch (DspDeviceStorageException e) {
            sendError(resp, 500, "internal_error", e.getMessage());
        } catch (JsonPresetApplier.PresetApplyException e) {
            writeJson(resp, e.httpStatus(), e.response(selectedDspId));
        } catch (RejectedExecutionException e) {
            sendError(resp, 503, "dsp_overloaded", e.getMessage());
        } catch (UnsupportedOperationException e) {
            sendError(resp, 409, "unsupported_operation", e.getMessage());
        } catch (IllegalStateException e) {
            sendError(resp, 409, "conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(resp, 422, "unprocessable_entity", e.getMessage());
        } catch (Exception e) {
            LOG.error("HTTP POST failed", e);
            sendError(resp, 500, "internal_error", "Unexpected server error.");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!DspBrowserOriginPolicy.allowed(req)) {
            sendError(resp, 403, "cross_origin_request", "Browser writes must come from the DSP server origin.");
            return;
        }
        try {
            String path = normalizePath(req.getPathInfo());
            if (!registryRoutes.delete(path, dsp, resp)) {
                sendError(resp, 404, "not_found", "Unknown endpoint.");
            }
        } catch (DspDeviceNotFoundException e) {
            sendError(resp, 404, "not_found", e.getMessage());
        } catch (DspDeviceStorageException e) {
            sendError(resp, 500, "internal_error", e.getMessage());
        } catch (RejectedExecutionException e) {
            sendError(resp, 503, "dsp_overloaded", e.getMessage());
        } catch (UnsupportedOperationException e) {
            sendError(resp, 409, "unsupported_operation", e.getMessage());
        } catch (IllegalStateException e) {
            sendError(resp, 409, "conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(resp, 422, "unprocessable_entity", e.getMessage());
        } catch (Exception e) {
            LOG.error("HTTP DELETE failed", e);
            sendError(resp, 500, "internal_error", "Unexpected server error.");
        }
    }

    private RequestContext requestContext(HttpServletRequest req) {
        String path = normalizePath(req.getPathInfo());
        if (!path.startsWith("/dsps/")) {
            if (!isRegistryRoute(path) && dsp.dspId().isEmpty()) {
                throw new IllegalStateException("Select a DSP using /dsps/{id} for device operations.");
            }
            return new RequestContext(dsp, path);
        }

        String rest = path.substring("/dsps/".length());
        int slash = rest.indexOf('/');
        String dspId = slash < 0 ? rest : rest.substring(0, slash);
        String selectedPath = slash < 0 ? "/state" : rest.substring(slash);
        if (selectedPath.isBlank()) {
            selectedPath = "/state";
        }
        if (selectedPath.equals("/devices") || selectedPath.startsWith("/devices/")) {
            throw new DspDeviceNotFoundException("Registry administration is only available at /devices.");
        }
        try {
            return new RequestContext(dsp.forDsp(dspId), normalizePath(selectedPath));
        } catch (IllegalArgumentException unknownTarget) {
            throw new DspDeviceNotFoundException(unknownTarget.getMessage());
        }
    }

    private static boolean isRegistryRoute(String path) {
        return path.equals("/") || path.equals("/health") || path.equals("/help") || path.equals("/openapi.json")
                || path.equals("/dsps") || path.equals("/devices") || path.startsWith("/devices/");
    }

    private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        responses.writeJson(resp, status, body);
    }

    private void sendError(HttpServletResponse resp, int status, String error, String message) throws IOException {
        responses.sendError(resp, status, error, message);
    }

    private String normalizePath(String path) {
        return DspHttpRequestParser.normalizePath(path);
    }
    static boolean isDirectChannelGainPath(String path) {
        return DspChannelPostHandler.isDirectChannelGainPath(path);
    }

    private record RequestContext(DspService dsp, String path) {
    }
}

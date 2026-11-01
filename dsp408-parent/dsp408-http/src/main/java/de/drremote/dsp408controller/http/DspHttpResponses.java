package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DspService;

/** Writes the common JSON response envelopes used by the HTTP API. */
final class DspHttpResponses {
    private final ObjectMapper mapper;

    DspHttpResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> state(DspService dsp) {
        return Map.ofEntries(
                Map.entry("dspId", dsp.dspId()),
                Map.entry("connected", dsp.isConnected()),
                Map.entry("deviceInfo", dsp.getDeviceInfo()),
                Map.entry("channels", dsp.getChannels()),
                Map.entry("settingsCache", dsp.getSettingsCacheStatus()),
                Map.entry("cachedBlockIndices", dsp.getCachedBlockIndices()),
                Map.entry("matrixOutputs", dsp.getMatrixOutputs()),
                Map.entry("inputGates", dsp.getInputGates()),
                Map.entry("inputCompressors", dsp.getInputCompressors()),
                Map.entry("limiters", dsp.getLimiters()),
                Map.entry("meters", dsp.getMeters())
        );
    }

    Map<String, Object> action(String message) {
        return Map.of(
                "ok", true,
                "message", message
        );
    }

    Map<String, Object> text(DspService dsp, String command, String text) {
        return Map.of(
                "ok", true,
                "dspId", dsp.dspId(),
                "command", command,
                "connected", dsp.isConnected(),
                "text", text == null ? "" : text
        );
    }

    void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }

    void sendError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        writeJson(response, status, Map.of(
                "error", error,
                "message", message,
                "status", status
        ));
    }
}

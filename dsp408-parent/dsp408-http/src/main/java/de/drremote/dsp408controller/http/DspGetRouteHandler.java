package de.drremote.dsp408controller.http;

import java.io.InputStream;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.InputGateDto;

/** Routes read-only HTTP API endpoints. */
final class DspGetRouteHandler {
    private final DspHttpResponses responses;

    DspGetRouteHandler(DspHttpResponses responses) {
        this.responses = responses;
    }

    boolean handle(String path, DspService dsp, HttpServletResponse response) throws Exception {
        if (path.equals("/") || path.equals("/health")) {
            responses.writeJson(response, 200, Map.of(
                    "service", "dsp408-http",
                    "ok", true
            ));
            return true;
        }

        if (path.equals("/dsps")) {
            responses.writeJson(response, 200, Map.of(
                    "defaultDsp", dsp.dspId(),
                    "dsps", dsp.getDspInstances()
            ));
            return true;
        }

        if (path.equals("/help")) {
            responses.writeJson(response, 200, responses.text(dsp, "help", dsp.helpText()));
            return true;
        }

        if (path.equals("/openapi.json")) {
            writeOpenApi(response);
            return true;
        }

        if (path.equals("/state") || path.equals("/status")) {
            responses.writeJson(response, 200, responses.state(dsp));
            return true;
        }

        if (path.equals("/traffic") || path.equals("/diagnostics/traffic")) {
            responses.writeJson(response, 200, dsp.getTrafficStatus());
            return true;
        }

        if (path.equals("/settings-cache") || path.equals("/cache/status")) {
            responses.writeJson(response, 200, dsp.getSettingsCacheStatus());
            return true;
        }

        if (path.equals("/capabilities")) {
            responses.writeJson(response, 200, dsp.getCapabilities());
            return true;
        }

        if (path.equals("/preset/export")) {
            responses.writeJson(response, 200, dsp.exportPresetSnapshot());
            return true;
        }

        if (path.equals("/auto-limit/lock")) {
            responses.writeJson(response, 200, dsp.getAutoLimitLease());
            return true;
        }

        if (path.equals("/auto-limit/status")) {
            responses.writeJson(response, 200, dsp.getAutoLimitStatus());
            return true;
        }

        if (path.equals("/device") || path.equals("/deviceinfo") || path.equals("/info")) {
            responses.writeJson(response, 200, dsp.getDeviceInfo());
            return true;
        }

        if (path.equals("/meters")) {
            responses.writeJson(response, 200, Map.of(
                    "dspId", dsp.dspId(),
                    "connected", dsp.isConnected(),
                    "meters", dsp.getMeters()
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/meter")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/meter".length());
            responses.writeJson(response, 200, Map.of(
                    "dspId", dsp.dspId(),
                    "connected", dsp.isConnected(),
                    "meter", dsp.getMeter(channelId)
            ));
            return true;
        }

        if (path.equals("/channels")) {
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "channels", dsp.getChannels()
            ));
            return true;
        }

        if (path.equals("/matrix")) {
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "outputs", dsp.getMatrixOutputs()
            ));
            return true;
        }

        if (path.startsWith("/matrix/")) {
            String outputId = path.substring("/matrix/".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "output", dsp.getMatrixOutput(outputId)
            ));
            return true;
        }

        if (path.equals("/peq")) {
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "channels", dsp.getPeqChannels()
            ));
            return true;
        }

        if (path.startsWith("/peq/")) {
            String channelId = path.substring("/peq/".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "channel", dsp.getPeqChannel(channelId)
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/gate")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/gate".length());
            InputGateDto gate = dsp.getInputGates().stream()
                    .filter(value -> value.id().equalsIgnoreCase(channelId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown input channel: " + channelId));
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "gate", gate
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/compressor")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/compressor".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "compressor", dsp.getInputCompressor(channelId)
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/limiter")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/limiter".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "limiter", dsp.getLimiter(channelId)
            ));
            return true;
        }

        if (path.startsWith("/channels/")) {
            String channelId = path.substring("/channels/".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "channel", dsp.getChannel(channelId)
            ));
            return true;
        }

        if (path.equals("/blocks")) {
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "settingsCache", dsp.getSettingsCacheStatus(),
                    "cachedBlockIndices", dsp.getCachedBlockIndices()
            ));
            return true;
        }

        if (path.startsWith("/blocks/")) {
            String blockIndex = path.substring("/blocks/".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "block", dsp.getCachedBlock(blockIndex)
            ));
            return true;
        }

        if (path.equals("/volume") || path.equals("/volume/status")) {
            responses.writeJson(
                    response,
                    200,
                    responses.text(dsp, "volume status", dsp.executeVolumeRoom("!dsp volume status"))
            );
            return true;
        }

        if (path.equals("/volume/help")) {
            responses.writeJson(
                    response,
                    200,
                    responses.text(dsp, "volume help", dsp.executeVolumeRoom("!dsp volume help"))
            );
            return true;
        }

        return false;
    }

    private void writeOpenApi(HttpServletResponse response) throws Exception {
        try (InputStream input = DspGetRouteHandler.class.getResourceAsStream("/openapi/dsp408-openapi.json")) {
            if (input == null) {
                responses.sendError(response, 404, "not_found", "OpenAPI resource not found.");
                return;
            }
            response.setStatus(200);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            input.transferTo(response.getOutputStream());
        }
    }
}

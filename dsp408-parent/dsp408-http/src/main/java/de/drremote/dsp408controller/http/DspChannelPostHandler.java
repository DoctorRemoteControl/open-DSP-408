package de.drremote.dsp408controller.http;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.StateDto;

/** Routes basic channel, meter and matrix write endpoints. */
final class DspChannelPostHandler {
    private final DspHttpRequestParser requestParser;
    private final DspHttpResponses responses;

    DspChannelPostHandler(DspHttpRequestParser requestParser, DspHttpResponses responses) {
        this.requestParser = requestParser;
        this.responses = responses;
    }

    boolean handle(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (path.equals("/delay-unit")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(
                    response,
                    200,
                    dsp.setDelayUnit(requestParser.requireString(body, "unit"))
            );
            return true;
        }

        if (path.equals("/meters/read")) {
            if (!dsp.isConnected()) {
                sendDisconnected(response, dsp);
                return true;
            }
            StateDto state;
            try {
                state = dsp.requestRuntimeMeters();
            } catch (IllegalStateException e) {
                // The connection can disappear after the preflight check.
                if (dsp.isConnected()) {
                    throw e;
                }
                sendDisconnected(response, dsp);
                return true;
            }
            if (!state.connected() || !dsp.isConnected()) {
                sendDisconnected(response, dsp);
                return true;
            }
            responses.writeJson(response, 200, state);
            return true;
        }

        if (path.equals("/matrix/route")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            String output = requestParser.requireString(body, "output");
            if (body.containsKey("inputs")) {
                responses.writeJson(
                        response,
                        200,
                        dsp.setMatrixRoutes(output, requestParser.requireStringList(body, "inputs"))
                );
            } else {
                responses.writeJson(
                        response,
                        200,
                        dsp.setMatrixRoute(output, requestParser.requireString(body, "input"))
                );
            }
            return true;
        }

        if (path.equals("/matrix/crosspoint-gain")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setMatrixCrosspointGain(
                    requestParser.requireString(body, "output"),
                    requestParser.requireString(body, "input"),
                    requestParser.requireDouble(body, "db")
            ));
            return true;
        }

        if (isDirectChannelGainPath(path)) {
            String channelId = path.substring("/channels/".length(), path.length() - "/gain".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            double db = requestParser.requireDouble(body, "db");
            responses.writeJson(response, 200, Map.of(
                    "ok", true,
                    "message", "Gain set.",
                    "channel", dsp.setGain(channelId, db)
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/name")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/name".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(
                    response,
                    200,
                    dsp.setChannelName(channelId, requestParser.requireString(body, "name"))
            );
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/phase")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/phase".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, Map.of(
                    "ok", true,
                    "message", "Phase set.",
                    "channel", dsp.setPhase(channelId, requestParser.requireBoolean(body, "inverted"))
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/delay")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/delay".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, Map.of(
                    "ok", true,
                    "message", "Delay set.",
                    "channel", dsp.setDelay(channelId, requestParser.requireDouble(body, "ms"))
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/mute")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/mute".length());
            responses.writeJson(response, 200, Map.of(
                    "ok", true,
                    "message", "Muted.",
                    "channel", dsp.mute(channelId)
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/unmute")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/unmute".length());
            responses.writeJson(response, 200, Map.of(
                    "ok", true,
                    "message", "Unmuted.",
                    "channel", dsp.unmute(channelId)
            ));
            return true;
        }

        return false;
    }

    static boolean isDirectChannelGainPath(String path) {
        return path != null && path.matches("/channels/[^/]+/gain");
    }

    private void sendDisconnected(HttpServletResponse response, DspService dsp) throws IOException {
        responses.sendError(response, 409, "dsp_not_connected", "DSP " + dsp.dspId() + " is not connected.");
    }
}

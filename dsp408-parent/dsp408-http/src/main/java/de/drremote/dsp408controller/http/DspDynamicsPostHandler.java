package de.drremote.dsp408controller.http;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;

/** Routes gate, compressor, limiter and Auto-Limit write endpoints. */
final class DspDynamicsPostHandler {
    private final DspHttpRequestParser requestParser;
    private final DspHttpResponses responses;

    DspDynamicsPostHandler(DspHttpRequestParser requestParser, DspHttpResponses responses) {
        this.requestParser = requestParser;
        this.responses = responses;
    }

    boolean handle(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (path.startsWith("/channels/") && path.contains("/gate")) {
            if (handleGate(path, dsp, request, response)) {
                return true;
            }
        }

        if (path.startsWith("/channels/") && path.endsWith("/compressor")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/compressor".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.setCompressor(
                    channelId,
                    requestParser.requireDouble(body, "thresholdDb"),
                    requestParser.requireString(body, "ratio"),
                    requestParser.requireDouble(body, "attackMs"),
                    requestParser.requireDouble(body, "releaseMs"),
                    requestParser.requireDouble(body, "kneeDb")
            ));
            return true;
        }

        if (path.startsWith("/channels/") && path.endsWith("/limiter")) {
            String channelId = path.substring("/channels/".length(), path.length() - "/limiter".length());
            Map<String, Object> body = requestParser.readJsonBody(request);
            if (dsp.getAutoLimitLease().active()) {
                String leaseToken = requestParser.optionalString(body, "leaseToken");
                if (!dsp.ownsAutoLimitLease(leaseToken)) {
                    responses.sendError(
                            response,
                            409,
                            "auto_limit_locked",
                            "Auto-Limit is already running on another client."
                    );
                    return true;
                }
            }
            responses.writeJson(response, 200, dsp.setLimiter(
                    channelId,
                    requestParser.requireDouble(body, "thresholdDb"),
                    requestParser.requireDouble(body, "attackMs"),
                    requestParser.requireDouble(body, "releaseMs")
            ));
            return true;
        }

        if (path.equals("/auto-limit/lock")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            var lease = dsp.acquireAutoLimitLease(
                    requestParser.requireString(body, "owner"),
                    requestParser.optionalLong(body, "ttlMs", 30_000L)
            );
            responses.writeJson(response, lease.acquired() ? 200 : 409, lease);
            return true;
        }

        if (path.equals("/auto-limit/start")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 202, dsp.startAutoLimit(
                    requestParser.optionalDouble(body, "headroomDb", 1.0),
                    requestParser.optionalString(body, "targetMode"),
                    requestParser.optionalLong(body, "measurementMs", 6_000L)
            ));
            return true;
        }

        if (path.equals("/auto-limit/cancel")) {
            responses.writeJson(response, 200, dsp.cancelAutoLimit());
            return true;
        }

        if (path.equals("/auto-limit/undo")) {
            responses.writeJson(response, 202, dsp.undoAutoLimit());
            return true;
        }

        if (path.equals("/auto-limit/lock/renew")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            var lease = dsp.renewAutoLimitLease(
                    requestParser.requireString(body, "token"),
                    requestParser.optionalLong(body, "ttlMs", 30_000L)
            );
            responses.writeJson(response, lease.acquired() ? 200 : 409, lease);
            return true;
        }

        if (path.equals("/auto-limit/lock/release")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            var lease = dsp.releaseAutoLimitLease(requestParser.requireString(body, "token"));
            responses.writeJson(response, lease.acquired() ? 200 : 409, lease);
            return true;
        }

        return false;
    }

    private boolean handleGate(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        String[] parts = path.split("/");
        String channelId = parts[2];
        String field = parts.length > 4 ? parts[4] : "";
        Map<String, Object> body = requestParser.readJsonBody(request);
        if (field.isBlank()) {
            responses.writeJson(response, 200, dsp.setInputGate(
                    channelId,
                    requestParser.requireDouble(body, "thresholdDb"),
                    requestParser.requireDouble(body, "holdMs"),
                    requestParser.requireDouble(body, "attackMs"),
                    requestParser.requireDouble(body, "releaseMs")
            ));
            return true;
        }
        if ("threshold".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setInputGateThreshold(channelId, requestParser.requireDouble(body, "thresholdDb"))
            );
            return true;
        }
        if ("hold".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setInputGateHold(channelId, requestParser.requireDouble(body, "holdMs"))
            );
            return true;
        }
        if ("attack".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setInputGateAttack(channelId, requestParser.requireDouble(body, "attackMs"))
            );
            return true;
        }
        if ("release".equalsIgnoreCase(field)) {
            responses.writeJson(
                    response,
                    200,
                    dsp.setInputGateRelease(channelId, requestParser.requireDouble(body, "releaseMs"))
            );
            return true;
        }
        return false;
    }
}

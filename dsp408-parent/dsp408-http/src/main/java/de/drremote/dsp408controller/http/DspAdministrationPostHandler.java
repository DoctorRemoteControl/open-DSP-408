package de.drremote.dsp408controller.http;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;

/** Routes connection, command and native/JSON preset administration endpoints. */
final class DspAdministrationPostHandler {
    private final DspHttpRequestParser requestParser;
    private final DspHttpResponses responses;
    private final JsonPresetApplier presetApplier;

    DspAdministrationPostHandler(
            DspHttpRequestParser requestParser,
            DspHttpResponses responses,
            JsonPresetApplier presetApplier
    ) {
        this.requestParser = requestParser;
        this.responses = responses;
        this.presetApplier = presetApplier;
    }

    boolean handle(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (path.equals("/connection/connect")) {
            responses.writeJson(response, 200, responses.action(dsp.connect()));
            return true;
        }

        if (path.equals("/connection/reconnect")) {
            responses.writeJson(response, 200, responses.action(dsp.reconnect()));
            return true;
        }

        if (path.equals("/connection/disconnect")) {
            dsp.disconnect();
            responses.writeJson(response, 200, responses.action("Disconnected."));
            return true;
        }

        if (path.equals("/command")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            String line = requestParser.requireString(body, "line");
            String mode = requestParser.stringValue(body.getOrDefault("mode", "shell"));
            boolean admin = !body.containsKey("admin") || requestParser.toBoolean(body.get("admin"));
            String text = "matrix".equalsIgnoreCase(mode)
                    ? dsp.executeMatrix(line, admin)
                    : dsp.executeShell(line);
            responses.writeJson(response, 200, responses.text(dsp, "command", text));
            return true;
        }

        if (path.equals("/preset/load")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            Object index = body.get("index");
            responses.writeJson(response, 200, index == null
                    ? dsp.loadPreset(requestParser.requireString(body, "slot"))
                    : dsp.loadPreset(requestParser.toInt(index)));
            return true;
        }

        if (path.equals("/preset/store")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.storePreset(
                    requestParser.requireString(body, "slot"),
                    requestParser.requireString(body, "name")
            ));
            return true;
        }

        if (path.equals("/preset/name/read")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(response, 200, dsp.readPresetName(requestParser.requireInt(body, "index")));
            return true;
        }

        if (path.equals("/preset/validate")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            JsonPresetApplier.PresetPlan plan = presetApplier.validate(body);
            responses.writeJson(response, 200, presetApplier.validationResponse(dsp.dspId(), plan));
            return true;
        }

        if (path.equals("/preset/apply")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            JsonPresetApplier.PresetPlan plan = presetApplier.validate(body);
            if (!dsp.isConnected()) {
                responses.sendError(
                        response,
                        409,
                        "dsp_not_connected",
                        "DSP " + dsp.dspId() + " is not connected."
                );
                return true;
            }
            boolean unmuteSelected = requestParser.queryBoolean(request, "unmuteSelected", false);
            // Preserve the preset apply contract: read back parameter blocks
            // before optional unmute unless the caller explicitly opts out.
            boolean refresh = requestParser.queryBoolean(request, "refresh", true);
            responses.writeJson(response, 200, presetApplier.apply(dsp, plan, unmuteSelected, refresh));
            return true;
        }

        return false;
    }
}

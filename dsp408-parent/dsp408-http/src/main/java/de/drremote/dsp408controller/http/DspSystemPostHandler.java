package de.drremote.dsp408controller.http;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.drremote.dsp408.api.DspService;

/** Routes test-tone, diagnostics, raw access and volume-room endpoints. */
final class DspSystemPostHandler {
    private final DspHttpRequestParser requestParser;
    private final DspHttpResponses responses;

    DspSystemPostHandler(DspHttpRequestParser requestParser, DspHttpResponses responses) {
        this.requestParser = requestParser;
        this.responses = responses;
    }

    boolean handle(
            String path,
            DspService dsp,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (path.equals("/test-tone/source")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            responses.writeJson(
                    response,
                    200,
                    dsp.setTestToneSource(requestParser.requireString(body, "source"))
            );
            return true;
        }

        if (path.equals("/test-tone/sine")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            Object raw = body.get("raw");
            responses.writeJson(response, 200, raw == null
                    ? dsp.setTestToneSineFrequency(requestParser.requireDouble(body, "hz"))
                    : dsp.setTestToneSineFrequencyRaw(requestParser.toInt(raw)));
            return true;
        }

        if (path.equals("/test-tone/off")) {
            responses.writeJson(response, 200, dsp.disableTestTone());
            return true;
        }

        if (path.startsWith("/blocks/") && path.endsWith("/read")) {
            String blockIndex = path.substring("/blocks/".length(), path.length() - "/read".length());
            responses.writeJson(response, 200, Map.of(
                    "connected", dsp.isConnected(),
                    "block", dsp.readBlock(blockIndex)
            ));
            return true;
        }

        if (path.equals("/blocks/scan") || path.equals("/scanblocks") || path.equals("/refresh")) {
            responses.writeJson(response, 200, dsp.scanBlocks());
            return true;
        }

        if (path.equals("/device/login")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            Object pinValue = body.get("pin");
            if (pinValue == null) {
                responses.sendError(response, 400, "bad_request", "Missing pin.");
                return true;
            }
            dsp.loginPin(String.valueOf(pinValue).trim());
            responses.writeJson(response, 200, responses.action("Login PIN sent."));
            return true;
        }

        if (path.equals("/raw")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            Object payloadHex = body.get("payloadHex");
            if (payloadHex == null) {
                responses.sendError(response, 400, "bad_request", "Missing payloadHex.");
                return true;
            }
            responses.writeJson(response, 200, dsp.sendRawHex(String.valueOf(payloadHex)));
            return true;
        }

        if (path.equals("/volume/up")) {
            writeVolume(response, dsp, "volume up", "!dsp volume up");
            return true;
        }
        if (path.equals("/volume/down")) {
            writeVolume(response, dsp, "volume down", "!dsp volume down");
            return true;
        }
        if (path.equals("/volume/mute")) {
            writeVolume(response, dsp, "volume mute", "!dsp volume mute");
            return true;
        }
        if (path.equals("/volume/unmute")) {
            writeVolume(response, dsp, "volume unmute", "!dsp volume unmute");
            return true;
        }
        if (path.equals("/volume/refresh")) {
            writeVolume(response, dsp, "volume refresh", "!dsp volume refresh");
            return true;
        }

        if (path.equals("/volume/set")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            double db = requestParser.requireDouble(body, "db");
            writeVolume(response, dsp, "volume set", "!dsp volume set " + db);
            return true;
        }

        if (path.equals("/volume/step")) {
            Map<String, Object> body = requestParser.readJsonBody(request);
            double db = requestParser.requireDouble(body, "db");
            String signed = db >= 0 ? "+" + db : String.valueOf(db);
            writeVolume(response, dsp, "volume step", "!dsp volume step " + signed);
            return true;
        }

        return false;
    }

    private void writeVolume(
            HttpServletResponse response,
            DspService dsp,
            String command,
            String line
    ) throws Exception {
        responses.writeJson(
                response,
                200,
                responses.text(dsp, command, dsp.executeVolumeRoom(line))
        );
    }
}

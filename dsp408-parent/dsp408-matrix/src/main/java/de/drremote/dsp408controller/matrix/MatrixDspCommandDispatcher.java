package de.drremote.dsp408controller.matrix;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.drremote.dsp408.api.DeviceInfoDto;
import de.drremote.dsp408.api.DspInstanceDto;
import de.drremote.dsp408.api.DspService;

final class MatrixDspCommandDispatcher {
    private final DspService manager;
    private final ObjectMapper mapper = new ObjectMapper();

    MatrixDspCommandDispatcher(DspService service) {
        this.manager = service;
    }

    String execute(String rawCommand, boolean isAdmin) throws Exception {
        return executeOn(manager, rawCommand, isAdmin);
    }

    String executeForDsp(String dspId, String rawCommand, boolean isAdmin) throws Exception {
        DspService target = targetDsp(dspId);
        return executeOn(target, rawCommand, isAdmin);
    }

    private String executeOn(DspService service, String rawCommand, boolean isAdmin) throws Exception {
        String[] parts = parse(rawCommand);
        if (parts.length == 0) {
            return help();
        }

        String cmd = parts[0].toLowerCase();
        String[] args = tail(parts);

        if ("dsps".equals(cmd)) {
            return formatDsps();
        }
        if ("dsp".equals(cmd) || "for".equals(cmd)) {
            String dspId = arg(args, 0, "Usage: !dsp dsp <dsp-id> <command>");
            String[] nested = tail(args);
            if (nested.length == 0) {
                throw new IllegalArgumentException("Usage: !dsp dsp <dsp-id> <command>");
            }
            return executeOn(targetDsp(dspId), join(nested), isAdmin);
        }

        if (!isAdmin && isAdminOnly(cmd)) {
            throw new IllegalArgumentException("Command disabled for non-admin Matrix users.");
        }

        return switch (cmd) {
            case "help", "?" -> help();
            case "connect" -> service.connect();
            case "disconnect", "close" -> {
                service.disconnect();
                yield "Disconnected.";
            }
            case "reconnect" -> service.reconnect();
            case "connected" -> service.isConnected() ? "yes" : "no";
            case "state", "status" -> json(service.scanBlocks());
            case "channels" -> json(service.getChannels());
            case "get" -> json(service.getChannel(arg(args, 0, "Usage: !dsp get <channel>")));
            case "deviceinfo", "info" -> json(service.getDeviceInfo());
            case "login" -> {
                service.loginPin(arg(args, 0, "Usage: !dsp login <1234>"));
                yield "Login PIN sent.";
            }
            case "loadpreset" -> loadPreset(service, args);
            case "readpresetname" -> json(service.readPresetName(intArg(args, 0, "Usage: !dsp readpresetname <0..19>")));
            case "meters" -> json(service.requestRuntimeMeters());
            case "meterstate", "meterscached" -> json(service.getMeters());
            case "meter" -> json(service.getMeter(arg(args, 0, "Usage: !dsp meter <channel>")));
            case "blocks" -> json(service.getCachedBlockIndices());
            case "readblock" -> json(service.readBlock(arg(args, 0, "Usage: !dsp readblock <hex>")));
            case "showblock", "block" -> json(service.getCachedBlock(arg(args, 0, "Usage: !dsp showblock <hex>")));
            case "scanblocks", "refresh" -> json(service.scanBlocks());
            case "raw", "sendraw" -> json(service.sendRawHex(join(args)));
            case "gain" -> json(service.setGain(arg(args, 0, "Usage: !dsp gain <channel> <db>"), doubleArg(args, 1, "Usage: !dsp gain <channel> <db>")));
            case "mute" -> json(service.mute(arg(args, 0, "Usage: !dsp mute <channel>")));
            case "unmute" -> json(service.unmute(arg(args, 0, "Usage: !dsp unmute <channel>")));
            case "name" -> json(service.setChannelName(arg(args, 0, "Usage: !dsp name <channel> <ascii-name>"), arg(args, 1, "Usage: !dsp name <channel> <ascii-name>")));
            case "phase" -> json(service.setPhase(arg(args, 0, "Usage: !dsp phase <channel> <0|180|normal|inverted>"), parsePhase(arg(args, 1, "Usage: !dsp phase <channel> <0|180|normal|inverted>"))));
            case "delay" -> json(service.setDelay(arg(args, 0, "Usage: !dsp delay <channel> <ms>"), doubleArg(args, 1, "Usage: !dsp delay <channel> <ms>")));
            case "delayunit" -> json(service.setDelayUnit(arg(args, 0, "Usage: !dsp delayunit <ms|m|ft>")));
            case "route" -> json(service.setMatrixRoute(arg(args, 0, "Usage: !dsp route <out> <in>"), arg(args, 1, "Usage: !dsp route <out> <in>")));
            case "xgain" -> json(service.setMatrixCrosspointGain(arg(args, 0, "Usage: !dsp xgain <out> <in> <db>"), arg(args, 1, "Usage: !dsp xgain <out> <in> <db>"), doubleArg(args, 2, "Usage: !dsp xgain <out> <in> <db>")));
            case "xhpset" -> json(service.setCrossoverHighPass(arg(args, 0, "Usage: !dsp xhpset <channel> <hz> <slope> <on|off>"), doubleArg(args, 1, "Usage: !dsp xhpset <channel> <hz> <slope> <on|off>"), arg(args, 2, "Usage: !dsp xhpset <channel> <hz> <slope> <on|off>"), onOffArg(args, 3, "Usage: !dsp xhpset <channel> <hz> <slope> <on|off>")));
            case "xhpfreq" -> json(service.setCrossoverHighPassFrequency(arg(args, 0, "Usage: !dsp xhpfreq <channel> <hz>"), doubleArg(args, 1, "Usage: !dsp xhpfreq <channel> <hz>")));
            case "xhpslope" -> json(service.setCrossoverHighPassSlope(arg(args, 0, "Usage: !dsp xhpslope <channel> <slope>"), arg(args, 1, "Usage: !dsp xhpslope <channel> <slope>")));
            case "xhpbypass" -> json(service.setCrossoverHighPassBypass(arg(args, 0, "Usage: !dsp xhpbypass <channel> <on|off>"), onOffArg(args, 1, "Usage: !dsp xhpbypass <channel> <on|off>")));
            case "xlpset" -> json(service.setCrossoverLowPass(arg(args, 0, "Usage: !dsp xlpset <channel> <hz> <slope> <on|off>"), doubleArg(args, 1, "Usage: !dsp xlpset <channel> <hz> <slope> <on|off>"), arg(args, 2, "Usage: !dsp xlpset <channel> <hz> <slope> <on|off>"), onOffArg(args, 3, "Usage: !dsp xlpset <channel> <hz> <slope> <on|off>")));
            case "xlpfreq" -> json(service.setCrossoverLowPassFrequency(arg(args, 0, "Usage: !dsp xlpfreq <channel> <hz>"), doubleArg(args, 1, "Usage: !dsp xlpfreq <channel> <hz>")));
            case "xlpslope" -> json(service.setCrossoverLowPassSlope(arg(args, 0, "Usage: !dsp xlpslope <channel> <slope>"), arg(args, 1, "Usage: !dsp xlpslope <channel> <slope>")));
            case "xlpbypass" -> json(service.setCrossoverLowPassBypass(arg(args, 0, "Usage: !dsp xlpbypass <channel> <on|off>"), onOffArg(args, 1, "Usage: !dsp xlpbypass <channel> <on|off>")));
            case "firmode" -> json(service.setFirProcessingMode(arg(args, 0, "Usage: !dsp firmode <out> <iir|fir>"), arg(args, 1, "Usage: !dsp firmode <out> <iir|fir>")));
            case "firgen", "firset" -> json(service.setFirGenerator(arg(args, 0, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>"), arg(args, 1, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>"), arg(args, 2, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>"), doubleArg(args, 3, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>"), doubleArg(args, 4, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>"), intArg(args, 5, "Usage: !dsp firgen <out> <type> <window> <hpHz> <lpHz> <taps>")));
            case "firupload" -> json(service.uploadExternalFir(arg(args, 0, "Usage: !dsp firupload <channel> <name> <coefficients>"), arg(args, 1, "Usage: !dsp firupload <channel> <name> <coefficients>"), doubleList(joinFrom(args, 2))));
            case "opeqset" -> json(service.setOutputPeq(arg(args, 0, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>"), intArg(args, 1, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>"), arg(args, 2, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>"), doubleArg(args, 3, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>"), doubleArg(args, 4, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>"), doubleArg(args, 5, "Usage: !dsp opeqset <out> <band> <type> <hz> <q> <gainDb>")));
            case "opeqfreq", "peqf" -> json(service.setOutputPeqFrequency(arg(args, 0, "Usage: !dsp opeqfreq <out> <band> <hz>"), intArg(args, 1, "Usage: !dsp opeqfreq <out> <band> <hz>"), doubleArg(args, 2, "Usage: !dsp opeqfreq <out> <band> <hz>")));
            case "opeqq" -> json(service.setOutputPeqQ(arg(args, 0, "Usage: !dsp opeqq <out> <band> <q>"), intArg(args, 1, "Usage: !dsp opeqq <out> <band> <q>"), doubleArg(args, 2, "Usage: !dsp opeqq <out> <band> <q>")));
            case "opeqqraw", "peqqraw" -> json(service.setOutputPeqQRaw(arg(args, 0, "Usage: !dsp opeqqraw <out> <band> <raw>"), intArg(args, 1, "Usage: !dsp opeqqraw <out> <band> <raw>"), intArg(args, 2, "Usage: !dsp opeqqraw <out> <band> <raw>")));
            case "opeqgain" -> json(service.setOutputPeqGain(arg(args, 0, "Usage: !dsp opeqgain <out> <band> <gainDb>"), intArg(args, 1, "Usage: !dsp opeqgain <out> <band> <gainDb>"), doubleArg(args, 2, "Usage: !dsp opeqgain <out> <band> <gainDb>")));
            case "opeqgcode", "peqgcode" -> json(service.setOutputPeqGainCode(arg(args, 0, "Usage: !dsp opeqgcode <out> <band> <code>"), intArg(args, 1, "Usage: !dsp opeqgcode <out> <band> <code>"), intArg(args, 2, "Usage: !dsp opeqgcode <out> <band> <code>")));
            case "opeqtype" -> json(service.setOutputPeqType(arg(args, 0, "Usage: !dsp opeqtype <out> <band> <type>"), intArg(args, 1, "Usage: !dsp opeqtype <out> <band> <type>"), arg(args, 2, "Usage: !dsp opeqtype <out> <band> <type>")));
            case "opeqbypass" -> json(service.setOutputPeqBypass(arg(args, 0, "Usage: !dsp opeqbypass <out> <band> <on|off>"), intArg(args, 1, "Usage: !dsp opeqbypass <out> <band> <on|off>"), onOffArg(args, 2, "Usage: !dsp opeqbypass <out> <band> <on|off>")));
            case "peqallbypass" -> json(service.setAllPeqBypass(onOffArg(args, 0, "Usage: !dsp peqallbypass <on|off>")));
            case "ipeqset" -> json(service.setInputPeq(arg(args, 0, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), intArg(args, 1, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), arg(args, 2, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), doubleArg(args, 3, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), doubleArg(args, 4, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), doubleArg(args, 5, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>"), onOffArg(args, 6, "Usage: !dsp ipeqset <in> <band> <type> <hz> <q> <gainDb> <on|off>")));
            case "ipeqfreq" -> json(service.setInputPeqFrequency(arg(args, 0, "Usage: !dsp ipeqfreq <in> <band> <hz>"), intArg(args, 1, "Usage: !dsp ipeqfreq <in> <band> <hz>"), doubleArg(args, 2, "Usage: !dsp ipeqfreq <in> <band> <hz>")));
            case "ipeqq" -> json(service.setInputPeqQ(arg(args, 0, "Usage: !dsp ipeqq <in> <band> <q>"), intArg(args, 1, "Usage: !dsp ipeqq <in> <band> <q>"), doubleArg(args, 2, "Usage: !dsp ipeqq <in> <band> <q>")));
            case "ipeqgain" -> json(service.setInputPeqGain(arg(args, 0, "Usage: !dsp ipeqgain <in> <band> <gainDb>"), intArg(args, 1, "Usage: !dsp ipeqgain <in> <band> <gainDb>"), doubleArg(args, 2, "Usage: !dsp ipeqgain <in> <band> <gainDb>")));
            case "ipeqtype" -> json(service.setInputPeqType(arg(args, 0, "Usage: !dsp ipeqtype <in> <band> <type>"), intArg(args, 1, "Usage: !dsp ipeqtype <in> <band> <type>"), arg(args, 2, "Usage: !dsp ipeqtype <in> <band> <type>")));
            case "ipeqbypass" -> json(service.setInputPeqBypass(arg(args, 0, "Usage: !dsp ipeqbypass <in> <band> <on|off>"), intArg(args, 1, "Usage: !dsp ipeqbypass <in> <band> <on|off>"), onOffArg(args, 2, "Usage: !dsp ipeqbypass <in> <band> <on|off>")));
            case "igeq" -> json(service.setInputGeq(arg(args, 0, "Usage: !dsp igeq <in> <band> <gainDb>"), intArg(args, 1, "Usage: !dsp igeq <in> <band> <gainDb>"), doubleArg(args, 2, "Usage: !dsp igeq <in> <band> <gainDb>")));
            case "gateset" -> json(service.setInputGate(arg(args, 0, "Usage: !dsp gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>"), doubleArg(args, 1, "Usage: !dsp gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>"), doubleArg(args, 2, "Usage: !dsp gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>"), doubleArg(args, 3, "Usage: !dsp gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>"), doubleArg(args, 4, "Usage: !dsp gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>")));
            case "gatethreshold" -> json(service.setInputGateThreshold(arg(args, 0, "Usage: !dsp gatethreshold <in> <thresholdDb>"), doubleArg(args, 1, "Usage: !dsp gatethreshold <in> <thresholdDb>")));
            case "gatehold" -> json(service.setInputGateHold(arg(args, 0, "Usage: !dsp gatehold <in> <holdMs>"), doubleArg(args, 1, "Usage: !dsp gatehold <in> <holdMs>")));
            case "gateattack" -> json(service.setInputGateAttack(arg(args, 0, "Usage: !dsp gateattack <in> <attackMs>"), doubleArg(args, 1, "Usage: !dsp gateattack <in> <attackMs>")));
            case "gaterelease" -> json(service.setInputGateRelease(arg(args, 0, "Usage: !dsp gaterelease <in> <releaseMs>"), doubleArg(args, 1, "Usage: !dsp gaterelease <in> <releaseMs>")));
            case "compset" -> json(service.setCompressor(arg(args, 0, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>"), doubleArg(args, 1, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>"), arg(args, 2, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>"), doubleArg(args, 3, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>"), doubleArg(args, 4, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>"), doubleArg(args, 5, "Usage: !dsp compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>")));
            case "compget", "limitget" -> service.executeMatrix("!dsp " + cmd + " " + join(args), true);
            case "limitset" -> json(service.setLimiter(arg(args, 0, "Usage: !dsp limitset <out> <thresholdDb> <attackMs> <releaseMs>"), doubleArg(args, 1, "Usage: !dsp limitset <out> <thresholdDb> <attackMs> <releaseMs>"), doubleArg(args, 2, "Usage: !dsp limitset <out> <thresholdDb> <attackMs> <releaseMs>"), doubleArg(args, 3, "Usage: !dsp limitset <out> <thresholdDb> <attackMs> <releaseMs>")));
            case "tone", "toneon", "tonefreq" -> json(service.setTestToneSineFrequency(doubleArg(args, 0, "Usage: !dsp tone <hz>")));
            case "tonesource" -> json(service.setTestToneSource(arg(args, 0, "Usage: !dsp tonesource <analog|pink|white|sine>")));
            case "toneraw" -> json(service.setTestToneSineFrequencyRaw(intArg(args, 0, "Usage: !dsp toneraw <0..30>")));
            case "toneoff" -> json(service.disableTestTone());
            case "volume" -> service.executeVolumeRoom("!dsp volume " + join(args));
            default -> throw new IllegalArgumentException("Unknown command: " + cmd + ". Use !dsp help.");
        };
    }

    private DspService targetDsp(String dspId) {
        if (dspId == null || dspId.isBlank()) {
            return manager;
        }
        return manager.forDsp(dspId.trim());
    }

    private String formatDsps() {
        List<DspInstanceDto> instances = manager.getDspInstances();
        if (instances.isEmpty()) {
            return "No DSPs configured.";
        }

        StringBuilder out = new StringBuilder("Configured DSPs:\n");
        for (DspInstanceDto dsp : instances) {
            DeviceInfoDto info = dsp.deviceInfo();
            String version = info == null || info.deviceVersion() == null || info.deviceVersion().isBlank()
                    ? "-"
                    : info.deviceVersion();
            out.append(dsp.defaultDevice() ? "* " : "- ")
                    .append(dsp.id())
                    .append(" ")
                    .append(dsp.connected() ? "connected" : "disconnected");
            if (dsp.ip() != null && !dsp.ip().isBlank()) {
                out.append(" ")
                        .append(dsp.ip())
                        .append(":")
                        .append(dsp.port());
            }
            out.append(" ")
                    .append(version)
                    .append('\n');
        }
        out.append("Use: !dsp dsp <dsp-id> <command>");
        return out.toString();
    }

    private String loadPreset(DspService service, String[] args) throws Exception {
        String slot = arg(args, 0, "Usage: !dsp loadpreset <F00|U01..U19|0..19>");
        if (slot.matches("\\d+")) {
            return json(service.loadPreset(Integer.parseInt(slot)));
        }
        return json(service.loadPreset(slot));
    }

    private String help() {
        return """
                DSP408 Matrix commands

                Multi-DSP: dsps, dsp <dsp-id> <command>
                Connection: connect, disconnect, reconnect, connected
                State: state, status, channels, get <channel>, deviceinfo
                Meters: meters (read DSP), meterstate (cached), meter <channel> (cached)
                Device: login <1234>, loadpreset <F00|U01..U19|0..19>, readpresetname <0..19>
                Blocks: blocks, readblock <hex>, showblock <hex>, scanblocks, refresh
                Channel: gain <channel> <db>, mute <channel>, unmute <channel>, name <channel> <name>, phase <channel> <0|180>, delay <channel> <ms>, delayunit <ms|m|ft>
                Matrix routing: route <out> <in>, xgain <out> <in> <db>
                Crossover: xhpset/xlpset <channel> <hz> <slope> <on|off>, xhpfreq/xlpfreq, xhpslope/xlpslope, xhpbypass/xlpbypass
                FIR408: firmode <out> <iir|fir>, firgen <out> <type> <window> <hpHz> <lpHz> <taps>, firupload <channel> <name> <coefficients>
                Output PEQ: opeqset, opeqfreq, opeqq, opeqqraw, opeqgain, opeqgcode, opeqtype, opeqbypass
                Input PEQ/GEQ: ipeqset, ipeqfreq, ipeqq, ipeqgain, ipeqtype, ipeqbypass, igeq
                All PEQ: peqallbypass <on|off> (104 bands, requires complete scanblocks state)
                Dynamics: gateset, gatethreshold, gatehold, gateattack, gaterelease, compset, compget, limitset, limitget
                Test tone: tone <hz>, tonesource <analog|pink|white|sine>, tonefreq <hz>, toneraw <0..30>, toneoff
                Volume room: volume <help|status|refresh|up|down|mute|unmute|set|step>
                Debug/admin: raw <hex payload>
                """.trim();
    }

    private String json(Object value) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private static boolean isAdminOnly(String cmd) {
        return switch (cmd) {
            case "connect", "disconnect", "close", "reconnect", "raw", "sendraw" -> true;
            default -> false;
        };
    }

    private static String[] parse(String rawCommand) {
        String text = rawCommand == null ? "" : rawCommand.trim();
        if (text.startsWith("!dsp")) {
            text = text.substring(4).trim();
        } else if (text.toLowerCase().startsWith("dsp ")) {
            text = text.substring(3).trim();
        }
        return text.isBlank() ? new String[0] : text.split("\\s+");
    }

    private static String[] tail(String[] parts) {
        String[] out = new String[Math.max(0, parts.length - 1)];
        if (out.length > 0) {
            System.arraycopy(parts, 1, out, 0, out.length);
        }
        return out;
    }

    private static String arg(String[] args, int index, String usage) {
        if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
            throw new IllegalArgumentException(usage);
        }
        return args[index].trim();
    }

    private static int intArg(String[] args, int index, String usage) {
        return Integer.parseInt(arg(args, index, usage));
    }

    private static double doubleArg(String[] args, int index, String usage) {
        return Double.parseDouble(arg(args, index, usage));
    }

    private static boolean onOffArg(String[] args, int index, String usage) {
        return switch (arg(args, index, usage).toLowerCase()) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("Expected on/off");
        };
    }

    private static boolean parsePhase(String value) {
        return switch (value.trim().toLowerCase()) {
            case "180", "inv", "invert", "inverted" -> true;
            case "0", "normal", "norm" -> false;
            default -> throw new IllegalArgumentException("Phase must be 0|180|normal|inverted");
        };
    }

    private static String join(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return String.join(" ", args).trim();
    }

    private static String joinFrom(String[] args, int offset) {
        if (args == null || args.length <= offset) {
            return "";
        }
        String[] tail = new String[args.length - offset];
        System.arraycopy(args, offset, tail, 0, tail.length);
        return join(tail);
    }

    private static List<Double> doubleList(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Usage: !dsp firupload <channel> <name> <coefficients>");
        }
        String[] tokens = text.trim().split("[,;\\s]+");
        List<Double> values = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                values.add(Double.parseDouble(token));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("FIR coefficients are required");
        }
        return values;
    }
}

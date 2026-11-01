package de.drremote.dsp408controller.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.service.UnsupportedDspOperationException;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.LimiterState;
import de.drremote.dsp408controller.util.DspTextFormatter;
import de.drremote.dsp408controller.util.Hex;

public final class DspCommandProcessor {
    public enum CommandMode {
        CLI,
        MATRIX
    }

    private final DspController controller;
    private final Supplier<DspConnectionConfig> configSupplier;
    private final Consumer<String> log;
    private final CommandMode mode;

    public DspCommandProcessor(DspController controller,
                               Supplier<DspConnectionConfig> configSupplier,
                               Consumer<String> log,
                               CommandMode mode) {
        this.controller = controller;
        this.configSupplier = configSupplier;
        this.log = log;
        this.mode = mode == null ? CommandMode.CLI : mode;
    }

    public void process(String line) {
        process(line, true);
    }

    public void process(String line, boolean isAdmin) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        try {
            String[] parts = trimmed.split("\\s+");
            int commandIndex = 0;
            if ("dsp".equalsIgnoreCase(parts[0]) || "!dsp".equalsIgnoreCase(parts[0])) {
                if (parts.length == 1) {
                    printHelp();
                    return;
                }
                commandIndex = 1;
            }

            String[] commandParts = Arrays.copyOfRange(parts, commandIndex, parts.length);
            String cmd = commandParts[0].toLowerCase(Locale.ROOT);
            if (mode == CommandMode.MATRIX && !isAdmin && isMatrixAdminCommand(cmd)) {
                log.accept("Command disabled in Matrix bot.");
                return;
            }

            switch (cmd) {
                case "connect" -> handleConnect();
                case "disconnect", "close" -> handleDisconnect();
                case "reconnect" -> handleReconnect();
                case "info" -> handleInfo();
                case "state" -> handleState();
                case "scanblocks" -> handleScanBlocks();
                case "block" -> handleBlock(commandParts);
                case "blocks" -> handleBlocks();
                case "refresh" -> handleRefresh();

                case "gain" -> handleGain(commandParts);
                case "mute" -> handleMute(commandParts, true);
                case "unmute" -> handleMute(commandParts, false);
                case "phase" -> handlePhase(commandParts);
                case "delay" -> handleDelay(commandParts);

                case "route" -> handleRoute(commandParts);
                case "xgain" -> handleCrosspointGain(commandParts);

                case "opeqset" -> handleOutputPeqSet(commandParts);
                case "opeqfreq" -> handleOutputPeqFreq(commandParts);
                case "opeqq" -> handleOutputPeqQ(commandParts);
                case "opeqgain" -> handleOutputPeqGain(commandParts);
                case "opeqtype" -> handleOutputPeqType(commandParts);
                case "opeqbypass" -> handleOutputPeqBypass(commandParts);
                case "peqallbypass" -> handleAllPeqBypass(commandParts);

                case "ipeqset" -> handleInputPeqSet(commandParts);
                case "ipeqfreq" -> handleInputPeqFreq(commandParts);
                case "ipeqq" -> handleInputPeqQ(commandParts);
                case "ipeqgain" -> handleInputPeqGain(commandParts);
                case "ipeqtype" -> handleInputPeqType(commandParts);
                case "ipeqbypass" -> handleInputPeqBypass(commandParts);

                case "igeq" -> handleInputGeq(commandParts);

                case "gateset" -> handleGateSet(commandParts);
                case "gatethreshold" -> handleGateThreshold(commandParts);
                case "gatehold" -> handleGateHold(commandParts);
                case "gateattack" -> handleGateAttack(commandParts);
                case "gaterelease" -> handleGateRelease(commandParts);

                case "xhpset" -> handleXhpSet(commandParts);
                case "xhpfreq" -> handleXhpFreq(commandParts);
                case "xhpslope" -> handleXhpSlope(commandParts);
                case "xhpbypass" -> handleXhpBypass(commandParts);

                case "xlpset" -> handleXlpSet(commandParts);
                case "xlpfreq" -> handleXlpFreq(commandParts);
                case "xlpslope" -> handleXlpSlope(commandParts);
                case "xlpbypass" -> handleXlpBypass(commandParts);

                case "firmode" -> handleFirMode(commandParts);
                case "firgen", "firset" -> handleFirGenerator(commandParts);
                case "firupload" -> handleFirUpload(commandParts);

                case "meters" -> handleMeters();
                case "meterwatch" -> handleMeterWatch(commandParts);
                case "meterstop" -> handleMeterStop();

                case "compget" -> handleCompGet(commandParts);
                case "limitget" -> handleLimitGet(commandParts);
                case "compset" -> handleCompSet(commandParts);
                case "limitset" -> handleLimitSet(commandParts);

                case "toneon" -> handleToneOn(commandParts);
                case "toneoff" -> handleToneOff();

                case "sendraw" -> handleSendRaw(commandParts);
                case "help", "?" -> printHelp();

                default -> log.accept("Unknown command: " + cmd + ". Type 'help'.");
            }
        } catch (RejectedExecutionException e) {
            throw e;
        } catch (UnsupportedDspOperationException e) {
            log.accept("Not enabled: " + e.getMessage());
        } catch (Exception e) {
            log.accept("Error: " + e.getMessage());
        }
    }

    private void handleConnect() throws IOException {
        if (controller.isConnected()) {
            log.accept("Already connected.");
            return;
        }
        controller.connect(configSupplier.get(), log);
    }

    private void handleDisconnect() {
        controller.close();
        log.accept("Disconnected.");
    }

    private void handleReconnect() throws IOException {
        controller.close();
        controller.connect(configSupplier.get(), log);
    }

    private void handleInfo() {
        String info = DspTextFormatter.formatDeviceInfo(
                controller.deviceVersion(),
                controller.lastSystemInfoPayload()
        );
        log.accept(info
                + System.lineSeparator()
                + "Library      : " + controller.libraryType()
                + " blocks=00.." + String.format(Locale.ROOT, "%02X", controller.maxParameterBlockIndex()));
    }

    private void handleState() {
        log.accept(DspTextFormatter.formatState(controller.state()));
    }

    private void handleScanBlocks() throws IOException {
        controller.scanParameterBlocks();
        log.accept("Block scan complete.");
    }

    private void handleBlock(String[] parts) {
        requireArgs(parts, 2, "Usage: block <hexIndex>");
        int index = parseHexByte(parts[1]);
        log.accept(DspTextFormatter.formatBlock(index, controller.state().getBlock(index)));
    }

    private void handleBlocks() {
        log.accept(DspTextFormatter.formatCachedBlocks(controller.state().cachedBlockIndices()));
    }

    private void handleRefresh() throws IOException {
        controller.requestSystemInfo();
        controller.scanParameterBlocks();
        controller.requestRuntimeMeters();
        log.accept("Refresh complete.");
    }

    private void handleGain(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: gain <channel> <db>");
        controller.setGain(parseChannel(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleMute(String[] parts, boolean muted) throws IOException {
        requireArgs(parts, 2, muted ? "Usage: mute <channel>" : "Usage: unmute <channel>");
        DspChannel channel = parseChannel(parts[1]);
        if (muted) {
            controller.mute(channel);
        } else {
            controller.unmute(channel);
        }
    }

    private void handlePhase(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: phase <channel> <0|180|normal|inverted>");
        DspChannel channel = parseChannel(parts[1]);
        boolean inverted = parsePhase(parts[2]);
        controller.setPhase(channel, inverted);
    }

    private void handleDelay(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: delay <channel> <ms>");
        controller.setDelay(parseChannel(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleRoute(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: route <output> <input>");
        controller.setMatrixRoute(parseOutput(parts[1]), parseInput(parts[2]));
    }

    private void handleCrosspointGain(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: xgain <output> <input> <db>");
        controller.setMatrixCrosspointGain(
                parseOutput(parts[1]),
                parseInput(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleOutputPeqSet(String[] parts) throws IOException {
        requireArgs(parts, 7, "Usage: opeqset <out> <band> <type> <freqHz> <q> <gainDb>");
        controller.setOutputPeq(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                parsePeqType(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5]),
                Double.parseDouble(parts[6])
        );
    }

    private void handleOutputPeqFreq(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: opeqfreq <out> <band> <freqHz>");
        controller.setOutputPeqFrequency(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleOutputPeqQ(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: opeqq <out> <band> <q>");
        controller.setOutputPeqQ(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleOutputPeqGain(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: opeqgain <out> <band> <gainDb>");
        controller.setOutputPeqGain(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleOutputPeqType(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: opeqtype <out> <band> <peak|lowshelf|highshelf>");
        controller.setOutputPeqType(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                parsePeqType(parts[3])
        );
    }

    private void handleInputPeqSet(String[] parts) throws IOException {
        requireArgs(parts, 8, "Usage: ipeqset <in> <band> <type> <freqHz> <q> <gainDb> <on|off>");
        controller.setInputPeq(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                parsePeqType(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5]),
                Double.parseDouble(parts[6]),
                parseOnOff(parts[7])
        );
    }

    private void handleInputPeqFreq(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: ipeqfreq <in> <band> <freqHz>");
        controller.setInputPeqFrequency(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleInputPeqQ(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: ipeqq <in> <band> <q>");
        controller.setInputPeqQ(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleInputPeqGain(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: ipeqgain <in> <band> <gainDb>");
        controller.setInputPeqGain(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleInputPeqType(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: ipeqtype <in> <band> <peak|lowshelf|highshelf>");
        controller.setInputPeqType(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                parsePeqType(parts[3])
        );
    }

    private void handleInputPeqBypass(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: ipeqbypass <in> <band> <on|off>");
        controller.setInputPeqBypass(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                parseOnOff(parts[3])
        );
    }

    private void handleInputGeq(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: igeq <in> <band> <gainDb>");
        controller.setInputGeq(
                parseInput(parts[1]),
                Integer.parseInt(parts[2]),
                Double.parseDouble(parts[3])
        );
    }

    private void handleGateSet(String[] parts) throws IOException {
        requireArgs(parts, 6, "Usage: gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>");
        controller.setInputGate(
                parseInput(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
        );
    }

    private void handleGateThreshold(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: gatethreshold <in> <thresholdDb>");
        controller.setInputGateThreshold(parseInput(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleGateHold(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: gatehold <in> <holdMs>");
        controller.setInputGateHold(parseInput(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleGateAttack(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: gateattack <in> <attackMs>");
        controller.setInputGateAttack(parseInput(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleGateRelease(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: gaterelease <in> <releaseMs>");
        controller.setInputGateRelease(parseInput(parts[1]), Double.parseDouble(parts[2]));
    }

    private void handleXhpSet(String[] parts) throws IOException {
        requireArgs(parts, 5, "Usage: xhpset <channel> <freqHz> <slope> <on|off>");
        controller.setCrossoverHighPass(
                parseChannel(parts[1]),
                Double.parseDouble(parts[2]),
                parseSlope(parts[3]),
                parseOnOff(parts[4])
        );
    }

    private void handleXhpFreq(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xhpfreq <channel> <freqHz>");
        controller.setCrossoverHighPassFrequency(
                parseChannel(parts[1]),
                Double.parseDouble(parts[2])
        );
    }

    private void handleXhpSlope(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xhpslope <channel> <slope>");
        controller.setCrossoverHighPassSlope(
                parseChannel(parts[1]),
                parseSlope(parts[2])
        );
    }

    private void handleXhpBypass(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xhpbypass <channel> <on|off>");
        controller.setCrossoverHighPassBypass(
                parseChannel(parts[1]),
                parseOnOff(parts[2])
        );
    }

    private void handleXlpSet(String[] parts) throws IOException {
        requireArgs(parts, 5, "Usage: xlpset <channel> <freqHz> <slope> <on|off>");
        controller.setCrossoverLowPass(
                parseChannel(parts[1]),
                Double.parseDouble(parts[2]),
                parseSlope(parts[3]),
                parseOnOff(parts[4])
        );
    }

    private void handleXlpFreq(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xlpfreq <channel> <freqHz>");
        controller.setCrossoverLowPassFrequency(
                parseChannel(parts[1]),
                Double.parseDouble(parts[2])
        );
    }

    private void handleXlpSlope(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xlpslope <channel> <slope>");
        controller.setCrossoverLowPassSlope(
                parseChannel(parts[1]),
                parseSlope(parts[2])
        );
    }

    private void handleXlpBypass(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: xlpbypass <channel> <on|off>");
        controller.setCrossoverLowPassBypass(
                parseChannel(parts[1]),
                parseOnOff(parts[2])
        );
    }

    private void handleOutputPeqBypass(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: opeqbypass <out> <band> <on|off>");
        controller.setOutputPeqBypass(
                parseOutput(parts[1]),
                Integer.parseInt(parts[2]),
                parseOnOff(parts[3])
        );
    }

    private void handleAllPeqBypass(String[] parts) throws IOException {
        requireArgs(parts, 2, "Usage: peqallbypass <on|off>");
        controller.setAllPeqBypass(parseOnOff(parts[1]));
    }

    private void handleFirMode(String[] parts) throws IOException {
        requireArgs(parts, 3, "Usage: firmode <out> <iir|fir>");
        controller.setFirProcessingMode(parseOutput(parts[1]), FirProcessingMode.parse(parts[2]));
    }

    private void handleFirGenerator(String[] parts) throws IOException {
        requireArgs(parts, 7, "Usage: firgen <out> <type> <window> <hpHz> <lpHz> <taps>");
        controller.setFirGenerator(
                parseOutput(parts[1]),
                FirFilterType.parse(parts[2]),
                FirWindowFunction.parse(parts[3]),
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5]),
                Integer.parseInt(parts[6])
        );
    }

    private void handleFirUpload(String[] parts) throws IOException {
        requireArgs(parts, 4, "Usage: firupload <channel> <name> <file|coefficients>");
        String source = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
        double[] coefficients = parseFirCoefficients(source);
        List<byte[]> payloads = controller.uploadExternalFir(parseChannel(parts[1]), parts[2], coefficients);
        log.accept("External FIR upload sent: " + coefficients.length + " taps, " + payloads.size() + " payloads.");
    }

    private void handleMeters() throws IOException {
        controller.requestRuntimeMeters();
        log.accept(DspTextFormatter.formatMetersState(controller.state()));
    }

    private void handleMeterWatch(String[] parts) {
        long intervalMs = 200L;
        if (parts.length >= 2) {
            intervalMs = Long.parseLong(parts[1]);
        }
        controller.startMeterPolling(intervalMs);
    }

    private void handleMeterStop() {
        controller.stopMeterPolling();
    }

    private void handleCompGet(String[] parts) {
        requireArgs(parts, 2, "Usage: compget <in>");
        DspChannel input = parseInput(parts[1]);
        CompressorState state = controller.state().compressor(input);
        if (state == null || !state.hasAnyValues()) {
            log.accept(input.displayName() + " compressor: no data");
            return;
        }

        log.accept(String.format(
                Locale.ROOT,
                "%s compressor: ratio=%s attack=%s release=%s knee=%s threshold=%s frequency=%s type=%s",
                input.displayName(),
                valueOrDash(state.ratioLabel()),
                valueOrDash(state.attackMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.attackMs())),
                valueOrDash(state.releaseMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.releaseMs())),
                valueOrDash(state.kneeDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.kneeDb())),
                valueOrDash(state.thresholdDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.thresholdDb())),
                valueOrDash(state.frequencyHz() == null ? null : DspTextFormatter.formatFrequency(state.frequencyHz())),
                valueOrDash(state.typeLabel())
        ));
    }

    private void handleLimitGet(String[] parts) {
        requireArgs(parts, 2, "Usage: limitget <out>");
        DspChannel output = parseOutput(parts[1]);
        LimiterState state = controller.state().limiter(output);
        if (state == null || !state.hasAnyValues()) {
            log.accept(output.displayName() + " limiter: no data");
            return;
        }

        log.accept(String.format(
                Locale.ROOT,
                "%s limiter: attack=%s release=%s threshold=%s runtime=%s knee=%s",
                output.displayName(),
                valueOrDash(state.attackMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.attackMs())),
                valueOrDash(state.releaseMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.releaseMs())),
                valueOrDash(state.thresholdDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.thresholdDb())),
                valueOrDash(state.runtimeActive() == null ? null : (state.runtimeActive() ? "active" : "idle")),
                valueOrDash(state.kneeDb() == null ? null : String.format(Locale.ROOT, "%.0f dB", state.kneeDb()))
        ));
    }

    private void handleCompSet(String[] parts) throws IOException {
        requireArgs(parts, 7, "Usage: compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>");
        controller.setCompressor(
                parseInput(parts[1]),
                Double.parseDouble(parts[2]),
                parts[3],
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5]),
                Double.parseDouble(parts[6])
        );
    }

    private void handleLimitSet(String[] parts) throws IOException {
        requireArgs(parts, 5, "Usage: limitset <out> <thresholdDb> <attackMs> <releaseMs>");
        controller.setLimiter(
                parseOutput(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4])
        );
    }

    private void handleToneOn(String[] parts) throws IOException {
        requireArgs(parts, 2, "Usage: toneon <freqHz>");
        controller.enableTestTone(Double.parseDouble(parts[1]));
    }

    private void handleToneOff() throws IOException {
        controller.disableTestTone();
    }

    private void handleSendRaw(String[] parts) throws IOException {
        requireArgs(parts, 2, "Usage: sendraw <hex bytes>");
        String hex = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        byte[] payload = Hex.parseFlexible(hex);
        controller.sendRaw(payload);
    }

    private void printHelp() {
        String header = mode == CommandMode.MATRIX
                ? "Commands (matrix mode):"
                : "Commands:";

        log.accept(header + """
      connect
      disconnect
      reconnect
      info
      state
      refresh
      scanblocks
      block <hexIndex>
      blocks

      gain <channel> <db>
      mute <channel>
      unmute <channel>
      phase <channel> <0|180|normal|inverted>
      delay <channel> <ms>

      route <out> <in>
      xgain <out> <in> <db>

      opeqset <out> <band> <type> <freqHz> <q> <gainDb>
      opeqfreq <out> <band> <freqHz>
      opeqq <out> <band> <q>
      opeqgain <out> <band> <gainDb>
      opeqtype <out> <band> <type>
      opeqbypass <out> <band> <on|off>
      peqallbypass <on|off>

      ipeqset <in> <band> <type> <freqHz> <q> <gainDb> <on|off>
      ipeqfreq <in> <band> <freqHz>
      ipeqq <in> <band> <q>
      ipeqgain <in> <band> <gainDb>
      ipeqtype <in> <band> <type>
      ipeqbypass <in> <band> <on|off>

      igeq <in> <band> <gainDb>

      gateset <in> <thresholdDb> <holdMs> <attackMs> <releaseMs>
      gatethreshold <in> <thresholdDb>
      gatehold <in> <holdMs>
      gateattack <in> <attackMs>
      gaterelease <in> <releaseMs>

      xhpset <channel> <freqHz> <slope> <on|off>
      xhpfreq <channel> <freqHz>
      xhpslope <channel> <slope>
      xhpbypass <channel> <on|off>

      xlpset <channel> <freqHz> <slope> <on|off>
      xlpfreq <channel> <freqHz>
      xlpslope <channel> <slope>
      xlpbypass <channel> <on|off>

      firmode <out> <iir|fir>
      firgen <out> <type> <window> <hpHz> <lpHz> <taps>
      firupload <channel> <name> <file|coefficients>

      meters
      meterwatch [intervalMs]
      meterstop

      compget <in>
      limitget <out>
      compset <in> <thresholdDb> <ratio> <attackMs> <releaseMs> <kneeDb>
      limitset <out> <thresholdDb> <attackMs> <releaseMs>

      toneon <freqHz>
      toneoff

      sendraw <hex bytes>
      help
    """);
    }

    private static void requireArgs(String[] parts, int minCount, String usage) {
        if (parts.length < minCount) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static DspChannel parseChannel(String value) {
        return DspChannel.parse(value);
    }

    private static DspChannel parseInput(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "ina", "in1", "input1", "a", "in-a" -> DspChannel.IN_A;
            case "2", "inb", "in2", "input2", "b", "in-b" -> DspChannel.IN_B;
            case "3", "inc", "in3", "input3", "c", "in-c" -> DspChannel.IN_C;
            case "4", "ind", "in4", "input4", "d", "in-d" -> DspChannel.IN_D;
            default -> throw new IllegalArgumentException("Input channel required: InA..InD (or 1..4)");
        };
    }

    private static DspChannel parseOutput(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "out1", "output1" -> DspChannel.OUT_1;
            case "2", "out2", "output2" -> DspChannel.OUT_2;
            case "3", "out3", "output3" -> DspChannel.OUT_3;
            case "4", "out4", "output4" -> DspChannel.OUT_4;
            case "5", "out5", "output5" -> DspChannel.OUT_5;
            case "6", "out6", "output6" -> DspChannel.OUT_6;
            case "7", "out7", "output7" -> DspChannel.OUT_7;
            case "8", "out8", "output8" -> DspChannel.OUT_8;
            default -> throw new IllegalArgumentException("Output channel required: Out1..Out8 (or 1..8)");
        };
    }

    private static boolean parsePhase(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "180", "inv", "invert", "inverted" -> true;
            case "0", "normal", "norm" -> false;
            default -> throw new IllegalArgumentException("Phase must be 0|180|normal|inverted");
        };
    }

    private static boolean parseOnOff(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "on", "true", "1", "yes" -> true;
            case "off", "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("Expected on/off");
        };
    }

    private static int parseHexByte(String value) {
        String v = value.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
        }
        return Integer.parseInt(v, 16);
    }

    private static PeqFilterType parsePeqType(String value) {
        return PeqFilterType.parse(value);
    }

    private static CrossoverSlope parseSlope(String value) {
        return CrossoverSlope.parse(value);
    }

    private static double[] parseFirCoefficients(String source) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("FIR coefficient source is required");
        }

        String text = readCoefficientSource(source.trim());
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.split("\\R")) {
            String stripped = stripCoefficientComment(line);
            if (!stripped.isBlank()) {
                cleaned.append(stripped).append(' ');
            }
        }

        String[] tokens = cleaned.toString().trim().split("[,;\\s]+");
        List<Double> values = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            values.add(Double.parseDouble(token));
        }

        if (values.isEmpty()) {
            throw new IllegalArgumentException("No FIR coefficients found");
        }

        double[] coefficients = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            coefficients[i] = values.get(i);
        }
        return coefficients;
    }

    private static String readCoefficientSource(String source) throws IOException {
        Path path = Path.of(source);
        if (Files.isRegularFile(path)) {
            return Files.readString(path);
        }
        return source;
    }

    private static String stripCoefficientComment(String line) {
        String out = line == null ? "" : line;
        int hash = out.indexOf('#');
        if (hash >= 0) {
            out = out.substring(0, hash);
        }
        int slash = out.indexOf("//");
        if (slash >= 0) {
            out = out.substring(0, slash);
        }
        return out.trim();
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static boolean isMatrixAdminCommand(String cmd) {
        return switch (cmd) {
            case "connect", "disconnect", "close", "reconnect", "sendraw" -> true;
            default -> false;
        };
    }
}

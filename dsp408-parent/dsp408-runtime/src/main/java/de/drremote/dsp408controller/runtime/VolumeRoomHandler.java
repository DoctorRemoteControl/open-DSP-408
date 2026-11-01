package de.drremote.dsp408controller.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.LimiterState;

public final class VolumeRoomHandler {
    private static final double LIMITER_STEP_DB = 1.0;
    private static final double MIN_LIMITER_DB = -90.0;
    private static final double MAX_LIMITER_DB = 20.0;

    private static final DspChannel[] INPUT_CHANNELS = {
            DspChannel.IN_A,
            DspChannel.IN_B,
            DspChannel.IN_C,
            DspChannel.IN_D
    };

    private static final DspChannel[] OUTPUT_CHANNELS = {
            DspChannel.OUT_1,
            DspChannel.OUT_2,
            DspChannel.OUT_3,
            DspChannel.OUT_4,
            DspChannel.OUT_5,
            DspChannel.OUT_6,
            DspChannel.OUT_7,
            DspChannel.OUT_8
    };

    private static final DspChannel[] ALL_CHANNELS = {
            DspChannel.IN_A,
            DspChannel.IN_B,
            DspChannel.IN_C,
            DspChannel.IN_D,
            DspChannel.OUT_1,
            DspChannel.OUT_2,
            DspChannel.OUT_3,
            DspChannel.OUT_4,
            DspChannel.OUT_5,
            DspChannel.OUT_6,
            DspChannel.OUT_7,
            DspChannel.OUT_8
    };

    private final DspRuntimeComponent runtime;

    public VolumeRoomHandler(DspRuntimeComponent runtime) {
        this.runtime = runtime;
    }

    public String handleVolumeRoom(String body) throws Exception {
        String command = normalizeRoomCommand(body);
        if (command == null) {
            return "";
        }
        if (command.isBlank()) {
            return shortUsage();
        }

        String[] parts = command.trim().split("\\s+");
        String cmd = normalizeWord(parts[0]);
        if (cmd.equals("help") || cmd.equals("hilfe") || cmd.equals("?")) {
            return help();
        }

        return runtime.withController(controller -> {
            if (isLimiterCommand(cmd)) {
                return handleLimiterCommand(controller, parts);
            }
            if (isTargetToken(parts[0])) {
                return handleTargetCommand(controller, parts);
            }
            String arg = parts.length > 1 ? parts[1] : "";
            return handleLegacyCommand(controller, cmd, arg);
        });
    }

    private String handleLimiterCommand(DspController controller, String[] parts) throws Exception {
        ensureConnected();
        String action = parts.length > 1 ? normalizeWord(parts[1]) : "status";

        if (action.equals("status")) {
            ensureKnownLimiters(controller, OUTPUT_CHANNELS);
            return formatLimiterStatus(controller, OUTPUT_CHANNELS);
        }
        if (action.equals("+") || action.equals("up") || action.equals("hoeher")) {
            return changeLimiterBy(controller, OUTPUT_CHANNELS, LIMITER_STEP_DB);
        }
        if (action.equals("-") || action.equals("down") || action.equals("tiefer")) {
            return changeLimiterBy(controller, OUTPUT_CHANNELS, -LIMITER_STEP_DB);
        }
        if (isSignedNumber(parts[1])) {
            return changeLimiterBy(
                    controller,
                    OUTPUT_CHANNELS,
                    parseLimiterDbDelta(parts[1], "limiter <+dB|-dB>")
            );
        }
        if (action.equals("set")) {
            String value = parts.length > 2 ? parts[2] : "";
            return setLimiterThreshold(
                    controller,
                    OUTPUT_CHANNELS,
                    parseLimiterDb(value, "limiter set <dB>")
            );
        }

        DspChannel output;
        try {
            output = limiterOutput(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return limiterUsage();
        }

        String outputAction = parts.length > 2 ? normalizeWord(parts[2]) : "status";
        if (outputAction.equals("status")) {
            ensureKnownLimiters(controller, new DspChannel[]{output});
            return formatLimiterStatus(controller, new DspChannel[]{output});
        }
        if (outputAction.equals("+") || outputAction.equals("up") || outputAction.equals("hoeher")) {
            return changeLimiterBy(controller, new DspChannel[]{output}, LIMITER_STEP_DB);
        }
        if (outputAction.equals("-") || outputAction.equals("down") || outputAction.equals("tiefer")) {
            return changeLimiterBy(controller, new DspChannel[]{output}, -LIMITER_STEP_DB);
        }
        if (outputAction.equals("set")) {
            String value = parts.length > 3 ? parts[3] : "";
            return setLimiterThreshold(
                    controller,
                    new DspChannel[]{output},
                    parseLimiterDb(value, "limiter " + output.displayName().toLowerCase(Locale.ROOT)
                            + " set <dB>")
            );
        }
        if (isNumber(parts[2])) {
            return setLimiterThreshold(
                    controller,
                    new DspChannel[]{output},
                    parseLimiterDb(parts[2], "limiter "
                            + output.displayName().toLowerCase(Locale.ROOT) + " <dB>")
            );
        }
        return limiterUsage();
    }

    static String normalizeRoomCommand(String body) {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) {
            return null;
        }

        String[] prefixed = raw.split("\\s+", 3);
        if (prefixed[0].equalsIgnoreCase("!dsp")) {
            if (prefixed.length < 2
                    || !(prefixed[1].equalsIgnoreCase("volume")
                    || prefixed[1].equalsIgnoreCase("vol"))) {
                return null;
            }
            return prefixed.length < 3 ? "" : prefixed[2].trim();
        }

        String first = raw.split("\\s+", 2)[0];
        String normalized = normalizeWord(first);
        if (isLegacyCommand(normalized) || isTargetToken(first)) {
            return raw;
        }
        return null;
    }

    static DspChannel[] channelsForTarget(String value) {
        String normalized = normalizeWord(value);
        if (normalized.equals("in")
                || normalized.equals("input")
                || normalized.equals("inputs")
                || normalized.equals("eingang")
                || normalized.equals("eingaenge")) {
            return INPUT_CHANNELS.clone();
        }
        if (normalized.equals("out")
                || normalized.equals("output")
                || normalized.equals("outputs")
                || normalized.equals("ausgang")
                || normalized.equals("ausgaenge")) {
            return OUTPUT_CHANNELS.clone();
        }
        if (normalized.matches("in[abcd]") || normalized.matches("out[1-8]")) {
            return new DspChannel[]{DspChannel.parse(normalized)};
        }
        throw new IllegalArgumentException("Unbekanntes Pegelziel: " + value);
    }

    private String handleTargetCommand(DspController controller, String[] parts) throws Exception {
        DspChannel[] channels = channelsForTarget(parts[0]);
        boolean group = channels.length > 1;
        String target = displayTarget(parts[0], channels);
        String action = parts.length > 1 ? normalizeWord(parts[1]) : "status";

        ensureConnected();

        if (action.equals("status")) {
            return formatStatus(controller, channels);
        }
        if (action.equals("refresh") || action.equals("aktualisieren")) {
            controller.scanParameterBlocks();
            return formatStatus(controller, channels);
        }
        if (action.equals("+") || action.equals("up") || action.equals("lauter")) {
            return changeBy(controller, target, channels, runtime.currentVolumeStepDb());
        }
        if (action.equals("-") || action.equals("down") || action.equals("leiser")) {
            return changeBy(controller, target, channels, -runtime.currentVolumeStepDb());
        }
        if (action.equals("mute") || action.equals("stumm") || action.equals("aus")) {
            return setMuted(controller, target, channels, true);
        }
        if (action.equals("unmute") || action.equals("an")) {
            return setMuted(controller, target, channels, false);
        }
        if (action.equals("set")) {
            String value = parts.length > 2 ? parts[2] : "";
            return setLevel(controller, target, channels, parseDb(value, target + " set <dB>"));
        }
        if (action.equals("step")) {
            String value = parts.length > 2 ? parts[2] : "";
            return changeBy(controller, target, channels, parseDb(value, target + " step <+/-dB>"));
        }
        if (!group && isNumber(parts[1])) {
            return setLevel(controller, target, channels, parseDb(parts[1], target + " <dB>"));
        }
        if (group && isNumber(parts[1])) {
            return "Für einen festen Gruppenpegel: " + target + " set " + parts[1];
        }

        return targetUsage(target, group);
    }

    private String handleLegacyCommand(DspController controller, String cmd, String arg) throws Exception {
        if (cmd.equals("help") || cmd.equals("hilfe") || cmd.equals("?")) {
            return help();
        }

        ensureConnected();

        if (cmd.equals("status")) {
            return formatStatus(controller, ALL_CHANNELS);
        }
        if (cmd.equals("refresh") || cmd.equals("aktualisieren")) {
            controller.scanParameterBlocks();
            return formatStatus(controller, ALL_CHANNELS);
        }
        if (cmd.equals("mute")) {
            return setMuted(controller, "in", INPUT_CHANNELS, true);
        }
        if (cmd.equals("unmute")) {
            return setMuted(controller, "in", INPUT_CHANNELS, false);
        }
        if (cmd.equals("up") || cmd.equals("lauter")) {
            return changeBy(controller, "in", INPUT_CHANNELS, runtime.currentVolumeStepDb());
        }
        if (cmd.equals("down") || cmd.equals("leiser")) {
            return changeBy(controller, "in", INPUT_CHANNELS, -runtime.currentVolumeStepDb());
        }
        if (cmd.equals("set")) {
            return setLevel(controller, "in", INPUT_CHANNELS, parseDb(arg, "set <dB>"));
        }
        if (cmd.equals("step")) {
            return changeBy(controller, "in", INPUT_CHANNELS, parseDb(arg, "step <+/-dB>"));
        }

        return shortUsage();
    }

    private String changeBy(DspController controller,
                            String target,
                            DspChannel[] channels,
                            double deltaDb) throws Exception {
        ensureKnownGains(controller, channels);
        double[] targets = new double[channels.length];
        for (int index = 0; index < channels.length; index++) {
            targets[index] = controller.state().channel(channels[index]).gainDb() + deltaDb;
        }
        for (int index = 0; index < channels.length; index++) {
            runtime.setGain(channels[index].displayName(), targets[index]);
        }
        String status = formatStatus(controller, channels);
        if (channels.length == 1) {
            return "✅ " + targetLabel(channels) + " " + formatSignedDb(deltaDb) + " dB → "
                    + compactGain(controller.state().channel(channels[0]));
        }
        return "✅ " + targetLabel(channels) + " " + formatSignedDb(deltaDb) + " dB"
                + System.lineSeparator() + status;
    }

    private String setLevel(DspController controller,
                            String target,
                            DspChannel[] channels,
                            double targetDb) throws Exception {
        for (DspChannel channel : channels) {
            runtime.setGain(channel.displayName(), targetDb);
        }
        String status = formatStatus(controller, channels);
        if (channels.length == 1) {
            return "✅ " + status;
        }
        return "✅ " + targetLabel(channels) + " = " + formatDb(targetDb) + " dB"
                + System.lineSeparator() + status;
    }

    private String setMuted(DspController controller,
                            String target,
                            DspChannel[] channels,
                            boolean muted) throws Exception {
        for (DspChannel channel : channels) {
            if (muted) {
                runtime.mute(channel.displayName());
            } else {
                runtime.unmute(channel.displayName());
            }
        }
        return "✅ " + targetLabel(channels) + (muted ? " stumm" : " aktiv");
    }

    private String changeLimiterBy(DspController controller,
                                   DspChannel[] outputs,
                                   double deltaDb) throws Exception {
        ensureKnownLimiters(controller, outputs);
        double[] targets = new double[outputs.length];
        for (int index = 0; index < outputs.length; index++) {
            targets[index] = checkedLimiterDb(
                    controller.state().limiter(outputs[index]).thresholdDb() + deltaDb
            );
        }
        for (int index = 0; index < outputs.length; index++) {
            writeLimiterThreshold(controller, outputs[index], targets[index]);
        }
        String label = outputs.length == 1 ? outputs[0].displayName() : "alle";
        return "✅ LIMITER " + label + " " + formatSignedDb(deltaDb) + " dB"
                + System.lineSeparator() + formatLimiterStatus(controller, outputs);
    }

    private String setLimiterThreshold(DspController controller,
                                       DspChannel[] outputs,
                                       double thresholdDb) throws Exception {
        ensureKnownLimiters(controller, outputs);
        double checkedThresholdDb = checkedLimiterDb(thresholdDb);
        for (DspChannel output : outputs) {
            writeLimiterThreshold(controller, output, checkedThresholdDb);
        }
        String label = outputs.length == 1 ? outputs[0].displayName() : "alle";
        return "✅ LIMITER " + label + " = " + formatDb(checkedThresholdDb) + " dB"
                + System.lineSeparator() + formatLimiterStatus(controller, outputs);
    }

    private void writeLimiterThreshold(DspController controller,
                                       DspChannel output,
                                       double thresholdDb) throws Exception {
        LimiterState limiter = controller.state().limiter(output);
        runtime.setLimiter(
                output.displayName(),
                thresholdDb,
                limiter.attackMs(),
                limiter.releaseMs()
        );
    }

    private void ensureConnected() {
        if (!runtime.isConnected()) {
            throw new IllegalStateException("DSP ist nicht verbunden.");
        }
    }

    private void ensureKnownGains(DspController controller, DspChannel[] channels) throws Exception {
        for (DspChannel channel : channels) {
            ChannelState state = controller.state().channel(channel);
            if (state.gainDb() == null || !state.gainConfirmedFromDevice()) {
                int blockIndex = Fir408WriteVerifier.gainLocation(channel).blockIndex();
                runtime.readBlock(String.format(Locale.ROOT, "%02X", blockIndex));
            }
        }

        for (DspChannel channel : channels) {
            ChannelState state = controller.state().channel(channel);
            if (state.gainDb() == null || !state.gainConfirmedFromDevice()) {
                throw new IllegalStateException(
                        "Kein bestätigter Pegel für " + channel.displayName()
                );
            }
        }
    }

    private void ensureKnownLimiters(DspController controller, DspChannel[] outputs) throws Exception {
        Set<Integer> missingBlocks = new LinkedHashSet<>();
        for (DspChannel output : outputs) {
            if (!hasCompleteLimiter(controller.state().limiter(output))) {
                missingBlocks.add(Fir408WriteVerifier.limiterLocation(output).blockIndex());
            }
        }
        for (int blockIndex : missingBlocks) {
            runtime.readBlock(String.format(Locale.ROOT, "%02X", blockIndex));
        }
        for (DspChannel output : outputs) {
            if (!hasCompleteLimiter(controller.state().limiter(output))) {
                throw new IllegalStateException(
                        "Limiterwerte für " + output.displayName() + " fehlen im Server-Cache."
                );
            }
        }
    }

    private static boolean hasCompleteLimiter(LimiterState limiter) {
        return limiter != null
                && limiter.thresholdDb() != null
                && limiter.attackMs() != null
                && limiter.releaseMs() != null
                && limiter.kneeDb() != null;
    }

    private String formatStatus(DspController controller, DspChannel[] channels) {
        if (channels.length == ALL_CHANNELS.length) {
            return formatStatus(controller, INPUT_CHANNELS)
                    + System.lineSeparator()
                    + formatStatus(controller, OUTPUT_CHANNELS);
        }
        if (channels.length == 1) {
            ChannelState state = controller.state().channel(channels[0]);
            return channels[0].displayName() + ": " + compactGain(state) + compactMute(state);
        }

        StringBuilder result = new StringBuilder(targetLabel(channels)).append(": ");
        for (DspChannel channel : channels) {
            if (result.length() > targetLabel(channels).length() + 2) {
                result.append(" | ");
            }
            ChannelState state = controller.state().channel(channel);
            result.append(shortChannelName(channel))
                    .append(' ')
                    .append(compactGain(state))
                    .append(compactMute(state));
        }
        return result.toString();
    }

    private String formatLimiterStatus(DspController controller, DspChannel[] outputs) {
        if (outputs.length == 1) {
            LimiterState limiter = controller.state().limiter(outputs[0]);
            return "LIMITER " + outputs[0].displayName() + ": "
                    + compactLimiterThreshold(limiter.thresholdDb());
        }
        StringBuilder result = new StringBuilder("LIMITER: ");
        for (DspChannel output : outputs) {
            if (result.length() > "LIMITER: ".length()) {
                result.append(" | ");
            }
            result.append(shortChannelName(output))
                    .append(' ')
                    .append(compactLimiterThreshold(controller.state().limiter(output).thresholdDb()));
        }
        return result.toString();
    }

    static String compactGain(Double gainDb) {
        return gainDb == null ? "? dB" : formatDb(gainDb) + " dB";
    }

    private static String compactGain(ChannelState state) {
        return compactGain(state.gainDb());
    }

    static String compactMute(Boolean muted) {
        return Boolean.TRUE.equals(muted) ? " 🔇" : "";
    }

    static String compactLimiterThreshold(Double thresholdDb) {
        return thresholdDb == null ? "? dB" : formatDb(thresholdDb) + " dB";
    }

    private static String compactMute(ChannelState state) {
        return compactMute(state.muted());
    }

    private static String targetLabel(DspChannel[] channels) {
        if (channels.length == 1) {
            return channels[0].displayName();
        }
        return channels[0] == DspChannel.IN_A ? "IN" : "OUT";
    }

    private static String shortChannelName(DspChannel channel) {
        String name = channel.displayName();
        return name.startsWith("In") ? name.substring(2) : name.substring(3);
    }

    private static boolean isLegacyCommand(String command) {
        return switch (command) {
            case "help", "hilfe", "?", "status", "refresh", "aktualisieren",
                    "up", "down", "lauter", "leiser", "mute", "unmute", "set", "step",
                    "limiter", "limit", "lim" -> true;
            default -> false;
        };
    }

    private static boolean isLimiterCommand(String command) {
        return command.equals("limiter") || command.equals("limit") || command.equals("lim");
    }

    static DspChannel limiterOutput(String value) {
        String normalized = normalizeWord(value);
        if (normalized.matches("[1-8]")) {
            normalized = "out" + normalized;
        }
        if (!normalized.matches("out[1-8]")) {
            throw new IllegalArgumentException("Unbekannter Limiter-Ausgang: " + value);
        }
        return DspChannel.parse(normalized);
    }

    private static boolean isTargetToken(String value) {
        try {
            channelsForTarget(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String displayTarget(String requested, DspChannel[] channels) {
        if (channels.length == 1) {
            return channels[0].displayName().toLowerCase(Locale.ROOT);
        }
        return channels[0] == DspChannel.IN_A ? "in" : "out";
    }

    private static String normalizeWord(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss");
    }

    private static boolean isNumber(String value) {
        return value != null && value.trim().replace(',', '.').matches("[+-]?\\d+(\\.\\d+)?");
    }

    private static boolean isSignedNumber(String value) {
        String normalized = value == null ? "" : value.trim();
        return (normalized.startsWith("+") || normalized.startsWith("-"))
                && normalized.length() > 1
                && isNumber(normalized);
    }

    private static double parseDb(String value, String usage) {
        if (!isNumber(value)) {
            throw new IllegalArgumentException("Benutzung: " + usage);
        }
        return Double.parseDouble(value.trim().replace(',', '.'));
    }

    private static double parseLimiterDb(String value, String usage) {
        return checkedLimiterDb(parseDb(value, usage));
    }

    private static double parseLimiterDbDelta(String value, String usage) {
        double deltaDb = parseDb(value, usage);
        if (!Double.isFinite(deltaDb) || deltaDb == 0.0) {
            throw new IllegalArgumentException("Limiter-Schritt muss ungleich 0 dB sein.");
        }
        return deltaDb;
    }

    static double checkedLimiterDb(double value) {
        if (!Double.isFinite(value) || value < MIN_LIMITER_DB || value > MAX_LIMITER_DB) {
            throw new IllegalArgumentException(
                    "Limiter-Threshold muss zwischen " + formatDb(MIN_LIMITER_DB)
                            + " und +" + formatDb(MAX_LIMITER_DB) + " dB liegen."
            );
        }
        return value;
    }

    private static String formatSignedDb(double value) {
        return (value >= 0 ? "+" : "") + formatDb(value);
    }

    private static String formatDb(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String targetUsage(String target, boolean group) {
        if (group) {
            return "Benutzung: " + target
                    + " <+|-|lauter|leiser|mute|unmute|status|set <dB>|step <dB>>";
        }
        return "Benutzung: " + target
                + " <+|-|dB|lauter|leiser|mute|unmute|status|set <dB>|step <dB>>";
    }

    private static String shortUsage() {
        return "Pegelraum: in + | out - | ina -20 | limiter + | limiter out1 -10 | status | help";
    }

    private static String limiterUsage() {
        return "Limiter: limiter <+|-|+1|-1|status|set <dB>|out1..out8 <dB|+|->>";
    }

    private String help() {
        double step = runtime.currentVolumeStepDb();
        return """
                Pegelraum – einfache Befehle

                Gruppen:
                  lauter            alle Eingänge +%.2f dB
                  leiser            alle Eingänge -%.2f dB
                  in +              alle Eingänge +%.2f dB
                  in -              alle Eingänge -%.2f dB
                  out +             alle Ausgänge +%.2f dB
                  out -             alle Ausgänge -%.2f dB
                  in mute           alle Eingänge stumm
                  out unmute        alle Ausgänge aktiv
                  in status         Eingänge anzeigen
                  out status        Ausgänge anzeigen

                Einzeln:
                  ina -20           InA auf -20 dB
                  inb +             InB +%.2f dB
                  out1 -10          Out1 auf -10 dB
                  out5 -            Out5 -%.2f dB
                  out1 mute         Out1 stumm
                  out1 unmute       Out1 aktiv

                Fester Gruppenpegel:
                  in set -20
                  out set -20

                Limiter-Thresholds:
                  limiter status     alle Limiter anzeigen
                  limiter +          alle Limiter +1 dB
                  limiter -          alle Limiter -1 dB
                  limiter +1         alle Limiter relativ +1 dB
                  limiter -1         alle Limiter relativ -1 dB
                  limiter set -10    alle Limiter auf -10 dB
                  limiter out3 -8    Limiter Out3 auf -8 dB
                  limiter out3 +     Limiter Out3 +1 dB

                Sonstiges:
                  status            alle Kanäle anzeigen
                  help              diese Hilfe

                Die alte Form !dsp volume ... funktioniert weiterhin.
                """.formatted(step, step, step, step, step, step, step, step).trim();
    }

}

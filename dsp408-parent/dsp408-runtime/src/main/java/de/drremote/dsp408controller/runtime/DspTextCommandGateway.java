package de.drremote.dsp408controller.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import de.drremote.dsp408controller.command.DspCommandProcessor;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.service.DspController;

/** Routes the textual CLI, Matrix and volume-room command surfaces. */
final class DspTextCommandGateway {
    private final DspRuntimeComponent runtime;
    private final DspController controller;
    private final Supplier<DspConnectionConfig> connectionConfig;
    private final VolumeRoomHandler volumeRoom;

    DspTextCommandGateway(
            DspRuntimeComponent runtime,
            DspController controller,
            Supplier<DspConnectionConfig> connectionConfig
    ) {
        this.runtime = runtime;
        this.controller = controller;
        this.connectionConfig = connectionConfig;
        this.volumeRoom = new VolumeRoomHandler(runtime);
    }

    synchronized String helpText() {
        return capture(DspCommandProcessor.CommandMode.CLI, "help", true);
    }

    synchronized String executeShell(String line) throws Exception {
        String normalized = normalize(line);
        if (normalized.isEmpty()) {
            return "";
        }

        String lifecycle = lifecycleCommand(normalized);
        if (lifecycle != null) {
            return executeLifecycle(lifecycle);
        }
        return capture(DspCommandProcessor.CommandMode.CLI, normalized, true);
    }

    synchronized String executeMatrix(String line, boolean isAdmin) {
        String normalized = normalize(line);
        if (normalized.isEmpty()) {
            return "";
        }
        String lifecycle = lifecycleCommand(normalized);
        if (lifecycle != null) {
            if (!isAdmin) return "Command disabled in Matrix bot.";
            try {
                return executeLifecycle(lifecycle);
            } catch (Exception error) {
                return "Error: " + error.getMessage();
            }
        }
        return capture(DspCommandProcessor.CommandMode.MATRIX, normalized, isAdmin);
    }

    synchronized String executeVolumeRoom(String line) throws Exception {
        return volumeRoom.handleVolumeRoom(line);
    }

    private String executeLifecycle(String command) throws Exception {
        return switch (command) {
            case "connect" -> runtime.connect();
            case "reconnect" -> runtime.reconnect();
            case "disconnect", "close" -> {
                runtime.disconnect();
                yield "Disconnected.";
            }
            default -> throw new IllegalArgumentException("Unknown lifecycle command");
        };
    }

    /** Match the processor's tokenization so lifecycle aliases cannot bypass its owner. */
    private static String lifecycleCommand(String line) {
        String[] parts = line.split("\\s+");
        int index = parts[0].equalsIgnoreCase("dsp") || parts[0].equalsIgnoreCase("!dsp") ? 1 : 0;
        if (index >= parts.length) return null;
        String command = parts[index].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "connect", "reconnect", "disconnect", "close" -> command;
            default -> null;
        };
    }

    private String capture(
            DspCommandProcessor.CommandMode mode,
            String line,
            boolean isAdmin
    ) {
        List<String> lines = new ArrayList<>();
        DspCommandProcessor processor = new DspCommandProcessor(
                controller,
                connectionConfig,
                lines::add,
                mode
        );

        String prefixed = normalize(line);
        if (!prefixed.isEmpty() && !prefixed.toLowerCase(Locale.ROOT).startsWith("!dsp")) {
            prefixed = "dsp " + prefixed;
        }

        processor.process(prefixed, isAdmin);
        return String.join(System.lineSeparator(), lines);
    }

    private static String normalize(String line) {
        return line == null ? "" : line.trim();
    }
}

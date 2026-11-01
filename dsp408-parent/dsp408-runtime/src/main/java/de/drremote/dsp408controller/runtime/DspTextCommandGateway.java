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

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("connect") || lower.equals("dsp connect") || lower.equals("!dsp connect")) {
            return runtime.connect();
        }
        if (lower.equals("disconnect")
                || lower.equals("dsp disconnect")
                || lower.equals("!dsp disconnect")) {
            runtime.disconnect();
            return "Disconnected.";
        }
        if (lower.equals("reconnect")
                || lower.equals("dsp reconnect")
                || lower.equals("!dsp reconnect")) {
            return runtime.reconnect();
        }
        return capture(DspCommandProcessor.CommandMode.CLI, normalized, true);
    }

    synchronized String executeMatrix(String line, boolean isAdmin) {
        String normalized = normalize(line);
        if (normalized.isEmpty()) {
            return "";
        }
        return capture(DspCommandProcessor.CommandMode.MATRIX, normalized, isAdmin);
    }

    synchronized String executeVolumeRoom(String line) throws Exception {
        return volumeRoom.handleVolumeRoom(line);
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

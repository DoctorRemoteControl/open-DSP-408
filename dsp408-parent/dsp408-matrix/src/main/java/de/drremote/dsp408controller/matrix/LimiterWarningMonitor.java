package de.drremote.dsp408controller.matrix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.StateDto;

final class LimiterWarningMonitor {
    static final String STATE_EVENT_TYPE = "de.drremote.dsp408.limiter_warnings";
    static final String STATE_KEY = "settings";

    enum RoomCommand {
        ENABLE,
        DISABLE,
        STATUS,
        HELP,
        IGNORE
    }

    private final DspService service;
    private final MatrixApiClient api;
    private final String roomId;
    private final long pollMs;
    private final long cooldownMs;
    private final AtomicBoolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Consumer<String> log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> activeOutputs = new LinkedHashSet<>();

    private ScheduledExecutorService executor;
    private long lastWarningAt;
    private int consecutiveErrors;

    LimiterWarningMonitor(DspService service,
                          MatrixApiClient api,
                          String roomId,
                          boolean enabledByDefault,
                          long pollMs,
                          long cooldownMs,
                          Consumer<String> log) {
        this.service = service;
        this.api = api;
        this.roomId = roomId;
        this.pollMs = pollMs;
        this.cooldownMs = cooldownMs;
        this.enabled = new AtomicBoolean(enabledByDefault);
        this.log = log;
    }

    synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        loadPersistedState();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dsp408-limiter-warning");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        activeOutputs.clear();
    }

    String handleRoomCommand(String text) throws Exception {
        return switch (parseRoomCommand(text)) {
            case ENABLE -> {
                setEnabled(true);
                yield "✅ Limiter-Warnungen sind AN.";
            }
            case DISABLE -> {
                setEnabled(false);
                yield "🔕 Limiter-Warnungen sind AUS.";
            }
            case STATUS -> statusText();
            case HELP -> """
                    Limiter-Warnraum – keine DSP-Steuerung

                    warnungen an
                    warnungen aus
                    status
                    help
                    """.trim();
            case IGNORE -> "";
        };
    }

    synchronized void setEnabled(boolean value) throws Exception {
        ObjectNode state = mapper.createObjectNode();
        state.put("enabled", value);
        state.put("updatedAt", Instant.now().toString());
        api.sendState(roomId, STATE_EVENT_TYPE, STATE_KEY, state);
        enabled.set(value);
        activeOutputs.clear();
        lastWarningAt = 0;
        log.accept("Limiter warnings " + (value ? "enabled" : "disabled"));
    }

    synchronized String statusText() {
        String active = activeOutputs.isEmpty()
                ? "keiner"
                : activeOutputs.stream().map(LimiterWarningMonitor::displayOutput).collect(Collectors.joining(", "));
        return "Limiter-Warnungen: " + (enabled.get() ? "AN" : "AUS")
                + System.lineSeparator()
                + "DSP: " + (service.isConnected() ? "verbunden" : "nicht verbunden")
                + System.lineSeparator()
                + "Limiter aktiv: " + active;
    }

    private void loadPersistedState() {
        try {
            JsonNode state = api.getState(roomId, STATE_EVENT_TYPE, STATE_KEY);
            if (state.has("enabled")) {
                enabled.set(state.path("enabled").asBoolean());
            }
        } catch (Exception ignored) {
            try {
                ObjectNode initial = mapper.createObjectNode();
                initial.put("enabled", enabled.get());
                initial.put("updatedAt", Instant.now().toString());
                api.sendState(roomId, STATE_EVENT_TYPE, STATE_KEY, initial);
            } catch (Exception e) {
                log.accept("Limiter warning state could not be initialized: " + e.getMessage());
            }
        }
    }

    private void pollSafely() {
        if (!running.get() || !enabled.get() || !service.isConnected()) {
            return;
        }
        try {
            pollOnce();
            consecutiveErrors = 0;
        } catch (Exception e) {
            consecutiveErrors++;
            if (consecutiveErrors == 1 || consecutiveErrors % 30 == 0) {
                log.accept("Limiter warning read failed: " + e.getMessage());
            }
        }
    }

    private synchronized void pollOnce() throws Exception {
        StateDto refreshed = service.requestRuntimeMeters();
        List<MeterDto> active = activeLimiters(refreshed.meters());
        Set<String> nowActive = active.stream()
                .map(MeterDto::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long now = System.currentTimeMillis();

        if (!nowActive.isEmpty()) {
            boolean newlyActive = !activeOutputs.containsAll(nowActive);
            boolean reminderDue = lastWarningAt > 0 && now - lastWarningAt >= cooldownMs;
            if (newlyActive || activeOutputs.isEmpty() || reminderDue) {
                api.sendText(roomId, warningText(active, reminderDue && !newlyActive), true);
                lastWarningAt = now;
            }
        } else if (!activeOutputs.isEmpty()) {
            String cleared = activeOutputs.stream()
                    .map(LimiterWarningMonitor::displayOutput)
                    .collect(Collectors.joining(", "));
            api.sendText(roomId, "✅ Limiter wieder frei: " + cleared, false);
            lastWarningAt = 0;
        }

        activeOutputs.clear();
        activeOutputs.addAll(nowActive);
    }

    static List<MeterDto> activeLimiters(MeterStateDto meters) {
        if (meters == null) {
            return List.of();
        }
        List<MeterDto> result = new ArrayList<>();
        for (MeterDto meter : meters.channels()) {
            if (meter != null
                    && meter.id() != null
                    && meter.id().toLowerCase(Locale.ROOT).matches("out[1-8]")
                    && Boolean.TRUE.equals(meter.limiterActive())
                    && meter.limiterConfirmedFromDevice()
                    && meter.limiterMappingConfirmed()) {
                result.add(meter);
            }
        }
        return List.copyOf(result);
    }

    static String warningText(List<MeterDto> active, boolean reminder) {
        String outputs = active.stream()
                .map(meter -> meter.displayName() == null || meter.displayName().isBlank()
                        ? displayOutput(meter.id())
                        : meter.displayName())
                .collect(Collectors.joining(", "));
        return "⚠️ LIMITER " + (reminder ? "WEITER AKTIV" : "AKTIV")
                + System.lineSeparator()
                + outputs
                + System.lineSeparator()
                + "Bitte Pegel reduzieren!";
    }

    static RoomCommand parseRoomCommand(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "warnungen an", "warnung an", "warnings on", "an" -> RoomCommand.ENABLE;
            case "warnungen aus", "warnung aus", "warnings off", "aus" -> RoomCommand.DISABLE;
            case "status" -> RoomCommand.STATUS;
            case "help", "hilfe", "?" -> RoomCommand.HELP;
            default -> RoomCommand.IGNORE;
        };
    }

    private static String displayOutput(String id) {
        if (id == null || id.isBlank()) {
            return "Ausgang";
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("out[1-8]")
                ? "Out" + normalized.substring(3)
                : id;
    }
}

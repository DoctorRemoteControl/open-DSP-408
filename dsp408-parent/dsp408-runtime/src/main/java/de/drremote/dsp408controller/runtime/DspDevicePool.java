package de.drremote.dsp408controller.runtime;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.DspInstanceDto;
import de.drremote.dsp408.api.DspService;

/**
 * Owns the configured secondary DSP runtimes and their lifecycle.
 *
 * <p>The primary runtime remains an OSGi component. Secondary runtimes are
 * created from the same configuration and are closed atomically whenever the
 * configured pool is replaced.</p>
 */
final class DspDevicePool implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DspDevicePool.class);
    private static final int DEFAULT_DSP_PORT = 9761;

    private final DspRuntimeComponent primary;
    private final boolean enabled;
    private volatile Map<String, DspRuntimeComponent> devices = Map.of();

    DspDevicePool(DspRuntimeComponent primary, boolean enabled) {
        this.primary = primary;
        this.enabled = enabled;
    }

    List<DspInstanceDto> instances() {
        if (!enabled) {
            return List.of(primary.toInstanceDto(true));
        }

        List<DspInstanceDto> instances = new ArrayList<>();
        instances.add(primary.toInstanceDto(true));
        for (DspRuntimeComponent device : devices.values()) {
            instances.add(device.toInstanceDto(false));
        }
        return List.copyOf(instances);
    }

    DspService resolve(String requestedId) {
        String normalized = normalizeId(requestedId);
        if (normalized.equals(primary.dspId())) {
            return primary;
        }
        DspRuntimeComponent device = devices.get(normalized);
        if (device == null) {
            throw new IllegalArgumentException("Unknown DSP id: " + requestedId);
        }
        return device;
    }

    void rebuild(Dsp408Configuration configuration) {
        if (!enabled) {
            return;
        }

        Map<String, DspRuntimeComponent> replacement = new LinkedHashMap<>();
        for (DspDefinition definition : parseDefinitions(configuration.dsps())) {
            if (definition.id().equals(primary.dspId())) {
                LOG.warn(
                        "Ignoring additional DSP '{}' because it is the default DSP id",
                        definition.id()
                );
                continue;
            }
            DspRuntimeComponent device = new DspRuntimeComponent(false);
            device.activate(new StaticDsp408Configuration(
                    definition.id(),
                    definition.ip(),
                    definition.port(),
                    configuration.dsp_library(),
                    configuration.auto_connect(),
                    configuration.auto_read_on_connect(),
                    configuration.meter_poll_interval_ms(),
                    configuration.volume_step_db(),
                    new String[0]
            ));
            DspRuntimeComponent replaced = replacement.put(definition.id(), device);
            if (replaced != null) {
                replaced.deactivate();
            }
        }

        Map<String, DspRuntimeComponent> old = devices;
        devices = Map.copyOf(replacement);
        closeDevices(old);
    }

    @Override
    public void close() {
        Map<String, DspRuntimeComponent> old = devices;
        devices = Map.of();
        closeDevices(old);
    }

    static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "main";
        }
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]*")) {
            throw new IllegalArgumentException(
                    "DSP id must contain only letters, digits, dot, underscore or dash: " + id
            );
        }
        return normalized;
    }

    static List<DspDefinition> parseDefinitions(String[] entries) {
        List<DspDefinition> definitions = new ArrayList<>();
        if (entries == null) {
            return definitions;
        }

        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.trim();
            String[] split = text.split("=", 2);
            if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                throw new IllegalArgumentException(
                        "DSP definition must use id=ip or id=ip:port: " + entry
                );
            }

            String id = normalizeId(split[0]);
            String endpoint = split[1].trim();
            int port = DEFAULT_DSP_PORT;
            String ip = endpoint;
            int colon = endpoint.lastIndexOf(':');
            if (colon > 0 && colon < endpoint.length() - 1) {
                ip = endpoint.substring(0, colon).trim();
                port = Integer.parseInt(endpoint.substring(colon + 1).trim());
            }
            if (ip.isBlank()) {
                throw new IllegalArgumentException("DSP IP is missing in definition: " + entry);
            }
            definitions.add(new DspDefinition(id, ip, port));
        }
        return List.copyOf(definitions);
    }

    private static void closeDevices(Map<String, DspRuntimeComponent> devices) {
        for (DspRuntimeComponent device : devices.values()) {
            device.deactivate();
        }
    }

    record DspDefinition(String id, String ip, int port) {
    }

    private record StaticDsp408Configuration(
            String dspId,
            String dspIp,
            int dspPort,
            String dspLibrary,
            boolean autoConnect,
            boolean autoReadOnConnect,
            long meterPollIntervalMs,
            double volumeStepDb,
            String[] dsps
    ) implements Dsp408Configuration {
        @Override
        public String dsp_id() {
            return dspId;
        }

        @Override
        public String dsp_ip() {
            return dspIp;
        }

        @Override
        public int dsp_port() {
            return dspPort;
        }

        @Override
        public String dsp_library() {
            return dspLibrary;
        }

        @Override
        public boolean auto_connect() {
            return autoConnect;
        }

        @Override
        public boolean auto_read_on_connect() {
            return autoReadOnConnect;
        }

        @Override
        public long meter_poll_interval_ms() {
            return meterPollIntervalMs;
        }

        @Override
        public double volume_step_db() {
            return volumeStepDb;
        }

        @Override
        public String[] dsps() {
            return dsps.clone();
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Dsp408Configuration.class;
        }
    }
}

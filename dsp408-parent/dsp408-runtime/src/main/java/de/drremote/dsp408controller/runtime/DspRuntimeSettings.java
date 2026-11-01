package de.drremote.dsp408controller.runtime;

import java.util.Objects;

import de.drremote.dsp408controller.core.net.DspConnectionConfig;

/** Owns the current OSGi settings and derives runtime-safe configuration values. */
final class DspRuntimeSettings {
    private static final String DEFAULT_IP = "192.168.0.166";
    private static final int DEFAULT_PORT = 9761;
    private static final long MIN_METER_POLL_INTERVAL_MS = 200L;
    private static final double DEFAULT_VOLUME_STEP_DB = 1.0;

    private volatile Dsp408Configuration configuration;
    private volatile String instanceId = "main";

    synchronized Update apply(Dsp408Configuration newConfiguration) {
        Objects.requireNonNull(newConfiguration, "DSP configuration is required");

        Dsp408Configuration oldConfiguration = configuration;
        String oldInstanceId = instanceId;
        String newInstanceId = DspDevicePool.normalizeId(newConfiguration.dsp_id());

        configuration = newConfiguration;
        instanceId = newInstanceId;

        return new Update(
                oldInstanceId,
                newInstanceId,
                oldConfiguration != null && connectionChanged(oldConfiguration, newConfiguration),
                oldConfiguration == null
                        || oldConfiguration.meter_poll_interval_ms()
                        != newConfiguration.meter_poll_interval_ms()
        );
    }

    String instanceId() {
        return instanceId;
    }

    DspConnectionConfig connectionConfig() {
        Dsp408Configuration current = configuration;
        if (current == null) {
            return DspConnectionConfig.defaultConfig(DEFAULT_IP, DEFAULT_PORT);
        }
        return DspConnectionConfig.defaultConfig(current.dsp_ip(), current.dsp_port());
    }

    boolean autoConnect() {
        Dsp408Configuration current = configuration;
        return current != null && current.auto_connect();
    }

    long meterPollIntervalMs() {
        Dsp408Configuration current = configuration;
        long configured = current == null
                ? MIN_METER_POLL_INTERVAL_MS
                : current.meter_poll_interval_ms();
        return Math.max(MIN_METER_POLL_INTERVAL_MS, configured);
    }

    double volumeStepDb() {
        Dsp408Configuration current = configuration;
        if (current == null) {
            return DEFAULT_VOLUME_STEP_DB;
        }
        double configured = current.volume_step_db();
        return configured > 0.0 ? configured : DEFAULT_VOLUME_STEP_DB;
    }

    private static boolean connectionChanged(
            Dsp408Configuration oldConfiguration,
            Dsp408Configuration newConfiguration) {
        return !oldConfiguration.dsp_ip().equals(newConfiguration.dsp_ip())
                || oldConfiguration.dsp_port() != newConfiguration.dsp_port()
                || !oldConfiguration.dsp_library().equalsIgnoreCase(newConfiguration.dsp_library());
    }

    record Update(
            String oldInstanceId,
            String newInstanceId,
            boolean connectionChanged,
            boolean meterPollIntervalChanged) {

        boolean instanceIdChanged() {
            return !oldInstanceId.equals(newInstanceId);
        }
    }
}

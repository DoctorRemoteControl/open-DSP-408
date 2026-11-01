package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.Test;

class DspRuntimeSettingsTest {

    @Test
    void exposesSafeDefaultsBeforeOsgiActivation() {
        DspRuntimeSettings settings = new DspRuntimeSettings();

        assertEquals("main", settings.instanceId());
        assertEquals("192.168.0.166", settings.connectionConfig().ip());
        assertEquals(9761, settings.connectionConfig().port());
        assertFalse(settings.autoConnect());
        assertEquals(200L, settings.meterPollIntervalMs());
        assertEquals(1.0, settings.volumeStepDb());
    }

    @Test
    void appliesAndNormalizesRuntimeConfiguration() {
        DspRuntimeSettings settings = new DspRuntimeSettings();
        Config first = config(" Rack.Left_1 ", "192.168.1.166", 9861, "fir408", true, 350L, 0.5);

        DspRuntimeSettings.Update update = settings.apply(first);

        assertTrue(update.instanceIdChanged());
        assertFalse(update.connectionChanged());
        assertTrue(update.meterPollIntervalChanged());
        assertEquals("rack.left_1", settings.instanceId());
        assertEquals("192.168.1.166", settings.connectionConfig().ip());
        assertEquals(9861, settings.connectionConfig().port());
        assertTrue(settings.autoConnect());
        assertEquals(350L, settings.meterPollIntervalMs());
        assertEquals(0.5, settings.volumeStepDb());
    }

    @Test
    void distinguishesReconnectChangesFromLiveRuntimeChanges() {
        DspRuntimeSettings settings = new DspRuntimeSettings();
        settings.apply(config("main", "192.168.0.166", 9761, "fir408", false, 200L, 1.0));

        DspRuntimeSettings.Update liveUpdate = settings.apply(
                config("stage", "192.168.0.166", 9761, "FIR408", true, 500L, 2.0));
        assertFalse(liveUpdate.connectionChanged());
        assertTrue(liveUpdate.meterPollIntervalChanged());
        assertTrue(liveUpdate.instanceIdChanged());

        DspRuntimeSettings.Update reconnectUpdate = settings.apply(
                config("stage", "192.168.0.167", 9761, "fir408", true, 500L, 2.0));
        assertTrue(reconnectUpdate.connectionChanged());
        assertFalse(reconnectUpdate.meterPollIntervalChanged());
    }

    @Test
    void clampsUnsafePollingAndVolumeValues() {
        DspRuntimeSettings settings = new DspRuntimeSettings();
        settings.apply(config("main", "192.168.0.166", 9761, "fir408", false, 20L, 0.0));

        assertEquals(200L, settings.meterPollIntervalMs());
        assertEquals(1.0, settings.volumeStepDb());
    }

    private static Config config(
            String id,
            String ip,
            int port,
            String library,
            boolean autoConnect,
            long meterIntervalMs,
            double volumeStepDb) {
        return new Config(
                id,
                ip,
                port,
                library,
                autoConnect,
                true,
                meterIntervalMs,
                volumeStepDb,
                new String[0]);
    }

    private record Config(
            String id,
            String ip,
            int port,
            String library,
            boolean autoConnect,
            boolean autoReadOnConnect,
            long meterIntervalMs,
            double volumeStepDb,
            String[] additionalDsps) implements Dsp408Configuration {

        @Override
        public String registry_mode() { return "static"; }

        @Override
        public String registry_file() { return ""; }

        @Override
        public String dsp_id() {
            return id;
        }

        @Override
        public String dsp_ip() {
            return ip;
        }

        @Override
        public int dsp_port() {
            return port;
        }

        @Override
        public String dsp_library() {
            return library;
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
            return meterIntervalMs;
        }

        @Override
        public double volume_step_db() {
            return volumeStepDb;
        }

        @Override
        public String[] dsps() {
            return additionalDsps;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Dsp408Configuration.class;
        }
    }
}

package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.annotation.Annotation;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DspRuntimeLifecycleTest {
    @Test
    void activationAndStatusStayResponsiveDuringBlockedBootstrap() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(2_000);
            DspRuntimeComponent runtime = new DspRuntimeComponent(false);
            CompletableFuture<Void> activating = CompletableFuture.runAsync(
                    () -> runtime.activate(config(server.getLocalPort(), new String[0])));
            try (Socket peer = server.accept()) {
                try {
                    activating.get(750, TimeUnit.MILLISECONDS);
                    assertFalse(runtime.isConnected());
                    assertFalse(runtime.getDspInstances().getFirst().connected());
                    assertFalse(runtime.getSettingsCacheStatus().ready());
                    CompletableFuture.runAsync(runtime::disconnect).get(750, TimeUnit.MILLISECONDS);
                    assertEquals("DISCONNECTED", runtime.getSettingsCacheStatus().phase());
                } finally {
                    peer.close();
                    activating.get(2, TimeUnit.SECONDS);
                    runtime.deactivate();
                }
            }
        }
    }

    @Test
    void secondaryBootstrapDoesNotBlockPoolActivationOrShutdown() throws Exception {
        try (ServerSocket primaryServer = new ServerSocket(0);
             ServerSocket secondaryServer = new ServerSocket(0)) {
            secondaryServer.setSoTimeout(2_000);
            DspRuntimeComponent runtime = new DspRuntimeComponent();
            CompletableFuture<Void> activating = CompletableFuture.runAsync(() -> runtime.activate(config(
                    primaryServer.getLocalPort(),
                    new String[]{"secondary=127.0.0.1:" + secondaryServer.getLocalPort()})));
            try (Socket secondary = secondaryServer.accept()) {
                try {
                    activating.get(750, TimeUnit.MILLISECONDS);
                    assertEquals(2, runtime.getDspInstances().size());
                    assertFalse(runtime.forDsp("secondary").isConnected());
                    CompletableFuture.runAsync(runtime::deactivate).get(750, TimeUnit.MILLISECONDS);
                    assertEquals(1, runtime.getDspInstances().size());
                } finally {
                    secondary.close();
                    activating.get(2, TimeUnit.SECONDS);
                    runtime.deactivate();
                }
            }
        }
    }

    @Test
    void configurationChangeCancelsBlockedBootstrapAndReturnsBeforeReplacementIsReady() throws Exception {
        try (ServerSocket firstServer = new ServerSocket(0);
             ServerSocket replacementServer = new ServerSocket(0)) {
            firstServer.setSoTimeout(2_000);
            replacementServer.setSoTimeout(2_000);
            DspRuntimeComponent runtime = new DspRuntimeComponent(false);
            CompletableFuture<Void> activating = CompletableFuture.runAsync(
                    () -> runtime.activate(config(firstServer.getLocalPort(), new String[0])));
            try (Socket first = firstServer.accept()) {
                CompletableFuture<Void> modifying = CompletableFuture.runAsync(
                        () -> runtime.modified(config(replacementServer.getLocalPort(), new String[0])));
                try {
                    modifying.get(750, TimeUnit.MILLISECONDS);
                    try (Socket replacement = replacementServer.accept()) {
                        assertFalse(runtime.isConnected());
                        assertEquals(replacementServer.getLocalPort(),
                                runtime.getDspInstances().getFirst().port());
                        CompletableFuture.runAsync(runtime::disconnect).get(750, TimeUnit.MILLISECONDS);
                    }
                } finally {
                    first.close();
                    activating.get(2, TimeUnit.SECONDS);
                    modifying.get(2, TimeUnit.SECONDS);
                    runtime.deactivate();
                }
            }
        }
    }

    static Dsp408Configuration config(int port, String[] devices) {
        return new Dsp408Configuration() {
            public String dsp_id() { return "main"; }
            public String dsp_ip() { return "127.0.0.1"; }
            public int dsp_port() { return port; }
            public String dsp_library() { return "fir408"; }
            public boolean auto_connect() { return true; }
            public boolean auto_read_on_connect() { return true; }
            public long meter_poll_interval_ms() { return 200L; }
            public double volume_step_db() { return 1.0; }
            public String[] dsps() { return devices; }
            public Class<? extends Annotation> annotationType() { return Dsp408Configuration.class; }
        };
    }
}

package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspService;

class DspTextCommandLifecycleTest {
    @TempDir Path temporary;

    @Test
    void removedShellTargetRejectsWhitespaceLifecycleCommandsBeforeNetwork() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(100);
            DspRuntimeComponent registry = registry();
            DspService stale = removedTarget(registry, server.getLocalPort());
            try {
                CompletableFuture<Object> request = CompletableFuture.supplyAsync(() -> {
                    try { return stale.executeShell("!dsp   connect ignored"); }
                    catch (Exception error) { return error; }
                });
                assertInstanceOf(IllegalStateException.class, request.get(750, TimeUnit.MILLISECONDS));
                assertThrows(SocketTimeoutException.class, server::accept);
            } finally {
                stale.disconnect();
                registry.deactivate();
            }
        }
    }

    @Test
    void removedMatrixAdminTargetCannotReconnectThroughCommandProcessor() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(100);
            DspRuntimeComponent registry = registry();
            DspService stale = removedTarget(registry, server.getLocalPort());
            try {
                CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> {
                    try { return stale.executeMatrix("!dsp   reconnect", true); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                assertTrue(request.get(750, TimeUnit.MILLISECONDS).contains("closed"));
                assertThrows(SocketTimeoutException.class, server::accept);
            } finally {
                stale.disconnect();
                registry.deactivate();
            }
        }
    }

    @Test
    void matrixNonAdminLifecycleCommandsRemainDisabled() throws Exception {
        DspRuntimeComponent registry = registry();
        try {
            assertEquals("Command disabled in Matrix bot.", registry.executeMatrix("connect", false));
            assertEquals("Command disabled in Matrix bot.", registry.executeMatrix("!dsp   reconnect", false));
        } finally { registry.deactivate(); }
    }

    private DspRuntimeComponent registry() {
        DspRuntimeComponent registry = new DspRuntimeComponent();
        registry.activate(DspManagedRegistryTest.config(temporary.resolve("devices.json"), "managed"));
        return registry;
    }

    private static DspService removedTarget(DspRuntimeComponent registry, int port) {
        registry.registerDevice(new DspDeviceDefinitionDto("one", "127.0.0.1", port, "auto", false));
        DspService stale = registry.forDsp("one");
        registry.removeDevice("one");
        return stale;
    }
}

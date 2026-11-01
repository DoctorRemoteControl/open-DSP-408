package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.drremote.dsp408.api.DspDeviceConflictException;
import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspDeviceNotFoundException;
import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.DspDeviceStorageException;

class DspManagedRegistryTest {
    @TempDir Path temporary;

    @Test
    void managedBootHasNoPhysicalRootOrDevices() throws Exception {
        DspRuntimeComponent runtime = start(temporary.resolve("devices.json"));
        try {
            assertEquals("", runtime.dspId());
            assertEquals("managed", runtime.getDeviceRegistry().mode());
            assertTrue(runtime.getDeviceRegistry().persistent());
            assertTrue(runtime.getDspInstances().isEmpty());
            assertThrows(DspDeviceConflictException.class, runtime::connect);
            assertThrows(DspDeviceConflictException.class, runtime::reconnect);
            assertThrows(DspDeviceConflictException.class, () -> runtime.forDsp(""));
        } finally { runtime.deactivate(); }
    }

    @Test
    void registrationIsOfflineAndSurvivesRestartWithItsLibrary() throws Exception {
        Path file = temporary.resolve("devices.json");
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(150);
            var definition = device("left", "127.0.0.1", server.getLocalPort(), false);
            DspRuntimeComponent first = start(file);
            try {
                assertTrue(first.registerDevice(definition).created());
                assertEquals(definition, first.getDeviceDefinition(" LEFT "));
                assertFalse(first.forDsp("left").isConnected());
                assertThrows(SocketTimeoutException.class, server::accept);
                assertTrue(Files.readString(file).contains("\"schemaVersion\""));
            } finally { first.deactivate(); }
            DspRuntimeComponent restarted = start(file);
            try {
                assertEquals(List.of(definition), restarted.getDeviceRegistry().devices());
                assertEquals(1, restarted.getDspInstances().size());
                assertFalse(restarted.getDspInstances().getFirst().defaultDevice());
                assertThrows(SocketTimeoutException.class, server::accept);
            } finally { restarted.deactivate(); }
        }
    }

    @Test
    void identicalRegistrationIsIdempotentAndConflictsPreserveOriginalRuntime() throws Exception {
        DspRuntimeComponent runtime = start(temporary.resolve("devices.json"));
        try {
            var first = device("left", "DSP.Example.INVALID.", 9761, false);
            runtime.registerDevice(first);
            DspService original = runtime.forDsp("left");
            assertFalse(runtime.registerDevice(device("LEFT", "dsp.example.invalid", 9761, false)).created());
            assertSame(original, runtime.forDsp("left"));
            assertThrows(DspDeviceConflictException.class,
                    () -> runtime.registerDevice(device("left", "other.invalid", 9761, false)));
            assertThrows(DspDeviceConflictException.class,
                    () -> runtime.registerDevice(device("right", "DSP.EXAMPLE.INVALID", 9761, false)));
            assertThrows(DspDeviceConflictException.class,
                    () -> runtime.registerDevice(device("left", "dsp.example.invalid", 9761, true)));
            assertEquals(List.of(first), runtime.getDeviceRegistry().devices());
            assertSame(original, runtime.forDsp("left"));
        } finally { runtime.deactivate(); }
    }

    @Test
    void mutationsPreserveOtherChildrenAndRemovedReferencesCannotReconnect() throws Exception {
        Path file = temporary.resolve("devices.json");
        DspRuntimeComponent runtime = start(file);
        try {
            runtime.registerDevice(device("one", "one.invalid", 9761, false));
            runtime.registerDevice(device("two", "two.invalid", 9761, false));
            DspService stale = runtime.forDsp("one");
            DspService other = runtime.forDsp("two");
            runtime.removeDevice("ONE");
            assertSame(other, runtime.forDsp("two"));
            assertThrows(DspDeviceNotFoundException.class, () -> runtime.forDsp("one"));
            assertThrows(DspDeviceNotFoundException.class, () -> runtime.removeDevice("one"));
            assertThrows(IllegalStateException.class, stale::connect);
            assertThrows(IllegalStateException.class, stale::reconnect);
            runtime.registerDevice(device("one", "replacement.invalid", 9761, false));
            assertNotSame(stale, runtime.forDsp("one"));
            assertSame(other, runtime.forDsp("two"));
            assertThrows(IllegalStateException.class, stale::connect);
        } finally { runtime.deactivate(); }
        DspRuntimeComponent restart = start(file);
        try {
            assertEquals(List.of("two", "one"), restart.getDeviceRegistry().devices().stream().map(DspDeviceDefinitionDto::id).toList());
        } finally { restart.deactivate(); }
    }

    @Test
    void failedMutationDoesNotPublishOrRemoveLiveStateOrDamagePreviousFile() throws Exception {
        Path directory = temporary.resolve("storage");
        Path file = directory.resolve("devices.json");
        DspRuntimeComponent runtime = start(file);
        try {
            var definition = device("one", "one.invalid", 9761, false);
            runtime.registerDevice(definition);
            DspService original = runtime.forDsp("one");
            String previous = Files.readString(file);
            Path moved = temporary.resolve("saved-storage");
            Files.move(directory, moved);
            Files.writeString(directory, "blocks creation of a temporary file");
            assertThrows(DspDeviceStorageException.class,
                    () -> runtime.registerDevice(device("two", "two.invalid", 9761, false)));
            assertThrows(IllegalStateException.class, () -> runtime.removeDevice("one"));
            assertEquals(List.of(definition), runtime.getDeviceRegistry().devices());
            assertSame(original, runtime.forDsp("one"));
            assertEquals(previous, Files.readString(moved.resolve("devices.json")));
        } finally { runtime.deactivate(); }
    }

    @Test
    void corruptUnknownVersionAndDuplicateStoredDefinitionsFailClosed() throws Exception {
        String definition = "{\"id\":\"one\",\"host\":\"localhost\",\"port\":9761,\"library\":\"auto\",\"autoConnect\":false}";
        for (String json : List.of("broken", "{\"schemaVersion\":2,\"devices\":[]}",
                "{\"schemaVersion\":1,\"devices\":[" + definition + "," + definition + "]}",
                "{\"schemaVersion\":1,\"devices\":[" + definition + "," + definition.replace("one", "two") + "]}",
                "{\"schemaVersion\":1,\"devices\":null}",
                "{\"schemaVersion\":1,\"devices\":[]} trailing")) {
            Path file = temporary.resolve("bad.json");
            Files.writeString(file, json);
            DspRuntimeComponent runtime = new DspRuntimeComponent();
            try {
                assertThrows(IllegalStateException.class, () -> runtime.activate(config(file, "managed")), json);
                assertEquals(json, Files.readString(file));
            } finally { runtime.deactivate(); }
        }
    }

    @Test
    void activationRejectsUnwritableStorageBeforePublishingManagedMode() throws Exception {
        Path blocked = temporary.resolve("blocked");
        Files.writeString(blocked, "regular file");
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        try {
            assertThrows(IllegalStateException.class, () -> runtime.activate(config(blocked.resolve("devices.json"), "managed")));
        } finally { runtime.deactivate(); }
    }

    @Test
    void liveStorageOrModeChangesAreRejectedWithoutDiscardingRegistrations() throws Exception {
        Path originalFile = temporary.resolve("devices.json");
        DspRuntimeComponent runtime = start(originalFile);
        try {
            var definition = device("one", "one.invalid", 9761, false);
            runtime.registerDevice(definition);
            DspService original = runtime.forDsp("one");
            assertThrows(DspDeviceConflictException.class,
                    () -> runtime.modified(config(temporary.resolve("other.json"), "managed")));
            assertThrows(DspDeviceConflictException.class, () -> runtime.modified(config(originalFile, "static")));
            runtime.modified(config(originalFile, "managed"));
            assertSame(original, runtime.forDsp("one"));
            assertEquals(List.of(definition), runtime.getDeviceRegistry().devices());
            assertFalse(Files.exists(temporary.resolve("other.json")));
        } finally { runtime.deactivate(); }
    }

    @Test
    void autoConnectStartsOnlyAfterPersistenceAndRemovalCancelsBootstrap() throws Exception {
        Path file = temporary.resolve("devices.json");
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(2_000);
            DspRuntimeComponent runtime = start(file);
            try {
                runtime.registerDevice(device("one", "127.0.0.1", server.getLocalPort(), true));
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(2_000);
                    assertTrue(Files.readString(file).contains("\"one\""));
                    DspService stale = runtime.forDsp("one");
                    CompletableFuture.runAsync(() -> runtime.removeDevice("one")).get(750, TimeUnit.MILLISECONDS);
                    assertThrows(IllegalStateException.class, stale::connect);
                    assertThrows(IllegalStateException.class, stale::reconnect);
                    byte[] data = new byte[1024];
                    while (peer.getInputStream().read(data) >= 0) { /* startup bytes followed by EOF */ }
                    assertTrue(runtime.getDspInstances().isEmpty());
                }
            } finally { runtime.deactivate(); }
        }
    }

    @Test
    void statusOfOtherDevicesDoesNotWaitForRemovedChildTeardown() throws Exception {
        Path file = temporary.resolve("devices.json");
        DspRuntimeComponent runtime = start(file);
        CompletableFuture<Void> removing = null;
        try {
            runtime.registerDevice(device("one", "one.invalid", 9761, false));
            runtime.registerDevice(device("two", "two.invalid", 9761, false));
            DspService removed = runtime.forDsp("one");
            DspService retained = runtime.forDsp("two");
            // Real deactivation synchronizes on its concrete runtime. Hold that
            // monitor to simulate slow resource teardown after the durable removal.
            synchronized (removed) {
                removing = CompletableFuture.runAsync(() -> runtime.removeDevice("one"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (Files.readString(file).contains("\"one\"") && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertFalse(Files.readString(file).contains("\"one\""));
                CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> {
                    assertEquals(List.of("two"), runtime.getDeviceRegistry().devices().stream().map(DspDeviceDefinitionDto::id).toList());
                    assertEquals(1, runtime.getDspInstances().size());
                    assertSame(retained, runtime.forDsp("two"));
                });
                reading.get(750, TimeUnit.MILLISECONDS);
            }
            removing.get(2, TimeUnit.SECONDS);
        } finally {
            if (removing != null) removing.get(2, TimeUnit.SECONDS);
            runtime.deactivate();
        }
    }

    @Test
    void offlineManagedChildrenDoNotAllocateWatchdogThreads() throws Exception {
        DspRuntimeComponent runtime = start(temporary.resolve("devices.json"));
        try {
            runtime.registerDevice(device("offline-managed-test", "unresolvable.invalid", 9761, false));
            assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread ->
                    thread.isAlive() && thread.getName().equals("dsp408-connection-watchdog-offline-managed-test")));
        } finally { runtime.deactivate(); }
    }

    @Test
    void externallyChangedRegistryIsNotOverwrittenByLaterMutations() throws Exception {
        Path file = temporary.resolve("devices.json");
        DspRuntimeComponent runtime = start(file);
        try {
            runtime.registerDevice(device("one", "one.invalid", 9761, false));
            DspService original = runtime.forDsp("one");
            Files.writeString(file, "external corrupt content");
            assertThrows(DspDeviceStorageException.class,
                    () -> runtime.registerDevice(device("two", "two.invalid", 9761, false)));
            assertEquals("external corrupt content", Files.readString(file));
            assertSame(original, runtime.forDsp("one"));
            assertEquals(1, runtime.getDeviceRegistry().devices().size());
        } finally { runtime.deactivate(); }
    }

    @Test
    void bundleDataDirectorySuppliesDefaultStorage() throws Exception {
        Path file = temporary.resolve("bundle-data/devices.json");
        org.osgi.framework.BundleContext context = (org.osgi.framework.BundleContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.osgi.framework.BundleContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getDataFile")) return file.toFile();
                    throw new AssertionError(method);
                });
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        try {
            runtime.activateComponent(context, config(null, "managed"));
            runtime.registerDevice(device("one", "one.invalid", 9761, false));
            assertTrue(Files.readString(file).contains("\"one\""));
        } finally { runtime.deactivate(); }
        DspRuntimeComponent restart = start(file);
        try { assertEquals("one", restart.getDeviceRegistry().devices().getFirst().id()); }
        finally { restart.deactivate(); }
    }

    @Test
    void staticConnectCannotStartAfterManagedModeWasPublished() throws Exception {
        assertModeTransitionCancelsUnadmittedRootConnection(false);
    }

    @Test
    void staticReconnectCannotStartAfterManagedModeWasPublished() throws Exception {
        assertModeTransitionCancelsUnadmittedRootConnection(true);
    }

    private void assertModeTransitionCancelsUnadmittedRootConnection(boolean reconnect) throws Exception {
        Path file = temporary.resolve("transition.json");
        try (ServerSocket peer = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            peer.setSoTimeout(200);
            Dsp408Configuration base = config(file, "static");
            Dsp408Configuration initial = (Dsp408Configuration) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Dsp408Configuration.class},
                    (proxy, method, args) -> method.getName().equals("dsp_port")
                            ? peer.getLocalPort() : method.invoke(base, args));
            DspRuntimeComponent root = new DspRuntimeComponent();
            root.activate(initial);
            var managerField = DspRuntimeComponent.class.getDeclaredField("connectionManager");
            managerField.setAccessible(true);
            Object manager = managerField.get(root);
            var lockField = DspConnectionManager.class.getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object lifecycleLock = lockField.get(manager);
            java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
            Thread connecting = new Thread(() -> {
                try { if (reconnect) root.reconnect(); else root.connect(); }
                catch (Throwable error) { outcome.set(error); }
            });
            try {
                // Hold locks in the production order. Before the fix the call
                // passes mode validation and blocks only at the manager; after
                // the fix admission waits for the root mode transition first.
                synchronized (root) {
                    synchronized (lifecycleLock) {
                        connecting.start();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (connecting.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                            Thread.sleep(1);
                        }
                        assertEquals(Thread.State.BLOCKED, connecting.getState());
                        Dsp408Configuration managedBase = config(file, "managed");
                        Dsp408Configuration managed = (Dsp408Configuration) Proxy.newProxyInstance(
                                getClass().getClassLoader(), new Class<?>[]{Dsp408Configuration.class},
                                (proxy, method, args) -> method.getName().equals("dsp_port")
                                        ? peer.getLocalPort() : method.invoke(managedBase, args));
                        root.modified(managed);
                        assertEquals("managed", root.getDeviceRegistry().mode());
                        assertTrue(root.getDspInstances().isEmpty());
                    }
                }
                assertThrows(SocketTimeoutException.class, () -> { try (Socket ignored = peer.accept()) { } });
                connecting.join(1000);
                assertFalse(connecting.isAlive(), "Mode rejection must finish without bootstrap");
                assertInstanceOf(DspDeviceConflictException.class, outcome.get());
            } finally {
                root.deactivate();
                connecting.join(2000);
            }
        }
    }

    private DspRuntimeComponent start(Path file) {
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        runtime.activate(config(file, "managed"));
        return runtime;
    }

    private static DspDeviceDefinitionDto device(String id, String host, int port, boolean autoConnect) {
        return new DspDeviceDefinitionDto(id, host, port, "auto", autoConnect);
    }

    static Dsp408Configuration config(Path file, String mode) {
        return (Dsp408Configuration) Proxy.newProxyInstance(Dsp408Configuration.class.getClassLoader(),
                new Class<?>[]{Dsp408Configuration.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "dsp_id" -> "main";
                    case "dsp_ip" -> "127.0.0.1";
                    case "dsp_port" -> 9761;
                    case "dsp_library" -> "fir408";
                    case "auto_connect", "auto_read_on_connect" -> false;
                    case "meter_poll_interval_ms" -> 200L;
                    case "volume_step_db" -> 1.0;
                    case "dsps" -> new String[0];
                    case "registry_mode" -> mode;
                    case "registry_file" -> file == null ? "" : file.toString();
                    case "annotationType" -> Dsp408Configuration.class;
                    case "toString" -> "managed-test-configuration";
                    default -> throw new AssertionError(method);
                });
    }
}

package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.service.DspController;

class DspConnectionManagerTest {
    @Test
    void disconnectCancelsBlockedBootstrapWithoutWaitingForTheNetwork() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            DspController controller = new DspController();
            DspSettingsCache cache = cache(controller, () -> { });
            AtomicBoolean metersStarted = new AtomicBoolean();
            try (DspConnectionManager manager = manager(controller, cache, server,
                    () -> metersStarted.set(true))) {
                CompletableFuture<String> connecting = connectAsync(manager);
                try (Socket peer = server.accept()) {
                    CompletableFuture<Void> disconnecting = CompletableFuture.runAsync(manager::disconnect);
                    try {
                        disconnecting.get(750, TimeUnit.MILLISECONDS);
                        assertThrows(ExecutionException.class, () -> connecting.get(750, TimeUnit.MILLISECONDS));
                        assertFalse(controller.isConnected());
                        assertEquals("DISCONNECTED", cache.status().phase());
                        assertFalse(metersStarted.get());
                    } finally {
                        peer.close();
                        disconnecting.get(2, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    @Test
    void shutdownCannotPublishLateCacheReadinessOrRestartMeters() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch recording = new CountDownLatch(1);
            CountDownLatch releaseRecording = new CountDownLatch(1);
            CountDownLatch recorded = new CountDownLatch(1);
            AtomicBoolean metersStarted = new AtomicBoolean();
            AtomicReference<Thread> worker = new AtomicReference<>();
            DspController controller = new DspController();
            controller.setPreferredLibraryType("fir408");
            DspSettingsCache cache = cache(controller, () -> {
                worker.set(Thread.currentThread());
                recording.countDown();
                awaitEvenWhenInterrupted(releaseRecording);
                recorded.countDown();
            });
            DspConnectionManager manager = manager(controller, cache, server,
                    () -> metersStarted.set(true));
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(10_000);
                    PushbackInputStream input = new PushbackInputStream(peer.getInputStream(), 1);
                    answerFirStartup(input, peer.getOutputStream());
                    answerCompleteSettingsDump(input, peer.getOutputStream());
                    awaitEvenWhenInterrupted(releaseRecording);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                CompletableFuture<String> connecting = connectAsync(manager);
                assertTrue(recording.await(10, TimeUnit.SECONDS));
                CompletableFuture.runAsync(manager::close).get(750, TimeUnit.MILLISECONDS);
                assertEquals("DISCONNECTED", cache.status().phase());
                releaseRecording.countDown();
                assertTrue(recorded.await(2, TimeUnit.SECONDS));
                worker.get().join(2_000);
                assertFalse(worker.get().isAlive(), "Cancelled lifecycle worker did not terminate");
                assertThrows(ExecutionException.class, () -> connecting.get(2, TimeUnit.SECONDS));
                assertEquals("DISCONNECTED", cache.status().phase());
                assertFalse(metersStarted.get());
                assertFalse(controller.isConnected());
                assertThrows(IllegalStateException.class, manager::connect);
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                releaseRecording.countDown();
                manager.close();
            }
        }
    }

    @Test
    void cacheInvalidationRejectsAnAlreadyReadDumpEvenWhileTransportRemainsConnected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch recording = new CountDownLatch(1);
            CountDownLatch releaseRecording = new CountDownLatch(1);
            CountDownLatch clientChecked = new CountDownLatch(1);
            DspController controller = new DspController();
            controller.setPreferredLibraryType("fir408");
            DspSettingsCache cache = cache(controller, () -> {
                recording.countDown();
                awaitEvenWhenInterrupted(releaseRecording);
            });
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(10_000);
                    PushbackInputStream input = new PushbackInputStream(peer.getInputStream(), 1);
                    answerFirStartup(input, peer.getOutputStream());
                    answerCompleteSettingsDump(input, peer.getOutputStream());
                    assertTrue(clientChecked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                controller.connect(new DspConnectionConfig(
                        "127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
                CompletableFuture<?> synchronizing = CompletableFuture.runAsync(() -> {
                    try {
                        cache.synchronize("test");
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
                assertTrue(recording.await(10, TimeUnit.SECONDS));
                cache.markDisconnected();
                releaseRecording.countDown();
                assertThrows(ExecutionException.class, () -> synchronizing.get(2, TimeUnit.SECONDS));
                assertEquals("DISCONNECTED", cache.status().phase());
                assertTrue(controller.isConnected());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                releaseRecording.countDown();
                clientChecked.countDown();
                controller.close();
            }
        }
    }

    private static DspSettingsCache cache(DspController controller, Runnable stateRecorder) {
        return new DspSettingsCache(controller,
                new DspTransactionCoordinator(controller, () -> { }), stateRecorder);
    }

    private static DspConnectionManager manager(DspController controller, DspSettingsCache cache,
                                               ServerSocket server, Runnable meterStarter) {
        DspConnectionConfig config = new DspConnectionConfig(
                "127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000);
        return new DspConnectionManager(controller, cache, () -> config, () -> "test", () -> false,
                meterStarter, ignored -> { });
    }

    private static CompletableFuture<String> connectAsync(DspConnectionManager manager) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return manager.connect();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    private static void awaitEvenWhenInterrupted(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void concurrentConnectCallersShareOneBootstrapAndOneCacheDump() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch allowStartup = new CountDownLatch(1);
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(10_000);
                    accepted.countDown();
                    assertTrue(allowStartup.await(2, TimeUnit.SECONDS));
                    PushbackInputStream input = new PushbackInputStream(peer.getInputStream(), 1);
                    answerFirStartup(input, peer.getOutputStream());
                    answerCompleteSettingsDump(input, peer.getOutputStream());
                    assertTrue(clientChecked.await(2, TimeUnit.SECONDS));
                    server.setSoTimeout(300);
                    try (Socket extra = server.accept()) {
                        throw new AssertionError("Duplicate connect opened another DSP session");
                    } catch (SocketTimeoutException expected) {
                        // Concurrent callers must retain the first session.
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspController controller = new DspController();
            controller.setPreferredLibraryType("fir408");
            DspSettingsCache cache = cache(controller, () -> { });
            AtomicInteger meterStarts = new AtomicInteger();
            try (DspConnectionManager manager = manager(controller, cache, server, meterStarts::incrementAndGet)) {
                List<CompletableFuture<String>> callers = new ArrayList<>();
                callers.add(manager.requestConnect());
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                for (int index = 0; index < 20; index++) {
                    callers.add(manager.requestConnect());
                }
                allowStartup.countDown();
                for (CompletableFuture<String> caller : callers) {
                    assertTrue(caller.get(10, TimeUnit.SECONDS).startsWith("Connected to "));
                }
                assertTrue(cache.status().ready());
                assertEquals(1L, cache.status().generation());
                assertEquals(1, meterStarts.get());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                allowStartup.countDown();
                clientChecked.countDown();
            }
        }
    }

    @Test
    void repeatedConnectKeepsTheExistingSessionAndCache() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    answerFirStartup(input, output);
                    answerCompleteSettingsDump(input, output);

                    assertTrue(clientChecked.await(2, TimeUnit.SECONDS));
                    server.setSoTimeout(300);
                    try (Socket ignored = server.accept()) {
                        throw new AssertionError("A repeated connect opened a second DSP session");
                    } catch (SocketTimeoutException expected) {
                        // The original DSP session is intentionally retained.
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspController controller = new DspController();
            DspConnectionManager manager = null;
            try {
                controller.setPreferredLibraryType("fir408");
                DspTransactionCoordinator transactions = new DspTransactionCoordinator(
                        controller,
                        () -> { }
                );
                DspSettingsCache settingsCache = new DspSettingsCache(
                        controller,
                        transactions,
                        () -> { }
                );
                DspConnectionConfig config = new DspConnectionConfig(
                        "127.0.0.1",
                        server.getLocalPort(),
                        1_000,
                        100,
                        60_000,
                        60_000
                );
                manager = new DspConnectionManager(
                        controller,
                        settingsCache,
                        () -> config,
                        () -> "test",
                        () -> false,
                        () -> { },
                        ignored -> { }
                );

                assertTrue(manager.connect().startsWith("Connected to "));
                long generation = settingsCache.status().generation();
                assertTrue(manager.connect().startsWith("Already connected to "));
                assertEquals(generation, settingsCache.status().generation());
                assertTrue(controller.isConnected());

                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                if (manager != null) {
                    manager.close();
                } else {
                    controller.close();
                }
            }
        }
    }

    private static void answerFirStartup(
            PushbackInputStream input,
            OutputStream output
    ) throws Exception {
        write(output, new byte[]{0x01, 0x00, 0x05, (byte) 0xB8,
                (byte) 192, (byte) 168, 1, (byte) 166});
        write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
        for (int expected : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
            assertEquals(expected, command(read(input)));
            write(output, new byte[]{0x01, 0x00, 0x01, (byte) expected});
        }

        for (int presetIndex = 0; presetIndex <= 0x12; presetIndex++) {
            DspFrameCodec.DecodedFrame request = read(input);
            assertEquals(0x29, command(request));
            assertEquals(presetIndex, request.payload()[4] & 0xFF);
            write(output, new byte[]{0x01, 0x00, 0x02, 0x29, (byte) presetIndex});
        }

        int[] firOffsets = {0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181};
        for (int expectedOffset : firOffsets) {
            DspFrameCodec.DecodedFrame request = read(input);
            assertEquals(0x56, command(request));
            int actualOffset = (request.payload()[5] & 0xFF)
                    | ((request.payload()[6] & 0xFF) << 8);
            assertEquals(expectedOffset, actualOffset);
            write(output, new byte[]{0x01, 0x00, 0x01, 0x56});
        }
    }

    private static void answerCompleteSettingsDump(
            PushbackInputStream input,
            OutputStream output
    ) throws Exception {
        for (int blockIndex = 0; blockIndex <= 0x1F; blockIndex++) {
            DspFrameCodec.DecodedFrame request = read(input);
            assertEquals(0x27, command(request));
            assertEquals(blockIndex, request.payload()[4] & 0xFF);
            byte[] response = new byte[55];
            response[0] = 0x01;
            response[2] = 0x34;
            response[3] = 0x24;
            response[4] = (byte) blockIndex;
            write(output, response);
        }
    }

    private static DspFrameCodec.DecodedFrame read(PushbackInputStream input) throws Exception {
        DspFrameCodec.DecodedFrame frame = DspFrameCodec.readFrame(input);
        if (frame == null) {
            throw new IllegalStateException("DSP test peer received no frame");
        }
        return frame;
    }

    private static int command(DspFrameCodec.DecodedFrame frame) {
        return frame.payload()[3] & 0xFF;
    }

    private static void write(OutputStream output, byte[] payload) throws Exception {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }
}

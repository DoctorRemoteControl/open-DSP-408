package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;

class DspControllerLifecycleTest {
    @Test
    void preferredLibraryChangeDoesNotReplaceMetadataDuringBootstrap() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch handshakeRead = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    write(socket.getOutputStream(), new byte[]{1, 0, 1, (byte) 0xBE});
                    DspFrameCodec.readFrame(new PushbackInputStream(socket.getInputStream(), 1));
                    handshakeRead.countDown();
                    while (socket.getInputStream().read() >= 0) { }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspController controller = new DspController();
            CompletableFuture<Void> connecting = CompletableFuture.runAsync(() ->
                    assertThrows(IOException.class, () -> controller.connect(config(server), ignored -> { })));
            try {
                assertTrue(handshakeRead.await(1, TimeUnit.SECONDS));
                controller.setPreferredLibraryType("fir408");
                assertEquals("dsp408_field_library", controller.libraryType());
            } finally {
                controller.close();
            }
            connecting.get(1, TimeUnit.SECONDS);
            peer.get(1, TimeUnit.SECONDS);
            controller.setPreferredLibraryType("fir408");
            assertEquals("dsp408_fir_field_library", controller.libraryType());
        }
    }

    @Test
    void lateCanceledBootstrapFailureCannotCloseItsReplacement() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch oldBootstrapPaused = new CountDownLatch(1);
            CountDownLatch releaseOldBootstrap = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try {
                    try (Socket oldSocket = server.accept()) {
                        while (oldSocket.getInputStream().read() >= 0) { }
                    }
                    try (Socket socket = server.accept()) {
                        PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                        OutputStream output = socket.getOutputStream();
                        write(output, new byte[]{1, 0, 1, (byte) 0xBE});
                        for (int expected : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
                            assertEquals(expected, DspFrameCodec.readFrame(input).payload()[3] & 0xFF);
                            write(output, new byte[]{1, 0, 1, (byte) expected});
                        }
                        for (int preset = 0; preset <= 0x13; preset++) {
                            byte[] request = DspFrameCodec.readFrame(input).payload();
                            assertEquals(0x29, request[3] & 0xFF);
                            assertEquals(preset, request[4] & 0xFF);
                            write(output, new byte[]{1, 0, 2, 0x29, (byte) preset});
                        }
                        assertTrue(checked.await(2, TimeUnit.SECONDS));
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspController controller = new DspController();
            CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                    assertThrows(Exception.class, () -> controller.connect(config(server), message -> {
                        if (message.startsWith("No DSP session-ready marker")) {
                            oldBootstrapPaused.countDown();
                            try {
                                assertTrue(releaseOldBootstrap.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    })));
            try {
                assertTrue(oldBootstrapPaused.await(1, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(300), controller::close);
                controller.connect(config(server), ignored -> { });
                assertTrue(controller.isConnected());
                releaseOldBootstrap.countDown();
                first.get(1, TimeUnit.SECONDS);
                assertTrue(controller.isConnected(), "Late cleanup belongs to the canceled client only");
            } finally {
                releaseOldBootstrap.countDown();
                checked.countDown();
                controller.close();
            }
            peer.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void observedStartupInProgressIsUnavailableAndCloseCancelsIt() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch startupRead = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    write(output, new byte[]{1, 0, 1, (byte) 0xBE});
                    for (int expected : new int[]{0x10, 0x13, 0x2C}) {
                        assertEquals(expected, DspFrameCodec.readFrame(input).payload()[3] & 0xFF);
                        write(output, new byte[]{1, 0, 1, (byte) expected});
                    }
                    assertEquals(0x22, DspFrameCodec.readFrame(input).payload()[3] & 0xFF);
                    startupRead.countDown();
                    while (input.read() >= 0) { }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspController controller = new DspController();
            CompletableFuture<Void> connecting = CompletableFuture.runAsync(() ->
                    assertThrows(IOException.class, () -> controller.connect(config(server), ignored -> { })));
            try {
                assertTrue(startupRead.await(1, TimeUnit.SECONDS));
                assertFalse(controller.isConnected(), "Controller is usable only after observed startup");
                assertThrows(IllegalStateException.class, controller::requestSystemInfo);
            } finally {
                assertTimeoutPreemptively(Duration.ofMillis(300), controller::close);
            }
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> connecting.get(1, TimeUnit.SECONDS));
            assertFalse(controller.isConnected());
            peer.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptedWorkerCannotStartAConnectionAfterCancellation() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            DspController controller = new DspController();
            CompletableFuture<Void> canceled = CompletableFuture.runAsync(() -> {
                Thread.currentThread().interrupt();
                try {
                    assertThrows(IOException.class, () -> controller.connect(config(server), ignored -> { }));
                } finally {
                    Thread.interrupted();
                }
            });
            canceled.get(1, TimeUnit.SECONDS);
            server.setSoTimeout(100);
            assertThrows(java.net.SocketTimeoutException.class, server::accept);
            assertFalse(controller.isConnected());
            controller.close();
        }
    }

    private static DspConnectionConfig config(ServerSocket server) {
        return new DspConnectionConfig("127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000);
    }

    private static void write(OutputStream output, byte[] payload) throws IOException {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }
}

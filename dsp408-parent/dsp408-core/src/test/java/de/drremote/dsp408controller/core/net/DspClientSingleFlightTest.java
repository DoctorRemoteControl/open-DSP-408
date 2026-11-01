package de.drremote.dsp408controller.core.net;

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

class DspClientSingleFlightTest {
    @Test
    void missingFirSessionReadyMarkerFallsBackToWarmReconnectHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    answerSameCommand(input, output, 0x2C);
                    assertTrue(clientChecked.await(1, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );

            try {
                client.connect(true);
                assertTrue(client.isConnected());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                client.close();
            }
        }
    }

    @Test
    void unansweredHandshakeClosesTheConnectionInsteadOfContinuing() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    write(socket.getOutputStream(), new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
                    assertEquals(0x10, command(read(input)));
                    while (input.read() >= 0) {
                        // Wait until the client closes the desynchronized stream.
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );

            assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                    assertThrows(IOException.class, client::connect)
            );
            assertFalse(client.isConnected());
            fakeDsp.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void slowRequiredReadCompletesWithoutRetry() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();

                    write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    assertEquals(0x2C, command(read(input)));
                    Thread.sleep(750L);
                    write(output, new byte[]{0x01, 0x00, 0x01, 0x2C});
                    assertTrue(clientChecked.await(1, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );
            try {
                client.connect();
                assertTrue(client.isConnected());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                client.close();
            }
        }
    }

    @Test
    void unansweredRequiredReadClosesTheSessionAfterItsDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();

                    write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    assertEquals(0x2C, command(read(input)));
                    assertEquals(-1, input.read(), "Missing read must close without retransmission");
                    assertTrue(clientChecked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );
            try {
                assertThrows(IOException.class, client::connect);
                assertFalse(client.isConnected());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                client.close();
            }
        }
    }

    @Test
    void unacknowledgedMuteClosesTheSessionWithoutRetry() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();

                    write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    answerSameCommand(input, output, 0x2C);
                    assertEquals(0x35, command(read(input)));
                    assertEquals(-1, input.read(), "Timed-out write must close without retransmission");
                    assertTrue(clientChecked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );
            try {
                client.connect();
                IOException failure = assertThrows(IOException.class, () -> client.setMute(0, true));
                assertFalse(client.isConnected());
                assertEquals(0L, client.trafficSnapshot().writeRetries());
                assertEquals(1L, client.trafficSnapshot().unacknowledgedWrites());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                client.close();
            }
        }
    }

    @Test
    void stoppingPollingRemovesSampleQueuedBehindForegroundRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch readSent = new CountDownLatch(1);
            CountDownLatch pollingStopped = new CountDownLatch(1);
            CountDownLatch clientChecked = new CountDownLatch(1);
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    write(output, new byte[]{1, 0, 1, (byte) 0xBE});
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    answerSameCommand(input, output, 0x2C);
                    assertEquals(0x27, command(read(input)));
                    readSent.countDown();
                    assertTrue(pollingStopped.await(2, TimeUnit.SECONDS));
                    write(output, new byte[]{1, 0, 2, 0x24, 3});
                    assertEquals(0x35, command(read(input)), "A paused meter must not precede the write");
                    write(output, new byte[]{1, 0, 1, 1});
                    assertTrue(clientChecked.await(1, TimeUnit.SECONDS));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            DspClient client = new DspClient(new DspConnectionConfig("127.0.0.1",
                    server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
            try {
                client.connect();
                CompletableFuture<Void> read = CompletableFuture.runAsync(() -> {
                    try { client.readParameterBlock(3); }
                    catch (IOException failure) { throw new RuntimeException(failure); }
                });
                assertTrue(readSent.await(1, TimeUnit.SECONDS));
                client.startMeterPolling(50);
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    while (client.trafficSnapshot().pendingCommands() == 0) Thread.onSpinWait();
                });
                client.stopMeterPolling();
                assertEquals(0, client.trafficSnapshot().pendingCommands());
                pollingStopped.countDown();
                read.get(2, TimeUnit.SECONDS);
                client.setMute(1, true);
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                pollingStopped.countDown();
                clientChecked.countDown();
                client.close();
            }
        }
    }

    @Test
    void missingMeterReplyDoesNotBlockControlOrCloseConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch meterReceived = new CountDownLatch(1);
            CountDownLatch clientChecked = new CountDownLatch(1);

            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();

                    write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
                    answerSameCommand(input, output, 0x10);
                    answerSameCommand(input, output, 0x13);
                    answerSameCommand(input, output, 0x2C);

                    assertEquals(0x40, command(read(input)));
                    long meterReceivedNanos = System.nanoTime();
                    meterReceived.countDown();

                    // A missing best-effort meter reply must expire quickly so
                    // the queued safety command can still reach the device.
                    assertEquals(0x35, command(read(input)));
                    long controlDelayMs = TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - meterReceivedNanos
                    );
                    assertTrue(controlDelayMs >= 300L,
                            "Control followed an unanswered meter request after only "
                                    + controlDelayMs + " ms");
                    // A late meter frame is unrelated to the mute transaction
                    // and must not be mistaken for its ACK.
                    write(output, new byte[]{0x01, 0x00, 0x01, 0x40});
                    write(output, new byte[]{0x01, 0x00, 0x01, 0x01});
                    assertTrue(clientChecked.await(1, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspClient client = new DspClient(
                    new DspConnectionConfig(
                            "127.0.0.1",
                            server.getLocalPort(),
                            1_000,
                            100,
                            60_000,
                            60_000
                    ),
                    ignored -> { }
            );
            try {
                client.connect();

                CompletableFuture<Void> meter = runIo(client::requestRuntimeMeters);
                assertTrue(meterReceived.await(1, TimeUnit.SECONDS));
                CompletableFuture<Void> mute = runIo(() -> client.setMute(3, true));

                meter.get(2, TimeUnit.SECONDS);
                mute.get(2, TimeUnit.SECONDS);
                assertTrue(client.isConnected());
                clientChecked.countDown();
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                clientChecked.countDown();
                client.close();
            }
        }
    }

    private static void answerSameCommand(
            PushbackInputStream input,
            OutputStream output,
            int expectedCommand
    ) throws Exception {
        assertEquals(expectedCommand, command(read(input)));
        write(output, new byte[]{0x01, 0x00, 0x01, (byte) expectedCommand});
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

    private static CompletableFuture<Void> runIo(IoRunnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws Exception;
    }
}

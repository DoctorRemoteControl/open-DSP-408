package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DspClientLifecycleTest {
    @Test
    void canceledOwnerCannotStartAConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            DspClient client = new DspClient(new DspConnectionConfig(
                    "127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
            try {
                assertThrows(IOException.class, () -> client.connect(false, () -> false));
                server.setSoTimeout(100);
                assertThrows(java.net.SocketTimeoutException.class, server::accept);
                assertFalse(client.isConnected());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void handshakeInProgressIsNotConnectedAndCloseCancelsIt() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch handshakeRead = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(DspFrameCodec.encode(new byte[]{1, 0, 1, (byte) 0xBE}));
                    socket.getOutputStream().flush();
                    DspFrameCodec.readFrame(new PushbackInputStream(socket.getInputStream(), 1));
                    handshakeRead.countDown();
                    while (socket.getInputStream().read() >= 0) { }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspClient client = new DspClient(new DspConnectionConfig(
                    "127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
            CompletableFuture<Void> connecting = CompletableFuture.runAsync(() ->
                    assertThrows(IOException.class, client::connect));
            try {
                assertTrue(handshakeRead.await(1, TimeUnit.SECONDS));
                assertFalse(client.isConnected(), "A TCP socket alone does not make the handshake ready");
            } finally {
                assertTimeoutPreemptively(Duration.ofMillis(300), client::close);
            }
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> connecting.get(1, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            peer.get(1, TimeUnit.SECONDS);
        }
    }
}

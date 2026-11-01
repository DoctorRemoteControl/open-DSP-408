package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DspSocketSessionTest {
    @Test
    void fragmentedFrameSurvivesIdleTimeoutBetweenChunks() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch checked = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    byte[] frame = DspFrameCodec.encode(new byte[]{1, 0, 4, 0x14, 0x10, 0x03, 0x55});
                    socket.getOutputStream().write(frame, 0, 6);
                    socket.getOutputStream().flush();
                    Thread.sleep(250L); // Longer than the session's 100 ms idle timeout.
                    socket.getOutputStream().write(frame, 6, frame.length-6);
                    socket.getOutputStream().flush();
                    assertTrue(checked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            DspSocketSession session = session(server);
            LinkedBlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
            session.setResponseListener(responses::add);
            try {
                session.open();
                session.startReader();
                byte[] reply = responses.poll(1, TimeUnit.SECONDS);
                assertNotNull(reply, "Idle socket timeouts must not discard an incomplete valid frame");
                org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 0, 4, 0x14, 0x10, 0x03, 0x55}, reply);
            } finally {
                checked.countDown();
                session.finishClose(session.beginClose());
            }
            peer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retiredWriterCannotSendToAReplacementSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch writerPaused = new CountDownLatch(1);
            CountDownLatch resumeWriter = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket oldSocket = server.accept(); Socket replacement = server.accept()) {
                    replacement.setSoTimeout(2_000);
                    replacement.getOutputStream().write(DspFrameCodec.encode(new byte[]{1, 0, 1, (byte) 0xBE}));
                    replacement.getOutputStream().flush();
                    var request = DspFrameCodec.readFrame(new java.io.PushbackInputStream(replacement.getInputStream(), 1));
                    assertEquals(0x35, request.payload()[3] & 0xff,
                            "The replacement socket must receive only its own writer's command");
                    assertTrue(checked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            DspSocketSession session = session(server);
            try {
                session.open();
                session.startReader();
                var retiredWriter = session.frameWriter();
                CompletableFuture<Void> retired = CompletableFuture.runAsync(() -> {
                    writerPaused.countDown();
                    try { assertTrue(resumeWriter.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                    assertThrows(IOException.class, () -> retiredWriter.write(new byte[]{0, 1, 1, 0x34}));
                });
                assertTrue(writerPaused.await(1, TimeUnit.SECONDS));
                session.finishClose(session.beginClose());
                session.open();
                session.startReader();
                assertTrue(session.awaitSessionReadyMarker());
                resumeWriter.countDown();
                retired.get(2, TimeUnit.SECONDS);
                session.frameWriter().write(new byte[]{0, 1, 1, 0x35});
                checked.countDown();
                peer.get(2, TimeUnit.SECONDS);
            } finally {
                resumeWriter.countDown();
                checked.countDown();
                session.finishClose(session.beginClose());
            }
        }
    }

    @Test
    void closeCancelsSocketConnectWithoutWaitingForItsTimeout() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch socketClosed = new CountDownLatch(1);
        Socket blockedSocket = new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                connectEntered.countDown();
                try {
                    socketClosed.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Canceled connect");
            }

            @Override
            public void close() {
                socketClosed.countDown();
            }
        };
        DspSocketSession session = new DspSocketSession(
                new DspConnectionConfig("127.0.0.1", 1, 10_000, 100, 60_000, 60_000),
                ignored -> { }, () -> blockedSocket);
        CompletableFuture<Void> connecting = CompletableFuture.runAsync(() ->
                assertThrows(IOException.class, session::open));
        assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                    session.finishClose(session.beginClose()));
            connecting.get(1, TimeUnit.SECONDS);
            assertFalse(session.isConnected());
        } finally {
            socketClosed.countDown();
        }
    }

    @Test
    void socketIsClosedBeforeClosingABlockedInputStream() throws Exception {
        CountDownLatch socketClosed = new CountDownLatch(1);
        Socket socket = new Socket() {
            @Override public void connect(SocketAddress endpoint, int timeout) { }
            @Override public void setTcpNoDelay(boolean enabled) { }
            @Override public void setKeepAlive(boolean enabled) { }
            @Override public void setSoTimeout(int timeout) { }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() { return -1; }
                    @Override public void close() throws IOException {
                        try {
                            socketClosed.await();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException(error);
                        }
                    }
                };
            }
            @Override public void close() { socketClosed.countDown(); }
        };
        DspSocketSession session = new DspSocketSession(
                new DspConnectionConfig("127.0.0.1", 1, 1_000, 100, 60_000, 60_000),
                ignored -> { }, () -> socket);
        session.open();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                    session.finishClose(session.beginClose()));
        } finally {
            socketClosed.countDown();
        }
    }

    @Test
    void checksumInvalidFramesHaveNoListenerOrReadyMarkerEffects() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch checked = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    byte[] invalid = DspFrameCodec.encode(new byte[]{1, 0, 1, (byte) 0xBE});
                    invalid[invalid.length - 1] ^= 1;
                    socket.getOutputStream().write(invalid);
                    socket.getOutputStream().write(DspFrameCodec.encode(new byte[]{1, 0, 1, 0x14}));
                    socket.getOutputStream().flush();
                    assertTrue(checked.await(2, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspSocketSession session = session(server);
            List<Integer> payloads = new CopyOnWriteArrayList<>();
            LinkedBlockingQueue<Integer> responses = new LinkedBlockingQueue<>();
            session.setPayloadListener(payload -> payloads.add(payload[3] & 0xFF));
            session.setResponseListener(payload -> responses.add(payload[3] & 0xFF));
            try {
                session.open();
                session.startReader();
                assertEquals(0x14, responses.poll(1, TimeUnit.SECONDS));
                assertFalse(session.awaitSessionReadyMarker());
                assertEquals(List.of(0x14), payloads);
                assertTrue(responses.isEmpty());
            } finally {
                checked.countDown();
                session.finishClose(session.beginClose());
            }
            peer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void detachedReaderCannotNotifyOrDisconnectAReplacementSession() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch oldListenerEntered = new CountDownLatch(1);
            CountDownLatch releaseOldListener = new CountDownLatch(1);
            CountDownLatch newChecked = new CountDownLatch(1);
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket oldSocket = server.accept()) {
                    oldSocket.getOutputStream().write(DspFrameCodec.encode(new byte[]{1, 0, 1, 0x14}));
                    oldSocket.getOutputStream().flush();
                    try (Socket newSocket = server.accept()) {
                        newSocket.getOutputStream().write(DspFrameCodec.encode(new byte[]{1, 0, 1, (byte) 0xBE}));
                        newSocket.getOutputStream().flush();
                        assertTrue(newChecked.await(2, TimeUnit.SECONDS));
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            DspSocketSession session = session(server);
            LinkedBlockingQueue<Integer> responses = new LinkedBlockingQueue<>();
            AtomicInteger disconnects = new AtomicInteger();
            session.setPayloadListener(payload -> {
                if ((payload[3] & 0xFF) == 0x14) {
                    oldListenerEntered.countDown();
                    try {
                        assertTrue(releaseOldListener.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        throw new RuntimeException(error);
                    }
                }
            });
            session.setResponseListener(payload -> responses.add(payload[3] & 0xFF));
            session.setDisconnectHandler(ignored -> disconnects.incrementAndGet());
            try {
                session.open();
                session.startReader();
                assertTrue(oldListenerEntered.await(1, TimeUnit.SECONDS));
                session.finishClose(session.beginClose());
                session.open();
                session.startReader();
                assertEquals(0xBE, responses.poll(1, TimeUnit.SECONDS));
                releaseOldListener.countDown();
                // A stale reader response is an observable indication that it crossed ownership.
                assertEquals(null, responses.poll(200, TimeUnit.MILLISECONDS));
                assertEquals(0, disconnects.get());
                assertTrue(session.isConnected());
            } finally {
                releaseOldListener.countDown();
                newChecked.countDown();
                session.finishClose(session.beginClose());
            }
            peer.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeWakesAReaderWaitingForTheSessionReadyMarker() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            DspSocketSession session = session(server);
            session.open();
            try (Socket peer = server.accept()) {
                session.startReader();
                CountDownLatch waiting = new CountDownLatch(1);
                CompletableFuture<Void> awaitReady = CompletableFuture.runAsync(() -> {
                    waiting.countDown();
                    assertThrows(IOException.class, session::awaitSessionReadyMarker);
                });
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                session.finishClose(session.beginClose());
                assertTimeoutPreemptively(Duration.ofMillis(250), () -> awaitReady.get(1, TimeUnit.SECONDS));
            }
        }
    }

    private static DspSocketSession session(ServerSocket server) {
        return new DspSocketSession(new DspConnectionConfig(
                "127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
    }

	@Test
	void decodesBeforeReleasingResponseAndKeepsListenersAcrossReconnect() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
				for (int connection = 0; connection < 2; connection++) {
					try (Socket socket = server.accept()) {
						socket.getOutputStream().write(DspFrameCodec.encode(
								new byte[]{0x01, 0x00, 0x01, (byte) 0xBE}
						));
						socket.getOutputStream().flush();
						while (socket.getInputStream().read() >= 0) {
							// Wait for the session under test to close this connection.
						}
					} catch (Exception error) {
						throw new RuntimeException(error);
					}
				}
			});

			DspSocketSession session = new DspSocketSession(
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
			List<String> events = new CopyOnWriteArrayList<>();
			LinkedBlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
			session.setPayloadListener(payload -> events.add("payload"));
			session.setResponseListener(payload -> {
				events.add("response");
				responses.add(payload);
			});

			for (int connection = 0; connection < 2; connection++) {
				try {
					session.open();
					session.startReader();
					assertTrue(session.awaitSessionReadyMarker());
					assertNotNull(responses.poll(1L, TimeUnit.SECONDS));
				} finally {
					session.finishClose(session.beginClose());
				}
			}

			fakeDsp.get(2L, TimeUnit.SECONDS);
			assertEquals(List.of("payload", "response", "payload", "response"), events);
		}
	}
}

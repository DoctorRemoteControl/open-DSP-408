package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.EOFException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import de.drremote.dsp408.api.DspControlConflictException;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteStatus;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspControlStateTest {
    @Test
    void cachedControlChangesAdvanceButMeterUpdatesKeepTheVersion() throws Exception {
        var runtime = new DspRuntimeComponent(false);
        var before = runtime.getControlState();
        assertEquals(before, runtime.getControlState());
        runtime.withController(controller -> {
            controller.state().markMeterFromDevice(DspChannel.OUT_8, 1, 2, 3, "meter");
            return null;
        });
        assertEquals(before, runtime.getControlState());
        runtime.withController(controller -> {
            controller.state().markMuted(DspChannel.OUT_8, true, "legacy-control");
            return null;
        });
        var after = runtime.getControlState();
        assertEquals(before.revision() + 1, after.revision());
        assertEquals(after, runtime.getControlState());
        assertNotEquals(before.etag(), after.etag());
        assertEquals("\"" + after.instanceId() + "." + after.revision() + "\"", after.etag());
        assertNotEquals(after.instanceId(), new DspRuntimeComponent(false).getControlState().instanceId());
    }

    @Test
    void sameTagCannotOverwriteAfterAnAcknowledgedWriteAndReadsStayResponsive() throws Exception {
        var active = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var peer = new Peer((socket, input, output) -> {
            assertEquals(0x34, command(read(input)));
            active.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            send(output, new byte[]{1, 0, 1, 1});
            socket.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> read(input),
                    "A stale competing writer must not transmit");
        }); var writers = Executors.newFixedThreadPool(3)) {
            try {
                var runtime = peer.runtime;
                var before = runtime.getControlState();
                var request = new ParameterWriteRequestDto("out8", "gain", -14.0, false);
                var first = writers.submit(() -> runtime.writeParameterConditional(request, before.etag(), false));
                assertTrue(active.await(2, TimeUnit.SECONDS));
                var snapshot = writers.submit(runtime::getControlState);
                assertEquals(before, snapshot.get(1, TimeUnit.SECONDS),
                        "RAM snapshots must not wait for the active DSP transaction");
                var busy = runtime.writeParameterConditional(request, before.etag(), true);
                assertEquals("dsp_busy", busy.errorCode());
                assertEquals(Boolean.FALSE, busy.transmitted());
                var second = writers.submit(() -> runtime.writeParameterConditional(request, before.etag(), false));
                release.countDown();
                assertEquals(ParameterWriteStatus.ACKNOWLEDGED, first.get(2, TimeUnit.SECONDS).status());
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> second.get(2, TimeUnit.SECONDS));
                var conflict = assertInstanceOf(DspControlConflictException.class, failure.getCause());
                assertTrue(conflict.currentState().revision() > before.revision());
                assertEquals(conflict.currentState(), runtime.getControlState());
                peer.awaitScript();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void staleTagDetectsLegacyCachedChangeBeforeAnyTransmission() throws Exception {
        try (var peer = new Peer((socket, input, output) -> {
            socket.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> read(input));
        })) {
            var runtime = peer.runtime;
            var before = runtime.getControlState();
            runtime.withController(controller -> {
                controller.state().markMuted(DspChannel.OUT_8, true, "legacy");
                return null;
            });
            var conflict = assertThrows(DspControlConflictException.class, () ->
                    runtime.writeParameterConditional(new ParameterWriteRequestDto("out8", "gain", -14.0, false),
                            before.etag(), true));
            assertTrue(conflict.currentState().channels().stream()
                    .filter(channel -> channel.id().equals("out8")).findFirst().orElseThrow().muted());
            peer.awaitScript();
        }
    }

    @Test
    void repeatingSameValueStillConsumesTheConditionalRevision() throws Exception {
        try (var peer = new Peer((socket, input, output) -> {
            for (int index = 0; index < 2; index++) {
                assertEquals(0x34, command(read(input)));
                send(output, new byte[]{1, 0, 1, 1});
            }
        })) {
            var runtime = peer.runtime;
            var request = new ParameterWriteRequestDto("out8", "gain", -14.0, false);
            var initial = runtime.getControlState();
            runtime.writeParameterConditional(request, initial.etag(), false);
            var first = runtime.getControlState();
            runtime.writeParameterConditional(request, first.etag(), false);
            var second = runtime.getControlState();
            assertTrue(second.revision() > first.revision(),
                    "An ACK must consume its condition even when cached values already match");
            peer.awaitScript();
        }
    }

    @Test
    void transactionPublicationDoesNotInvertTheAutoLimitMonitorOrder() throws Exception {
        try (var peer = new Peer((socket, input, output) -> {
            assertEquals(0x34, command(read(input)));
            send(output, new byte[]{1, 0, 1, 1});
        }); var writers = Executors.newSingleThreadExecutor()) {
            var runtime = peer.runtime;
            var field = DspRuntimeComponent.class.getDeclaredField("autoLimit");
            field.setAccessible(true);
            Object autoLimit = field.get(runtime);
            var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var before = runtime.getControlState();
            var request = new ParameterWriteRequestDto("out8", "gain", -14.0, false);
            java.util.concurrent.Future<?> first;
            synchronized (autoLimit) {
                first = writers.submit(() -> {
                    thread.set(Thread.currentThread());
                    return runtime.writeParameterConditional(request, before.etag(), false);
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!waitingForAutoLimit(thread.get()) && System.nanoTime() < deadline) Thread.sleep(1);
                assertTrue(waitingForAutoLimit(thread.get()), "The writer must reach Auto-Limit undo invalidation");
                assertThrows(DspControlConflictException.class, () ->
                        runtime.writeParameterConditional(request, before.etag(), true),
                        "The transaction must be released before acquiring the Auto-Limit monitor");
            }
            first.get(2, TimeUnit.SECONDS);
            peer.awaitScript();
        }
    }

    @Test
    void staleConditionReturnsCurrentStateEvenIfTheDeviceIsOffline() throws Exception {
        var runtime = new DspRuntimeComponent(false);
        var before = runtime.getControlState();
        runtime.withController(controller -> {
            controller.state().markMuted(DspChannel.OUT_8, true, "legacy");
            return null;
        });
        var conflict = assertThrows(DspControlConflictException.class, () ->
                runtime.writeParameterConditional(new ParameterWriteRequestDto("out8", "gain", -14.0, false),
                        before.etag(), true));
        assertFalse(conflict.currentState().connected());
        assertTrue(conflict.currentState().revision() > before.revision());
        var current = runtime.getControlState();
        var rejected = runtime.writeParameterConditional(new ParameterWriteRequestDto("out8", "gain", -14.0, false),
                current.etag(), true);
        assertEquals("dsp_not_connected", rejected.errorCode());
        assertEquals(Boolean.FALSE, rejected.transmitted());
        assertEquals(current, runtime.getControlState());
    }

    private static boolean waitingForAutoLimit(Thread thread) {
        return thread != null && thread.getState() == Thread.State.BLOCKED
                && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(DspAutoLimitManager.class.getName())
                                && frame.getMethodName().equals("invalidateUndo"));
    }

    private static int command(DspFrameCodec.DecodedFrame frame) {
        return frame.payload()[3] & 255;
    }

    private static DspFrameCodec.DecodedFrame read(PushbackInputStream input) throws Exception {
        var frame = DspFrameCodec.readFrame(input);
        if (frame == null) throw new EOFException("Fake DSP received no frame");
        return frame;
    }

    private static void send(OutputStream output, byte[] payload) throws Exception {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }

    @FunctionalInterface
    private interface Script {
        void run(java.net.Socket socket, PushbackInputStream input, OutputStream output) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        final DspRuntimeComponent runtime = new DspRuntimeComponent(false);
        final ServerSocket server = new ServerSocket(0);
        final CountDownLatch close = new CountDownLatch(1);
        final CompletableFuture<Void> completed = new CompletableFuture<>();
        final CompletableFuture<Void> peer;

        Peer(Script script) throws Exception {
            peer = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new PushbackInputStream(socket.getInputStream(), 1);
                    var output = socket.getOutputStream();
                    send(output, new byte[]{1, 0, 5, (byte) 0xB8, (byte) 192, (byte) 168, 1, (byte) 166});
                    send(output, new byte[]{1, 0, 1, (byte) 0xBE});
                    for (int expected : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
                        assertEquals(expected, command(read(input)));
                        if (expected == 0x13) {
                            byte[] ascii = "FIR408 V0113P".getBytes(StandardCharsets.US_ASCII);
                            byte[] version = new byte[4 + ascii.length];
                            version[0] = 1;
                            version[2] = (byte) (ascii.length + 1);
                            version[3] = 0x13;
                            System.arraycopy(ascii, 0, version, 4, ascii.length);
                            send(output, version);
                        } else send(output, new byte[]{1, 0, 1, (byte) expected});
                    }
                    for (int index = 0; index <= 0x12; index++) {
                        var frame = read(input);
                        assertEquals(0x29, command(frame));
                        assertEquals(index, frame.payload()[4] & 255);
                        send(output, new byte[]{1, 0, 2, 0x29, (byte) index});
                    }
                    for (int offset : new int[]{0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181}) {
                        var frame = read(input);
                        assertEquals(0x56, command(frame));
                        assertEquals(offset, (frame.payload()[5] & 255) | ((frame.payload()[6] & 255) << 8));
                        send(output, new byte[]{1, 0, 1, 0x56});
                    }
                    script.run(socket, input, output);
                    completed.complete(null);
                    assertTrue(close.await(8, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    completed.completeExceptionally(failure);
                    throw new RuntimeException(failure);
                }
            });
            runtime.withController(controller -> {
                controller.setPreferredLibraryType("fir408");
                controller.connect(new DspConnectionConfig("127.0.0.1", server.getLocalPort(),
                        1_000, 100, 60_000, 60_000), ignored -> { });
                return null;
            });
        }

        void awaitScript() throws Exception {
            completed.get(3, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            close.countDown();
            runtime.withController(controller -> { controller.close(); return null; });
            server.close();
            peer.get(3, TimeUnit.SECONDS);
        }
    }
}

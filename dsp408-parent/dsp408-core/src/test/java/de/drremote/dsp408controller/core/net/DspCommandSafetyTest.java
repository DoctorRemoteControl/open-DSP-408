package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspCommandSafetyTest {
    @Test
    void lateAckCannotConfirmTheNextWriteAfterAnUnansweredWrite() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger writes = new AtomicInteger();
            CountDownLatch firstSent = new CountDownLatch(1);
            harness.writer = payload -> {
                if ((payload[4] & 0xff) == 1) {
                    int attempt = writes.incrementAndGet();
                    harness.timer.schedule(() -> harness.dispatcher.signalResponse(DspProtocol.ACK),
                            attempt == 1 ? 750 : 200, TimeUnit.MILLISECONDS);
                    firstSent.countDown();
                }
            };
            CompletableFuture<Boolean> first = harness.send(DspProtocol.buildMute(1, true), true);
            assertTrue(firstSent.await(1, TimeUnit.SECONDS));
            CompletableFuture<Boolean> next = harness.send(DspProtocol.buildMute(2, true), true);
            assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS),
                    "A timed-out generic ACK must invalidate the connection without retry");
            assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
            assertEquals(1, writes.get(), "No duplicate write may leave a second unidentifiable ACK");
            assertFalse(harness.connected.get());
        }
    }

    @Test
    void unansweredKeepAliveInvalidatesSessionBeforeALaterWrite() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch keepAliveSent = new CountDownLatch(1);
            harness.writer = payload -> keepAliveSent.countDown();
            CompletableFuture<Boolean> keepAlive = harness.send(DspProtocol.keepAlive(), false);
            assertTrue(keepAliveSent.await(1, TimeUnit.SECONDS));
            CompletableFuture<Boolean> write = harness.send(DspProtocol.buildMute(1, true), true);
            assertThrows(ExecutionException.class, () -> keepAlive.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> write.get(2, TimeUnit.SECONDS));
            assertFalse(harness.connected.get());
        }
    }

    @Test
    void interruptedQueuedMeterIsNotTransmittedAfterForegroundRead() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch readSent = new CountDownLatch(1);
            AtomicInteger meters = new AtomicInteger();
            harness.writer = payload -> {
                if ((payload[3] & 0xff) == 0x27) readSent.countDown();
                if ((payload[3] & 0xff) == 0x40) {
                    meters.incrementAndGet();
                    harness.dispatcher.signalResponse(new byte[]{1, 0, 1, 0x40});
                }
            };
            CompletableFuture<Boolean> read = harness.send(DspProtocol.readParameterBlock(3), false);
            assertTrue(readSent.await(1, TimeUnit.SECONDS));
            Thread meter = new Thread(() -> {
                try { harness.dispatcher.send(DspProtocol.runtimeMeterRequest()); }
                catch (IOException expected) { }
            });
            meter.start();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (harness.dispatcher.trafficSnapshot(false, null).pendingCommands() != 1) {
                    Thread.onSpinWait();
                }
            });
            meter.interrupt();
            meter.join(1_000);
            assertFalse(meter.isAlive());
            assertEquals(0, harness.dispatcher.trafficSnapshot(false, null).pendingCommands(),
                    "The interrupted pending sample must already be removed");
            harness.dispatcher.signalResponse(new byte[]{1, 0, 2, 0x24, 3});
            assertTrue(read.get(1, TimeUnit.SECONDS));
            // A barrier command demonstrates the worker has drained anything left behind.
            harness.writer = payload -> {
                if ((payload[3] & 0xff) == 0x40) meters.incrementAndGet();
                harness.dispatcher.signalResponse(DspProtocol.ACK);
            };
            assertTrue(harness.send(DspProtocol.buildMute(1, true), true).get(2, TimeUnit.SECONDS));
            assertEquals(0, meters.get());
        }
    }

    @Test
    void slowReadGetsOneResponseWindowWithoutDuplicateRequests() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger reads = new AtomicInteger();
            harness.writer = payload -> {
                reads.incrementAndGet();
                harness.timer.schedule(() -> harness.dispatcher.signalResponse(
                        new byte[]{1, 0, 2, 0x24, 3}), 750, TimeUnit.MILLISECONDS);
            };
            assertTrue(harness.send(DspProtocol.readParameterBlock(3), false).get(2, TimeUnit.SECONDS));
            assertEquals(1, reads.get(), "Repeated reads can leave stale block replies after a later write");
        }
    }

    @Test
    void presetStoreAcceptsAckAfterTheDocumentedFlashSilence() throws Exception {
        try (Harness harness = new Harness()) {
            harness.writer = payload -> harness.timer.schedule(
                    () -> harness.dispatcher.signalResponse(DspProtocol.ACK),
                    8_100, TimeUnit.MILLISECONDS);
            assertTrue(harness.send(DspProtocol.storePreset(1), false).get(12, TimeUnit.SECONDS));
            assertTrue(harness.connected.get());
        }
    }

    @FunctionalInterface
    private interface Writer { void write(byte[] payload) throws IOException; }

    private static final class Harness implements AutoCloseable {
        final AtomicBoolean connected = new AtomicBoolean(true);
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        volatile Writer writer = payload -> { };
        final DspCommandDispatcher dispatcher = new DspCommandDispatcher(
                connected::get, connected::get,
                payload -> { writer.write(payload); return DspFrameCodec.encode(payload); },
                this::disconnect, ignored -> { });

        Harness() { dispatcher.open(); dispatcher.start(); }

        CompletableFuture<Boolean> send(byte[] payload, boolean reliable) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (reliable) { dispatcher.sendReliable(payload); return true; }
                    return dispatcher.send(payload);
                } catch (IOException failure) { throw new CompletionException(failure); }
            });
        }

        void disconnect() { connected.set(false); dispatcher.close(new IOException("Session invalidated")); }
        @Override public void close() { disconnect(); timer.shutdownNow(); }
    }
}

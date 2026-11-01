package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Repeatable producer stress against the real dispatcher and response tracker, with an in-memory DSP peer. */
class DspCommandOverloadTest {
    @Test
    @Timeout(15)
    void concurrentProducersRejectBeyondCapacityThenDrainAndReuseTheHealthySession() throws Exception {
        CountDownLatch activeWritten = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        CountDownLatch produce = new CountDownLatch(1);
        AtomicInteger connectionCloses = new AtomicInteger();
        AtomicReference<DspCommandDispatcher> owner = new AtomicReference<>();
        List<Integer> transmitted = Collections.synchronizedList(new ArrayList<>());
        DspCommandDispatcher dispatcher = new DspCommandDispatcher(
                () -> true, () -> true,
                payload -> {
                    int id = (payload[4] & 0xFF) | ((payload[5] & 0xFF) << 8);
                    transmitted.add(id);
                    if (id == 999) {
                        activeWritten.countDown();
                        try {
                            if (!releaseActive.await(5, TimeUnit.SECONDS)) throw new IOException("Test peer not released");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    owner.get().signalResponse(new byte[]{1, 0, 1, 1});
                    return DspFrameCodec.encode(payload);
                }, connectionCloses::incrementAndGet, ignored -> { });
        owner.set(dispatcher);
        dispatcher.open();
        dispatcher.start();

        try (var producers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> active = producers.submit(() -> {
                send(dispatcher, 999);
                return null;
            });
            assertTrue(activeWritten.await(2, TimeUnit.SECONDS));
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int id = 0; id < 256; id++) {
                int commandId = id;
                outcomes.add(producers.submit(() -> {
                    produce.await();
                    try {
                        send(dispatcher, commandId);
                        return true;
                    } catch (RejectedExecutionException overloaded) {
                        return false;
                    }
                }));
            }
            produce.countDown();
            try {
                await(() -> dispatcher.trafficSnapshot(false, null).submittedCommands() == 257);
                assertEquals(64, dispatcher.trafficSnapshot(false, null).pendingCommands());
                assertEquals(64, dispatcher.trafficSnapshot(false, null).maxPendingCommands());
                assertEquals(List.of(999), List.copyOf(transmitted), "Pending commands cannot reach the peer yet");
            } finally {
                releaseActive.countDown();
            }
            active.get(3, TimeUnit.SECONDS);
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes) if (outcome.get(3, TimeUnit.SECONDS)) accepted++;
            assertEquals(64, accepted);
            assertEquals(65, transmitted.size());
            assertEquals(65, transmitted.stream().distinct().count(), "Accepted commands transmit exactly once");
            assertEquals(65, dispatcher.trafficSnapshot(false, null).transmittedCommands());
            assertEquals(0, dispatcher.trafficSnapshot(false, null).pendingCommands());
            assertEquals(0, connectionCloses.get());

            send(dispatcher, 1000);
            assertEquals(66, transmitted.size(), "The same session accepts commands after overload drains");
            assertEquals(0, connectionCloses.get());
        } finally {
            releaseActive.countDown();
            dispatcher.close(null);
        }
    }

    private static void send(DspCommandDispatcher dispatcher, int id) throws IOException {
        // Command 0x37 has no coalescing key, so all 256 producers compete for capacity.
        dispatcher.sendReliable(new byte[]{0, 1, 3, 0x37, (byte) id, (byte) (id >> 8)});
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "All producers must reach the queue within the bounded test deadline");
    }
}

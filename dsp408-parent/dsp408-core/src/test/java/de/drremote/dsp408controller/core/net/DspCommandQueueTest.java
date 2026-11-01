package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

import org.junit.jupiter.api.Test;

class DspCommandQueueTest {
    @Test
    void interactiveRejectsPendingOrForegroundWorkBeforeTransmission() throws Exception {
        for (DspCommandQueue.Priority priority : DspCommandQueue.Priority.values()) {
            DspCommandQueue queue = openQueue();
            queue.submit(new byte[]{1}, priority, null, false);
            assertBusy(interactive(queue, new byte[]{2}, "gain:0"));
            var active = queue.take();
            queue.beginTransmission(active);
            if (priority != DspCommandQueue.Priority.METER && priority != DspCommandQueue.Priority.KEEPALIVE) {
                assertBusy(interactive(queue, new byte[]{2}, "gain:0"));
            }
            queue.complete(active, true);
        }
    }

    @Test
    void interactiveReservationFollowsOneBackgroundExchangeAndCannotBeOvertakenOrCoalesced() throws Exception {
        for (DspCommandQueue.Priority background : List.of(DspCommandQueue.Priority.METER, DspCommandQueue.Priority.KEEPALIVE)) {
            DspCommandQueue queue = openQueue();
            queue.submit(new byte[]{1}, background, "background", false);
            var active = queue.take();
            queue.beginTransmission(active);
            var reserved = interactive(queue, new byte[]{2}, "gain:0");
            var old = queue.submit(new byte[]{3}, DspCommandQueue.Priority.CONTROL, "gain:0", true);
            var newest = queue.submit(new byte[]{4}, DspCommandQueue.Priority.CONTROL, "gain:0", true);
            queue.submit(new byte[]{5}, DspCommandQueue.Priority.SAFETY, null, true);
            assertFalse(old.get());
            assertFalse(reserved.isDone());
            assertBusy(interactive(queue, new byte[]{6}, "gain:1"));
            queue.complete(active, true);
            var selected = queue.take();
            assertSame(reserved, selected.completion());
            // Taking the reservation must not remove the later same-key mapping.
            var replacement = queue.submit(new byte[]{7}, DspCommandQueue.Priority.CONTROL, "gain:0", true);
            assertFalse(newest.get());
            queue.complete(selected, true);
            assertTrue(reserved.get());
            assertEquals(5, queue.take().payload()[0]);
            assertFalse(replacement.isDone());
        }
    }

    @Test
    void interactiveReservationSurvivesSafetyDisplacementAndIsCancelledOnClose() throws Exception {
        DspCommandQueue queue = openQueue();
        var reserved = interactive(queue, new byte[]{1}, "gain:0");
        for (int index = 0; index < 63; index++) queue.submit(new byte[]{2}, DspCommandQueue.Priority.READ, null, false);
        queue.submit(new byte[]{3}, DspCommandQueue.Priority.SAFETY, null, true);
        assertFalse(reserved.isDone());
        assertSame(reserved, queue.take().completion());
        queue.close(new java.io.IOException("closed"));
        assertTrue(reserved.isCompletedExceptionally());
    }

    @Test
    void interactiveReservationCancellationIsDefiniteOnlyBeforeTransmissionStarts() throws Exception {
        DspCommandQueue queue = openQueue();
        var reserved = interactive(queue, new byte[]{1}, "gain:0");
        assertTrue(queue.cancel(reserved, new java.io.IOException("cancelled")));
        assertTrue(reserved.isCompletedExceptionally());
        assertEquals(0, queue.stats().pendingCommands());
        var accepted = interactive(queue, new byte[]{2}, "gain:0");
        var selected = queue.take();
        assertTrue(queue.beginTransmission(selected));
        assertFalse(queue.cancel(accepted, new java.io.IOException("too late")));
        assertFalse(accepted.isDone());
        queue.complete(selected, true);
        assertTrue(accepted.get());
    }

    private static CompletableFuture<Boolean> interactive(DspCommandQueue queue, byte[] payload, String key) throws Exception {
        return queue.submitInteractive(payload, DspCommandQueue.Priority.CONTROL, key, true);
    }

    private static void assertBusy(CompletableFuture<Boolean> future) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertEquals("DspBusyException", failure.getCause().getClass().getSimpleName());
    }

    @Test
    void sixtyFifthPendingCommandFailsWithoutChangingTheQueue() throws Exception {
        DspCommandQueue queue = openQueue();
        var accepted = fill(queue, DspCommandQueue.Priority.CONTROL);

        var rejected = queue.submit(new byte[]{99}, DspCommandQueue.Priority.CONTROL, null, true);

        assertRejected(rejected);
        assertEquals(64, queue.stats().pendingCommands());
        assertEquals(64, queue.stats().maxPendingCommands());
        for (var completion : accepted) {
            var command = queue.take();
            assertSame(completion, command.completion());
            queue.complete(command, true);
            assertTrue(completion.get());
        }
    }

    @Test
    void activeExchangeIsAdditionalToSixtyFourPendingCommands() throws Exception {
        DspCommandQueue queue = openQueue();
        var active = queue.submit(new byte[]{1}, DspCommandQueue.Priority.CONTROL, null, true);
        var command = queue.take();
        assertTrue(queue.beginTransmission(command));
        fill(queue, DspCommandQueue.Priority.CONTROL);

        assertRejected(queue.submit(new byte[]{2}, DspCommandQueue.Priority.CONTROL, null, true));
        assertFalse(active.isDone(), "Overload must leave the active exchange intact");
        queue.complete(command, true);
        assertTrue(active.get());
        assertEquals(64, queue.stats().pendingCommands());
    }

    @Test
    void newestSameKeyValueReplacesAtCapacityBeforeAdmissionCheck() throws Exception {
        DspCommandQueue queue = openQueue();
        var old = queue.submit(new byte[]{10}, DspCommandQueue.Priority.CONTROL, "gain:0", true);
        for (int index = 1; index < 64; index++) {
            queue.submit(new byte[]{0}, DspCommandQueue.Priority.CONTROL, null, true);
        }

        var newest = queue.submit(new byte[]{20}, DspCommandQueue.Priority.CONTROL, "gain:0", true);

        assertFalse(old.get());
        assertFalse(newest.isDone());
        assertEquals(64, queue.stats().pendingCommands());
        for (int index = 0; index < 63; index++) queue.complete(queue.take(), true);
        var selected = queue.take();
        assertEquals(20, selected.payload()[0]);
        queue.complete(selected, true);
        assertTrue(newest.get());
    }

    @Test
    void safetyDisplacesOneLowestPriorityQueuedCommandWithExplicitFailure() throws Exception {
        DspCommandQueue queue = openQueue();
        var active = queue.submit(new byte[]{1}, DspCommandQueue.Priority.METER, "active-meter", false);
        var activeCommand = queue.take();
        assertTrue(queue.beginTransmission(activeCommand));
        var controls = new ArrayList<CompletableFuture<Boolean>>();
        for (int index = 0; index < 62; index++) {
            controls.add(queue.submit(new byte[]{2}, DspCommandQueue.Priority.CONTROL, null, true));
        }
        var read = queue.submit(new byte[]{3}, DspCommandQueue.Priority.READ, "read:1", false);
        var meter = queue.submit(new byte[]{4}, DspCommandQueue.Priority.METER, "meter", false);

        var safety = queue.submit(new byte[]{5}, DspCommandQueue.Priority.SAFETY, "mute:0", true);

        assertRejected(meter);
        assertRejected(queue.submit(new byte[]{6}, DspCommandQueue.Priority.METER, "meter", false));
        assertFalse(read.isDone());
        assertFalse(active.isDone());
        assertTrue(controls.stream().noneMatch(CompletableFuture::isDone));
        assertEquals(64, queue.stats().pendingCommands());
        queue.complete(activeCommand, true);
        var selected = queue.take();
        assertSame(safety, selected.completion());
        queue.complete(selected, true);
        assertTrue(safety.get());
        assertTrue(active.get());
    }

    @Test
    void safetyCannotDisplaceAnotherSafetyCommand() throws Exception {
        DspCommandQueue queue = openQueue();
        var accepted = fill(queue, DspCommandQueue.Priority.SAFETY);

        assertRejected(queue.submit(new byte[]{99}, DspCommandQueue.Priority.SAFETY, null, true));

        assertEquals(64, queue.stats().pendingCommands());
        assertTrue(accepted.stream().noneMatch(CompletableFuture::isDone));
    }

    @Test
    void controlCannotDisplaceLowerPriorityWorkAtCapacity() {
        DspCommandQueue queue = openQueue();
        var accepted = fill(queue, DspCommandQueue.Priority.METER);

        assertRejected(queue.submit(new byte[]{1}, DspCommandQueue.Priority.CONTROL, null, true));

        assertEquals(64, queue.stats().pendingCommands());
        assertTrue(accepted.stream().noneMatch(CompletableFuture::isDone));
    }

    @Test
    void rejectedKeyDoesNotReplaceAnAcceptedCommandLater() throws Exception {
        DspCommandQueue queue = openQueue();
        fill(queue, DspCommandQueue.Priority.CONTROL);
        assertRejected(queue.submit(new byte[]{1}, DspCommandQueue.Priority.CONTROL, "gain:0", true));
        queue.complete(queue.take(), true);

        var accepted = queue.submit(new byte[]{2}, DspCommandQueue.Priority.CONTROL, "gain:0", true);
        var replacement = queue.submit(new byte[]{3}, DspCommandQueue.Priority.CONTROL, "gain:0", true);

        assertFalse(accepted.get());
        assertFalse(replacement.isDone());
        assertEquals(64, queue.stats().pendingCommands());
        assertEquals(1, queue.stats().supersededCommands());
    }

    @Test
    @Timeout(10)
    void concurrentCoalescingAndSafetyAdmissionPreserveNewestValuesAndPriorityAtCapacity() throws Exception {
        DspCommandQueue queue = openQueue();
        List<CompletableFuture<Boolean>> reads = new ArrayList<>();
        for (int index = 0; index < 56; index++) {
            reads.add(queue.submit(new byte[]{0}, DspCommandQueue.Priority.READ, null, false));
        }
        for (int key = 0; key < 8; key++) {
            queue.submit(new byte[]{(byte) key, 0}, DspCommandQueue.Priority.CONTROL, "gain:" + key, true);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CompletableFuture<Boolean>>> producers = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int key = 0; key < 8; key++) {
                int channel = key;
                producers.add(executor.submit(() -> {
                    start.await();
                    CompletableFuture<Boolean> newest = null;
                    for (int value = 0; value < 100; value++) {
                        newest = queue.submit(new byte[]{(byte) channel, (byte) value},
                                DspCommandQueue.Priority.CONTROL, "gain:" + channel, true);
                    }
                    return newest;
                }));
            }
            for (int index = 0; index < 16; index++) {
                int id = index;
                producers.add(executor.submit(() -> {
                    start.await();
                    return queue.submit(new byte[]{(byte) id}, DspCommandQueue.Priority.SAFETY, "mute:" + id, true);
                }));
            }
            start.countDown();
            List<CompletableFuture<Boolean>> admitted = new ArrayList<>();
            for (var producer : producers) admitted.add(producer.get(3, TimeUnit.SECONDS));

            assertEquals(64, queue.stats().pendingCommands());
            assertEquals(64, queue.stats().maxPendingCommands());
            assertEquals(800, queue.stats().supersededCommands());
            assertEquals(16, reads.stream().filter(CompletableFuture::isCompletedExceptionally).count());
            for (var read : reads) if (read.isCompletedExceptionally()) assertRejected(read);
            assertTrue(admitted.stream().noneMatch(CompletableFuture::isDone));
            boolean[] newestChannelSeen = new boolean[8];
            for (int index = 0; index < 64; index++) {
                var command = queue.take();
                assertEquals(index < 16 ? DspCommandQueue.Priority.SAFETY
                        : index < 24 ? DspCommandQueue.Priority.CONTROL : DspCommandQueue.Priority.READ,
                        command.priority());
                if (command.priority() == DspCommandQueue.Priority.CONTROL) {
                    int channel = command.payload()[0];
                    assertFalse(newestChannelSeen[channel]);
                    newestChannelSeen[channel] = true;
                    assertEquals(99, command.payload()[1]);
                }
                assertTrue(queue.beginTransmission(command));
                queue.complete(command, true);
            }
            for (boolean seen : newestChannelSeen) assertTrue(seen);
            for (var completion : admitted) assertTrue(completion.get(1, TimeUnit.SECONDS));
            assertEquals(64, queue.stats().transmittedCommands());
            assertEquals(0, queue.stats().pendingCommands());
        }
    }

    private static DspCommandQueue openQueue() {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        return queue;
    }

    private static List<CompletableFuture<Boolean>> fill(DspCommandQueue queue, DspCommandQueue.Priority priority) {
        var accepted = new ArrayList<CompletableFuture<Boolean>>();
        for (int index = 0; index < 64; index++) {
            accepted.add(queue.submit(new byte[]{(byte) index}, priority, null, true));
        }
        return accepted;
    }

    private static void assertRejected(CompletableFuture<Boolean> completion) {
        assertTrue(completion.isCompletedExceptionally(), "Overload must fail immediately");
        CompletionException failure = assertThrows(CompletionException.class, completion::join);
        assertTrue(failure.getCause() instanceof RejectedExecutionException, failure.toString());
    }

    @Test
    void startedMeterRemainsInFlightWhenPollingStops() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var completion = queue.submit(new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER, "meter", false);
        var selected = queue.take();
        assertTrue(queue.beginTransmission(selected));
        queue.cancelPendingMeters();
        assertFalse(completion.isDone());
        queue.complete(selected, true);
        assertTrue(completion.get());
        assertEquals(1L, queue.stats().meterTransmissions());
    }

    @Test
    void meterSelectedButNotStartedIsCancelledWhenPollingStops() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var completion = queue.submit(new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER, "meter", false);
        var selected = queue.take();
        queue.cancelPendingMeters();
        assertTrue(completion.isDone(), "Selection alone must not make an unsent sample uncancellable");
        assertFalse(completion.get());
        assertFalse(queue.beginTransmission(selected));
        queue.complete(selected, false);
        assertEquals(0L, queue.stats().meterTransmissions());
    }

    @Test
    void safetyCommandsOvertakePendingMeters() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        queue.submit(new byte[]{0, 1, 1, 0x40}, DspCommandQueue.Priority.METER, "meter", false);
        queue.submit(new byte[]{0, 1, 2, 0x35, 4}, DspCommandQueue.Priority.SAFETY, "mute:4", true);

        assertEquals(DspCommandQueue.Priority.SAFETY, queue.take().priority());
        assertEquals(DspCommandQueue.Priority.METER, queue.take().priority());
    }

    @Test
    void newestPendingValueSupersedesOlderValueForSameKey() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var first = queue.submit(
                new byte[]{0, 1, 4, 0x34, 0, 10, 0},
                DspCommandQueue.Priority.CONTROL,
                "gain:0",
                true
        );
        queue.submit(
                new byte[]{0, 1, 4, 0x34, 0, 20, 0},
                DspCommandQueue.Priority.CONTROL,
                "gain:0",
                true
        );

        assertFalse(first.get());
        assertEquals(20, queue.take().payload()[5]);
        assertEquals(1L, queue.stats().supersededCommands());
    }

    @Test
    void repeatedMeterRequestIsCoalesced() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var first = queue.submit(
                new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER,
                "meter",
                false
        );
        queue.submit(
                new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER,
                "meter",
                false
        );

        assertFalse(first.get());
        assertEquals(1L, queue.stats().coalescedMeterRequests());
    }
}

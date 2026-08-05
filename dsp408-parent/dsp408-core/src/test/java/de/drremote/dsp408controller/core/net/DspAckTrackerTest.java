package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DspAckTrackerTest {
    @Test
    void releasesWaiterAfterAck() throws Exception {
        DspAckTracker tracker = new DspAckTracker();
        long snapshot = tracker.snapshot();

        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return tracker.awaitAfter(snapshot, 500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });

        tracker.signal();

        assertTrue(waiting.get(1, TimeUnit.SECONDS));
    }

    @Test
    void timesOutWithoutAck() throws Exception {
        DspAckTracker tracker = new DspAckTracker();

        assertFalse(tracker.awaitAfter(tracker.snapshot(), 20L));
    }
}

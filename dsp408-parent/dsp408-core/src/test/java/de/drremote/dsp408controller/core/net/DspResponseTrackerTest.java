package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DspResponseTrackerTest {
    @Test
    void releasesTheSingleWaiterWithTheReceivedPayload() throws Exception {
        DspResponseTracker tracker = new DspResponseTracker();
        long snapshot = tracker.snapshot();
        byte[] response = {0x01, 0x00, 0x01, 0x01};

        CompletableFuture<DspResponseTracker.ObservedResponse> waiting =
                CompletableFuture.supplyAsync(() -> {
            try {
                return tracker.awaitAfter(snapshot, 500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        tracker.signal(response);

        assertArrayEquals(response, waiting.get(1, TimeUnit.SECONDS).payload());
    }

    @Test
    void timesOutWithoutAResponse() throws Exception {
        DspResponseTracker tracker = new DspResponseTracker();

        assertNull(tracker.awaitAfter(tracker.snapshot(), 20L));
    }

    @Test
    void preservesMultipleFramesForCorrelation() throws Exception {
        DspResponseTracker tracker = new DspResponseTracker();
        long snapshot = tracker.snapshot();
        byte[] stale = {0x01, 0x00, 0x01, 0x10};
        byte[] expected = {0x01, 0x00, 0x01, 0x13};

        tracker.signal(stale);
        tracker.signal(expected);

        DspResponseTracker.ObservedResponse first = tracker.awaitAfter(snapshot, 20L);
        DspResponseTracker.ObservedResponse second = tracker.awaitAfter(first.sequence(), 20L);
        assertArrayEquals(stale, first.payload());
        assertArrayEquals(expected, second.payload());
    }
}

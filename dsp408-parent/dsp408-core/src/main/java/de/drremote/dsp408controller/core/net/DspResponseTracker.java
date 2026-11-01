package de.drremote.dsp408controller.core.net;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Hands the next checksum-valid DSP frame from the socket reader to the single
 * active command. The command queue guarantees that only one waiter exists.
 */
final class DspResponseTracker {
    record ObservedResponse(long sequence, byte[] payload) {
        ObservedResponse {
            payload = Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    private static final int MAX_BUFFERED_RESPONSES = 64;

    private long sequence;
    private final Deque<ObservedResponse> responses = new ArrayDeque<>();

    synchronized long snapshot() {
        return sequence;
    }

    synchronized void signal(byte[] payload) {
        sequence++;
        responses.addLast(new ObservedResponse(sequence, payload));
        while (responses.size() > MAX_BUFFERED_RESPONSES) {
            responses.removeFirst();
        }
        notifyAll();
    }

    synchronized ObservedResponse awaitAfter(long observedSequence, long timeoutMs)
            throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long deadlineNanos = System.nanoTime() + remainingNanos;

        while (sequence <= observedSequence) {
            if (remainingNanos <= 0L) {
                return null;
            }

            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
            wait(millis, nanos);
            remainingNanos = deadlineNanos - System.nanoTime();
        }

        for (ObservedResponse response : responses) {
            if (response.sequence() > observedSequence) {
                return new ObservedResponse(response.sequence(), response.payload());
            }
        }
        return null;
    }

    synchronized void reset() {
        sequence = 0L;
        responses.clear();
        notifyAll();
    }
}

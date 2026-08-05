package de.drremote.dsp408controller.core.net;

import java.util.concurrent.TimeUnit;

/**
 * Tracks generic DSP ACK frames without coupling the socket reader to a
 * particular command thread.
 */
final class DspAckTracker {
    private long sequence;

    synchronized long snapshot() {
        return sequence;
    }

    synchronized void signal() {
        sequence++;
        notifyAll();
    }

    synchronized boolean awaitAfter(long observedSequence, long timeoutMs) throws InterruptedException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long deadlineNanos = System.nanoTime() + remainingNanos;

        while (sequence <= observedSequence) {
            if (remainingNanos <= 0L) {
                return false;
            }

            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
            wait(millis, nanos);
            remainingNanos = deadlineNanos - System.nanoTime();
        }

        return true;
    }

    synchronized void reset() {
        sequence = 0L;
        notifyAll();
    }
}

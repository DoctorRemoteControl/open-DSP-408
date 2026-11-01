package de.drremote.dsp408controller.runtime;

import java.util.Locale;

import de.drremote.dsp408controller.core.net.DspTrafficSnapshot;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.service.DspNotConnectedException;

/**
 * Owns runtime-level DSP transactions which span more than one protocol
 * request. The core transport remains the sole owner of frame serialization;
 * this coordinator adds meter suspension and explicit authoritative readback.
 */
final class DspTransactionCoordinator {
    private static final int DEFAULT_BLOCK_READ_ATTEMPTS = 4;
    private static final long BLOCK_RESPONSE_TIMEOUT_MS = 350L;
    private static final long BLOCK_RESPONSE_POLL_MS = 50L;

    private final DspController controller;
    private final Runnable meterPollingStarter;
    private final Object transactionLock = new Object();

    DspTransactionCoordinator(DspController controller,
                              Runnable meterPollingStarter) {
        this.controller = controller;
        this.meterPollingStarter = meterPollingStarter;
    }

    <T> T withMetersPaused(CheckedSupplier<T> action) throws Exception {
        synchronized (transactionLock) {
            DspTrafficSnapshot before = controller.trafficSnapshot();
            boolean resumeMeterPolling = before.centralMeterPolling();
            if (resumeMeterPolling) {
                try {
                    controller.stopMeterPolling();
                } catch (DspNotConnectedException disconnected) {
                    // A concurrent close already stopped this session's tasks.
                    // Let the operation perform its own connection preflight.
                }
            }

            try {
                return action.get();
            } finally {
                if (resumeMeterPolling && controller.isConnected()) {
                    try {
                        meterPollingStarter.run();
                    } catch (DspNotConnectedException disconnected) {
                        // Disconnect after the connectivity check is expected
                        // cleanup, and must not replace an ACK or I/O failure.
                    }
                }
            }
        }
    }

    byte[] readFreshParameterBlock(int blockIndex) throws Exception {
        return readFreshParameterBlock(blockIndex, DEFAULT_BLOCK_READ_ATTEMPTS);
    }

    byte[] readFreshParameterBlock(int blockIndex, int requestAttempts) throws Exception {
        long previousRevision = controller.state().blockRevision(blockIndex);
        int attempts = Math.max(1, requestAttempts);
        for (int requestAttempt = 1; requestAttempt <= attempts; requestAttempt++) {
            if (controller.state().blockRevision(blockIndex) > previousRevision) {
                byte[] alreadyReceived = controller.state().getBlock(blockIndex);
                if (alreadyReceived != null) {
                    return alreadyReceived;
                }
            }

            controller.readParameterBlock(blockIndex);
            long deadlineEpochMs = System.currentTimeMillis() + BLOCK_RESPONSE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadlineEpochMs) {
                if (controller.state().blockRevision(blockIndex) > previousRevision) {
                    byte[] data = controller.state().getBlock(blockIndex);
                    if (data != null) {
                        return data;
                    }
                }
                Thread.sleep(BLOCK_RESPONSE_POLL_MS);
            }
        }
        throw new IllegalStateException(
                String.format(Locale.ROOT, "Keine frische DSP-Antwort für Block %02X.", blockIndex)
        );
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}

package de.drremote.dsp408controller.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.SettingsCacheDto;
import de.drremote.dsp408controller.core.service.DspController;

/**
 * Maintains the lifecycle of the authoritative in-memory settings snapshot.
 * Reading the snapshot is deliberately independent from the runtime component
 * monitor so HTTP status requests stay cheap while a device transaction runs.
 */
final class DspSettingsCache {
    private static final Logger LOG = LoggerFactory.getLogger(DspSettingsCache.class);
    // Each transport read is sent once. A required response timeout closes
    // the session, so these dump passes cannot retry an exhausted read on
    // the same session; they only revisit blocks still missing while live.
    private static final int MAX_DUMP_PASSES = 2;
    private static final int BLOCK_ATTEMPTS_PER_PASS = 1;
    private static final long SYNC_TIMEOUT_MS = 60_000L;
    private static final long SETTLE_MS = 300L;

    private final DspController controller;
    private final DspTransactionCoordinator transactions;
    private final Runnable confirmedStateRecorder;
    private final AtomicLong generation = new AtomicLong();
    private final Object statusLock = new Object();
    private volatile long invalidationEpoch;
    private volatile SettingsCacheDto status = SettingsCacheDto.empty();

    DspSettingsCache(DspController controller,
                     DspTransactionCoordinator transactions,
                     Runnable confirmedStateRecorder) {
        this.controller = controller;
        this.transactions = transactions;
        this.confirmedStateRecorder = confirmedStateRecorder;
    }

    SettingsCacheDto status() {
        return status;
    }

    SettingsCacheDto synchronize(String reason) throws Exception {
        return synchronize(reason, () -> true);
    }

    SettingsCacheDto synchronize(String reason, BooleanSupplier sessionCurrent) throws Exception {
        long requestedEpoch = invalidationEpoch;
        return transactions.withMetersPaused(
                () -> synchronizeWithMetersStopped(reason, requestedEpoch, sessionCurrent));
    }

    private SettingsCacheDto synchronizeWithMetersStopped(
            String reason, long requestedEpoch, BooleanSupplier sessionCurrent) throws Exception {
        int maxBlockIndex = controller.maxParameterBlockIndex();
        int expectedBlockCount = maxBlockIndex + 1;
        long currentGeneration;
        long startedAtEpochMs = System.currentTimeMillis();
        synchronized (statusLock) {
            ensureCurrent(requestedEpoch, sessionCurrent);
            currentGeneration = generation.incrementAndGet();
            controller.state().clearCachedBlocks();
            status = new SettingsCacheDto(
                    "SYNCHRONIZING",
                    false,
                    expectedBlockCount,
                    0,
                    allBlockIndices(maxBlockIndex),
                    startedAtEpochMs,
                    null,
                    currentGeneration,
                    "Reading the complete DSP settings dump (" + reason + ")."
            );
        }

        Exception dumpFailure = null;
        List<Integer> missing = missingBlocks(maxBlockIndex);
        long deadlineEpochMs = startedAtEpochMs + SYNC_TIMEOUT_MS;
        for (int pass = 1;
             pass <= MAX_DUMP_PASSES
                     && !missing.isEmpty()
                     && controller.isConnected()
                     && System.currentTimeMillis() < deadlineEpochMs;
             pass++) {
            for (int blockIndex : List.copyOf(missing)) {
                ensureCurrent(requestedEpoch, sessionCurrent);
                if (System.currentTimeMillis() >= deadlineEpochMs) {
                    break;
                }
                try {
                    transactions.readFreshParameterBlock(blockIndex, BLOCK_ATTEMPTS_PER_PASS);
                } catch (Exception error) {
                    ensureCurrent(requestedEpoch, sessionCurrent);
                    dumpFailure = error;
                    LOG.warn(
                            "DSP settings dump pass {}/{} block {} failed: {}",
                            pass,
                            MAX_DUMP_PASSES,
                            DspRuntimeDtoMapper.formatIndexHex(blockIndex),
                            safeMessage(error)
                    );
                }
            }
            Thread.sleep(SETTLE_MS);
            missing = missingBlocks(maxBlockIndex);
            publish(requestedEpoch, sessionCurrent, new SettingsCacheDto(
                    "SYNCHRONIZING",
                    false,
                    expectedBlockCount,
                    expectedBlockCount - missing.size(),
                    missing.stream().map(DspRuntimeDtoMapper::formatIndexHex).toList(),
                    startedAtEpochMs,
                    null,
                    currentGeneration,
                    "Reading DSP settings dump; pass " + pass + "/" + MAX_DUMP_PASSES + "."
            ));
        }

        ensureCurrent(requestedEpoch, sessionCurrent);
        boolean ready = missing.isEmpty();
        int cachedBlockCount = expectedBlockCount - missing.size();
        long completedAtEpochMs = System.currentTimeMillis();
        String message;
        if (ready) {
            message = readyMessage();
            confirmedStateRecorder.run();
        } else {
            String suffix = dumpFailure == null
                    ? ""
                    : " Last pass: " + safeMessage(dumpFailure);
            message = "DSP settings cache is incomplete (" + cachedBlockCount + "/"
                    + expectedBlockCount + " blocks)." + suffix;
        }

        return publish(requestedEpoch, sessionCurrent, new SettingsCacheDto(
                ready ? "READY" : "DEGRADED",
                ready,
                expectedBlockCount,
                cachedBlockCount,
                missing.stream().map(DspRuntimeDtoMapper::formatIndexHex).toList(),
                startedAtEpochMs,
                completedAtEpochMs,
                currentGeneration,
                message
        ));
    }

    private SettingsCacheDto publish(long requestedEpoch, BooleanSupplier sessionCurrent,
                                     SettingsCacheDto replacement) throws IOException {
        synchronized (statusLock) {
            ensureCurrent(requestedEpoch, sessionCurrent);
            status = replacement;
            return replacement;
        }
    }

    private void ensureCurrent(long requestedEpoch, BooleanSupplier sessionCurrent) throws IOException {
        if (invalidationEpoch != requestedEpoch || !sessionCurrent.getAsBoolean()
                || !controller.isConnected() || Thread.currentThread().isInterrupted()) {
            throw new IOException("DSP settings synchronization cancelled or disconnected");
        }
    }

    void promoteComplete() {
        SettingsCacheDto previous = status;
        if (!controller.isConnected() || previous.ready()
                || (!"DEGRADED".equals(previous.phase())
                && !"SYNCHRONIZING".equals(previous.phase()))) {
            return;
        }

        int maxBlockIndex = controller.maxParameterBlockIndex();
        if (!missingBlocks(maxBlockIndex).isEmpty()) {
            return;
        }

        confirmedStateRecorder.run();
        synchronized (statusLock) {
            if (status != previous || !controller.isConnected()) {
                return;
            }
            status = new SettingsCacheDto(
                    "READY",
                    true,
                    maxBlockIndex + 1,
                    maxBlockIndex + 1,
                    List.of(),
                    previous.startedAtEpochMs(),
                    System.currentTimeMillis(),
                    previous.generation(),
                    readyMessage()
            );
        }
    }

    void markDisconnected() {
        synchronized (statusLock) {
            invalidationEpoch++;
            SettingsCacheDto previous = status;
            status = new SettingsCacheDto(
                    "DISCONNECTED",
                    false,
                    previous.expectedBlockCount(),
                    controller.state().cachedBlockIndices().size(),
                    previous.missingBlockIndices(),
                    previous.startedAtEpochMs(),
                    previous.completedAtEpochMs(),
                    previous.generation(),
                    "DSP disconnected; the last settings snapshot is retained but not live."
            );
        }
    }

    void markStale(String message) {
        synchronized (statusLock) {
            invalidationEpoch++;
            SettingsCacheDto previous = status;
            status = new SettingsCacheDto(
                    "STALE",
                    false,
                    previous.expectedBlockCount(),
                    controller.state().cachedBlockIndices().size(),
                    previous.missingBlockIndices(),
                    previous.startedAtEpochMs(),
                    previous.completedAtEpochMs(),
                    previous.generation(),
                    message
            );
        }
    }

    private List<Integer> missingBlocks(int maxBlockIndex) {
        List<Integer> missing = new ArrayList<>();
        for (int blockIndex = 0; blockIndex <= maxBlockIndex; blockIndex++) {
            if (controller.state().getBlock(blockIndex) == null) {
                missing.add(blockIndex);
            }
        }
        return missing;
    }

    private static List<String> allBlockIndices(int maxBlockIndex) {
        List<String> indices = new ArrayList<>();
        for (int blockIndex = 0; blockIndex <= maxBlockIndex; blockIndex++) {
            indices.add(DspRuntimeDtoMapper.formatIndexHex(blockIndex));
        }
        return List.copyOf(indices);
    }

    private static String readyMessage() {
        return "Complete DSP settings dump is cached; settings reads are RAM-only.";
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}

package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

final class DspCommandQueue {
    private static final int PENDING_COMMAND_LIMIT = 64;
    enum Priority {
        SAFETY(0),
        CONTROL(10),
        KEEPALIVE(20),
        READ(30),
        METER(40);

        private final int order;

        Priority(int order) {
            this.order = order;
        }
    }

    static final class Command {
        private final byte[] payload;
        private final Priority priority;
        private final long sequence;
        private final String coalesceKey;
        private final boolean reliableWrite;
        private boolean transmissionStarted;
        private final CompletableFuture<Boolean> completion = new CompletableFuture<>();

        private Command(byte[] payload,
                        Priority priority,
                        long sequence,
                        String coalesceKey,
                        boolean reliableWrite) {
            this.payload = Arrays.copyOf(payload, payload.length);
            this.priority = priority;
            this.sequence = sequence;
            this.coalesceKey = coalesceKey;
            this.reliableWrite = reliableWrite;
        }

        byte[] payload() {
            return payload;
        }

        Priority priority() {
            return priority;
        }

        String coalesceKey() {
            return coalesceKey;
        }

        boolean reliableWrite() {
            return reliableWrite;
        }

        CompletableFuture<Boolean> completion() {
            return completion;
        }
    }

    record Stats(
            int pendingCommands,
            int maxPendingCommands,
            long submittedCommands,
            long transmittedCommands,
            long supersededCommands,
            long meterTransmissions,
            long coalescedMeterRequests,
            long controlTransmissions,
            long writeRetries,
            long unacknowledgedWrites,
            String activePriority,
            long lastTransmitEpochMs
    ) {
    }

    private final PriorityQueue<Command> pending = new PriorityQueue<>(
            Comparator.comparingInt((Command command) -> command.priority.order)
                    .thenComparingLong(command -> command.sequence)
    );
    private final Map<String, Command> pendingByKey = new HashMap<>();

    private boolean open;
    private long nextSequence;
    private int maxPendingCommands;
    private long submittedCommands;
    private long transmittedCommands;
    private long supersededCommands;
    private long meterTransmissions;
    private long coalescedMeterRequests;
    private long controlTransmissions;
    private long writeRetries;
    private long unacknowledgedWrites;
    private Priority activePriority;
    private Command activeCommand;
    private long lastTransmitEpochMs;

    synchronized void open() {
        pending.clear();
        pendingByKey.clear();
        open = true;
        nextSequence = 0L;
        maxPendingCommands = 0;
        submittedCommands = 0L;
        transmittedCommands = 0L;
        supersededCommands = 0L;
        meterTransmissions = 0L;
        coalescedMeterRequests = 0L;
        controlTransmissions = 0L;
        writeRetries = 0L;
        unacknowledgedWrites = 0L;
        activePriority = null;
        lastTransmitEpochMs = 0L;
        notifyAll();
    }

    synchronized CompletableFuture<Boolean> submit(byte[] payload,
                                                    Priority priority,
                                                    String coalesceKey,
                                                    boolean reliableWrite) {
        if (!open || Thread.currentThread().isInterrupted()) {
            return CompletableFuture.failedFuture(new IOException("DSP command queue is closed"));
        }

        Command command = new Command(
                payload,
                priority,
                nextSequence++,
                coalesceKey,
                reliableWrite
        );
        submittedCommands++;

        Command previous = coalesceKey == null ? null : pendingByKey.get(coalesceKey);
        if (previous != null) {
            removePending(previous);
            supersededCommands++;
            if (previous.priority == Priority.METER) {
                coalescedMeterRequests++;
            }
            previous.completion.complete(false);
        } else if (pending.size() >= PENDING_COMMAND_LIMIT) {
            Command displaced = priority == Priority.SAFETY ? safetyDisplacementCandidate() : null;
            if (displaced == null) {
                command.completion.completeExceptionally(new RejectedExecutionException(
                        "DSP command queue is full (64 pending commands)"));
                return command.completion;
            }
            removePending(displaced);
            displaced.completion.completeExceptionally(new RejectedExecutionException(
                    "DSP command displaced by a safety command because the queue is full"));
        }

        if (coalesceKey != null) {
            pendingByKey.put(coalesceKey, command);
        }
        pending.add(command);
        maxPendingCommands = Math.max(maxPendingCommands, pending.size());
        notifyAll();
        return command.completion;
    }

    private Command safetyDisplacementCandidate() {
        // Preserve higher priorities and older commands within the selected priority.
        return pending.stream()
                .filter(command -> command.priority.order > Priority.SAFETY.order)
                .max(Comparator.comparingInt((Command command) -> command.priority.order)
                        .thenComparingLong(command -> command.sequence))
                .orElse(null);
    }

    private void removePending(Command command) {
        pending.remove(command);
        if (command.coalesceKey != null) {
            pendingByKey.remove(command.coalesceKey, command);
        }
    }

    synchronized Command take() throws InterruptedException {
        while (open && pending.isEmpty()) {
            wait();
        }
        if (!open) {
            return null;
        }

        Command command = pending.remove();
        if (command.coalesceKey != null) {
            pendingByKey.remove(command.coalesceKey, command);
        }
        activePriority = command.priority;
        activeCommand = command;
        return command;
    }

    synchronized boolean beginTransmission(Command command) {
        if (activeCommand != command || command.completion.isDone()) return false;
        command.transmissionStarted = true;
        return true;
    }

    synchronized void complete(Command command, boolean transmitted) {
        if (activeCommand != command) return;
        activeCommand = null;
        activePriority = null;
        if (transmitted) {
            transmittedCommands++;
            lastTransmitEpochMs = System.currentTimeMillis();
            if (command.priority == Priority.METER) {
                meterTransmissions++;
            } else if (command.priority == Priority.SAFETY || command.priority == Priority.CONTROL) {
                controlTransmissions++;
            }
        } else if (command.priority == Priority.METER) {
            coalescedMeterRequests++;
        }
        command.completion.complete(transmitted);
        notifyAll();
    }

    synchronized void fail(Command command, Throwable failure) {
        if (activeCommand != command) return;
        activeCommand = null;
        activePriority = null;
        command.completion.completeExceptionally(failure);
        notifyAll();
    }

    synchronized boolean cancel(CompletableFuture<Boolean> completion, Throwable failure) {
        if (activeCommand != null && activeCommand.completion == completion
                && !activeCommand.transmissionStarted) {
            completion.completeExceptionally(new IOException("DSP command cancelled before transmission", failure));
            return true;
        }
        for (Command command : pending) {
            if (command.completion == completion) {
                pending.remove(command);
                if (command.coalesceKey != null) pendingByKey.remove(command.coalesceKey, command);
                completion.completeExceptionally(new IOException("DSP command cancelled before transmission", failure));
                return true;
            }
        }
        return false;
    }

    synchronized void cancelPendingMeters() {
        if (activeCommand != null && activeCommand.priority == Priority.METER
                && !activeCommand.transmissionStarted) {
            activeCommand.completion.complete(false);
        }
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Command command = iterator.next();
            if (command.priority == Priority.METER) {
                iterator.remove();
                pendingByKey.remove(command.coalesceKey, command);
                coalescedMeterRequests++;
                command.completion.complete(false);
            }
        }
    }

    synchronized void recordWriteRetry() {
        writeRetries++;
    }

    synchronized void recordUnacknowledgedWrite() {
        unacknowledgedWrites++;
    }

    synchronized Stats stats() {
        return new Stats(
                pending.size(),
                maxPendingCommands,
                submittedCommands,
                transmittedCommands,
                supersededCommands,
                meterTransmissions,
                coalescedMeterRequests,
                controlTransmissions,
                writeRetries,
                unacknowledgedWrites,
                activePriority == null ? null : activePriority.name().toLowerCase(),
                lastTransmitEpochMs
        );
    }

    synchronized void close(Throwable failure) {
        open = false;
        IOException closed = failure instanceof IOException io
                ? io
                : new IOException("DSP command queue closed", failure);
        if (activeCommand != null) {
            activeCommand.completion.completeExceptionally(closed);
            activeCommand = null;
        }
        for (Command command : pending) {
            command.completion.completeExceptionally(closed);
        }
        pending.clear();
        pendingByKey.clear();
        activePriority = null;
        notifyAll();
    }
}

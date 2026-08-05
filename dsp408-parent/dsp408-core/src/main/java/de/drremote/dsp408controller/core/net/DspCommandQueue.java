package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

final class DspCommandQueue {
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
        if (!open) {
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

        if (coalesceKey != null) {
            Command previous = pendingByKey.put(coalesceKey, command);
            if (previous != null && pending.remove(previous)) {
                supersededCommands++;
                if (previous.priority == Priority.METER) {
                    coalescedMeterRequests++;
                }
                previous.completion.complete(false);
            }
        }

        pending.add(command);
        maxPendingCommands = Math.max(maxPendingCommands, pending.size());
        notifyAll();
        return command.completion;
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
        return command;
    }

    synchronized void complete(Command command, boolean transmitted) {
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
        activePriority = null;
        command.completion.completeExceptionally(failure);
        notifyAll();
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
        for (Command command : pending) {
            command.completion.completeExceptionally(closed);
        }
        pending.clear();
        pendingByKey.clear();
        activePriority = null;
        notifyAll();
    }
}

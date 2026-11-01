package de.drremote.dsp408controller.http;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;

/** Servlet-local delivery protection; completed outcomes and failures are retained. */
final class DspParameterRequestCache {
    private static final int CAPACITY = 4096;
    private static final long COMPLETED_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);
    private final Map<String, Entry> entries = new HashMap<>();
    private final LongSupplier clock;

    DspParameterRequestCache() {
        this(System::nanoTime);
    }

    DspParameterRequestCache(LongSupplier clock) {
        this.clock = clock;
    }

    ParameterWriteResultDto execute(String key, String dspId, ParameterWriteRequestDto request,
                                   WriteOperation operation) throws Exception {
        return execute(key, dspId, null, request, operation);
    }

    ParameterWriteResultDto execute(String key, String dspId, Object targetIdentity,
                                   ParameterWriteRequestDto request, WriteOperation operation) throws Exception {
        Entry entry;
        boolean owner;
        synchronized (entries) {
            long now = clock.getAsLong();
            entries.values().removeIf(existing -> existing.completed
                    && now - existing.completedAt >= COMPLETED_TTL_NANOS);
            entry = entries.get(key);
            owner = entry == null;
            if (owner) {
                if (entries.size() >= CAPACITY) {
                    throw new RejectedExecutionException("Parameter request cache is full. Try again later.");
                }
                entry = new Entry(dspId, targetIdentity, request);
                entries.put(key, entry);
            } else if (!Objects.equals(entry.dspId, dspId) || entry.targetIdentity != targetIdentity
                    || !entry.request.equals(request)) {
                throw new IllegalStateException("Idempotency-Key was already used for another parameter request.");
            }
        }

        // Device I/O and duplicate waiting must never hold the admission lock.
        if (owner) {
            try {
                entry.outcome.complete(Objects.requireNonNull(operation.execute(), "Parameter result is required."));
            } catch (Exception | Error failure) {
                entry.outcome.completeExceptionally(failure);
            } finally {
                synchronized (entries) {
                    entry.completedAt = clock.getAsLong();
                    entry.completed = true;
                }
            }
        }
        return await(entry.outcome);
    }

    private static ParameterWriteResultDto await(CompletableFuture<ParameterWriteResultDto> outcome)
            throws Exception {
        try {
            return outcome.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected parameter request failure.", cause);
        }
    }

    @FunctionalInterface
    interface WriteOperation {
        ParameterWriteResultDto execute() throws Exception;
    }

    private static final class Entry {
        final String dspId;
        final Object targetIdentity;
        final ParameterWriteRequestDto request;
        final CompletableFuture<ParameterWriteResultDto> outcome = new CompletableFuture<>();
        long completedAt;
        boolean completed;

        Entry(String dspId, Object targetIdentity, ParameterWriteRequestDto request) {
            this.dspId = dspId;
            this.targetIdentity = targetIdentity;
            this.request = request;
        }
    }
}

package de.drremote.dsp408controller.runtime;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import de.drremote.dsp408.api.ParameterReadbackDto;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.net.DspBusyException;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspParameter;
import de.drremote.dsp408controller.core.service.DspParameterCapability;
import de.drremote.dsp408controller.core.service.DspParameterCatalog;
import de.drremote.dsp408controller.core.service.DspParameterReading;
import de.drremote.dsp408controller.core.service.DspParameterSession;
import de.drremote.dsp408controller.core.service.UnsupportedDspOperationException;

/** One bounded write and optional authoritative readback, with no replay or rollback. */
final class DspParameterWrites {
    private static final int MAX_ADMITTED_OPERATIONS = 65;

    private final DspController controller;
    private final DspTransactionCoordinator transactions;
    private final Supplier<String> dspId;
    private final UnaryOperator<ParameterWriteResultDto> completed;
    private final Semaphore admission = new Semaphore(MAX_ADMITTED_OPERATIONS);

    DspParameterWrites(DspController controller, DspTransactionCoordinator transactions,
            Supplier<String> dspId) {
        this(controller, transactions, dspId, UnaryOperator.identity());
    }

    DspParameterWrites(DspController controller, DspTransactionCoordinator transactions,
            Supplier<String> dspId, UnaryOperator<ParameterWriteResultDto> completed) {
        this.completed = Objects.requireNonNull(completed);
        this.controller = Objects.requireNonNull(controller);
        this.transactions = Objects.requireNonNull(transactions);
        this.dspId = Objects.requireNonNull(dspId);
    }

    ParameterWriteResultDto write(ParameterWriteRequestDto request) {
        return write(request, false, () -> { });
    }

    ParameterWriteResultDto writeInteractive(ParameterWriteRequestDto request) {
        return write(request, true, () -> { });
    }

    ParameterWriteResultDto writeConditional(ParameterWriteRequestDto request, boolean interactive,
            Runnable precondition) {
        return write(request, interactive, Objects.requireNonNull(precondition));
    }

    private ParameterWriteResultDto write(ParameterWriteRequestDto request, boolean interactive,
            Runnable precondition) {
        Operation operation = new Operation(request);
        // Bound callers before the shared transaction monitor as well as the
        // physical queue, including callers waiting behind legacy transactions.
        if (!admission.tryAcquire()) {
            operation.dspId = dspId.get();
            return operation.reject(interactive ? "dsp_busy" : "dsp_overloaded",
                    "The parameter operation limit is full (65 admitted writes).");
        }
        try {
            // Identity, the condition and cache invalidation all run inside the
            // existing admitted transaction; readers never take this lock.
            return interactive
                    ? transactions.withMetersPausedInteractive(() -> completed.apply(
                            performSafely(operation, true, precondition)))
                    : transactions.withMetersPaused(() -> completed.apply(
                            performSafely(operation, false, precondition)));
        } catch (DspBusyException busy) {
            operation.dspId = dspId.get();
            return operation.reject("dsp_busy",
                    "The interactive write was rejected before transmission: " + busy.getMessage());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception unexpected) {
            throw new IllegalStateException("Unexpected parameter transaction failure", unexpected);
        } finally {
            admission.release();
        }
    }

    private ParameterWriteResultDto performSafely(Operation operation, boolean interactive,
            Runnable precondition) {
        try {
            return perform(operation, interactive, precondition);
        } catch (CancellationException superseded) {
            return operation.result(operation.acknowledged ? ParameterWriteStatus.UNCERTAIN
                    : ParameterWriteStatus.REJECTED, operation.acknowledged ? Boolean.TRUE : Boolean.FALSE,
                    operation.acknowledged ? "readback_failed" : "write_superseded",
                    operation.acknowledged
                            ? "The DSP acknowledged the write, but fresh readback was superseded."
                            : "A newer queued write superseded this write before transmission.");
        } catch (DspBusyException busy) {
            if (operation.dspId == null) operation.dspId = dspId.get();
            return operation.result(operation.acknowledged ? ParameterWriteStatus.UNCERTAIN
                    : ParameterWriteStatus.REJECTED, operation.acknowledged ? Boolean.TRUE : Boolean.FALSE,
                    "dsp_busy", operation.acknowledged
                            ? "The DSP acknowledged the write, but readback found other DSP work."
                            : "The interactive write was rejected before transmission: " + busy.getMessage());
        } catch (RejectedExecutionException overloaded) {
            return operation.result(operation.acknowledged ? ParameterWriteStatus.UNCERTAIN
                    : ParameterWriteStatus.REJECTED, operation.acknowledged ? Boolean.TRUE : Boolean.FALSE,
                    "dsp_overloaded", operation.acknowledged
                            ? "The write was acknowledged, but readback admission was rejected."
                            : "The DSP command queue rejected the write before transmission.");
        } catch (IOException failure) {
            if (!operation.attempted) {
                return operation.result(ParameterWriteStatus.REJECTED, Boolean.FALSE,
                        "session_changed", "The DSP session changed before transmission.");
            }
            return operation.result(ParameterWriteStatus.UNCERTAIN,
                    operation.acknowledged ? Boolean.TRUE : null,
                    operation.acknowledged ? "readback_failed" : "write_failed",
                    operation.acknowledged
                            ? "The DSP acknowledged the write, but fresh readback failed: " + failure.getMessage()
                            : "The write may have reached the DSP; no acknowledgement was established: "
                                    + failure.getMessage());
        } catch (RuntimeException failure) {
            // Programming errors are not valid device outcomes.
            throw failure;
        } catch (Exception unexpected) {
            throw new IllegalStateException("Unexpected parameter transaction failure", unexpected);
        }
    }

    private ParameterWriteResultDto perform(Operation operation, boolean interactive,
            Runnable precondition) throws IOException {
        operation.dspId = dspId.get();
        // Report a stale reviewed state even if the connection changed to
        // offline; a matching condition still receives the usual device gates.
        precondition.run();
        if (operation.request == null) {
            return operation.reject("invalid_input", "A parameter write request is required.");
        }

        DspParameterSession session = controller.openParameterSession();
        DspParameterCatalog catalog = session.catalog();
        DspChannel channel;
        DspParameter parameter;
        try {
            if (operation.channelId == null || operation.parameter == null) {
                throw new IllegalArgumentException("Channel and parameter are required.");
            }
            channel = DspChannel.parse(operation.channelId);
            parameter = DspParameter.parse(operation.parameter);
            operation.channelId = DspRuntimeDtoMapper.channelId(channel);
            operation.parameter = parameter.id();
            operation.normalizedValue = catalog.normalize(parameter, operation.request.value());
        } catch (IllegalArgumentException invalid) {
            return operation.reject("invalid_input", invalid.getMessage());
        }

        if (!catalog.connected()) {
            return operation.reject("dsp_not_connected", "The DSP is disconnected.");
        }
        if (!catalog.firmwareRecognized()) {
            return operation.reject("unsupported_firmware", "The DSP firmware identity is not recognized.");
        }
        DspParameterCapability capability = catalog.capability(channel, parameter);
        if (!capability.writable()) {
            return operation.reject("unsupported_parameter", "This parameter has no supported write mapping.");
        }
        if (operation.request.verify() && !capability.readbackSupported()) {
            return operation.reject("readback_unsupported", "This parameter has no confirmed readback mapping.");
        }
        if (!session.isCurrent()) {
            return operation.reject("session_changed", "The DSP session changed before transmission.");
        }

        // No physical work may occur between the comparison and this bound
        // session write. Competing participating writers share this transaction.
        precondition.run();
        operation.attempted = true;
        try {
            if (interactive) session.writeInteractive(channel, parameter, operation.normalizedValue);
            else session.write(channel, parameter, operation.normalizedValue);
        } catch (UnsupportedDspOperationException unsupported) {
            return operation.reject("unsupported_parameter", unsupported.getMessage());
        }
        // The bound core session returns only after an actual ACK, including a
        // mute request which happens to equal the locally cached value.
        operation.acknowledged = true;
        if (!operation.request.verify()) {
            return operation.result(ParameterWriteStatus.ACKNOWLEDGED, Boolean.TRUE, null,
                    "The DSP acknowledged the write; readback was not requested.");
        }
        if (!session.isCurrent()) {
            return operation.result(ParameterWriteStatus.UNCERTAIN, Boolean.TRUE, "session_changed",
                    "The DSP acknowledged the write, but the connection changed before verification.");
        }

        DspParameterReading reading = interactive ? session.readBackInteractive(channel, parameter)
                : session.readBack(channel, parameter);
        if (!session.isCurrent()) {
            return operation.result(ParameterWriteStatus.UNCERTAIN, Boolean.TRUE, "session_changed",
                    "The DSP acknowledged the write, but the connection changed during verification.");
        }
        if (reading.receiveRevision() <= 0 || reading.observedAtEpochMs() <= 0) {
            return operation.result(ParameterWriteStatus.UNCERTAIN, Boolean.TRUE, "readback_failed",
                    "The readback did not establish a fresh DSP receive revision.");
        }
        operation.readback = new ParameterReadbackDto(reading.value(),
                String.format(Locale.ROOT, "%02X", reading.blockIndex()), reading.receiveRevision(),
                reading.observedAtEpochMs());
        if (!Objects.equals(operation.normalizedValue, operation.readback.value())) {
            return operation.result(ParameterWriteStatus.UNCERTAIN, Boolean.TRUE, "readback_mismatch",
                    "Fresh DSP readback differs from the normalized requested value.");
        }
        return operation.result(ParameterWriteStatus.VERIFIED, Boolean.TRUE, null,
                "The DSP acknowledged the write and fresh readback matches the normalized value.");
    }

    private static final class Operation {
        final String requestId = UUID.randomUUID().toString();
        final ParameterWriteRequestDto request;
        String dspId;
        String channelId;
        String parameter;
        Object normalizedValue;
        boolean attempted;
        boolean acknowledged;
        ParameterReadbackDto readback;

        Operation(ParameterWriteRequestDto request) {
            this.request = request;
            channelId = request == null ? null : request.channelId();
            if (channelId != null) {
                try {
                    channelId = DspRuntimeDtoMapper.channelId(DspChannel.parse(channelId));
                } catch (IllegalArgumentException unknownChannel) {
                    // Preserve invalid input for the rejection returned by preflight.
                }
            }
            parameter = request == null ? null : request.parameter();
        }

        ParameterWriteResultDto reject(String code, String message) {
            return result(ParameterWriteStatus.REJECTED, Boolean.FALSE, code, message);
        }

        ParameterWriteResultDto result(ParameterWriteStatus status, Boolean transmitted, String code, String message) {
            return new ParameterWriteResultDto(requestId, dspId, channelId, parameter,
                    request == null ? null : request.value(), normalizedValue, status, acknowledged,
                    transmitted, readback, code, message, System.currentTimeMillis());
        }
    }
}

package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.util.Hex;

/**
 * Serializes DSP commands and correlates exactly one request with its response.
 * Socket ownership and frame decoding remain outside this class.
 */
final class DspCommandDispatcher implements DspPayloadTransport {
	private static final long MISSING_METER_RECOVERY_QUIET_MS = 200L;
	private static final long QUEUE_WAIT_TIMEOUT_SECONDS = 15L;

	@FunctionalInterface
	interface FrameWriter {
		byte[] write(byte[] payload) throws IOException;
	}

	private final BooleanSupplier running;
	private final BooleanSupplier connected;
	private final FrameWriter frameWriter;
	private final Consumer<BooleanSupplier> connectionCloser;
	private final Consumer<String> log;
	private final DspTrafficGate trafficGate = new DspTrafficGate();
	private final DspResponseTracker responseTracker = new DspResponseTracker();
	private final DspCommandQueue commandQueue = new DspCommandQueue();

	private volatile Thread workerThread;

	DspCommandDispatcher(BooleanSupplier running, BooleanSupplier connected,
			FrameWriter frameWriter, Runnable connectionCloser, Consumer<String> log) {
		this(running, connected, frameWriter,
				stillCurrent -> { if (stillCurrent.getAsBoolean()) connectionCloser.run(); }, log);
	}

	DspCommandDispatcher(BooleanSupplier running, BooleanSupplier connected,
			FrameWriter frameWriter, Consumer<BooleanSupplier> connectionCloser, Consumer<String> log) {
		this.running = running;
		this.connected = connected;
		this.frameWriter = frameWriter;
		this.connectionCloser = connectionCloser;
		this.log = log;
	}

	synchronized void open() {
		trafficGate.reset();
		responseTracker.reset();
		commandQueue.open();
	}

	synchronized void start() {
		start(frameWriter);
	}

	synchronized void start(FrameWriter workerWriter) {
		workerThread = new Thread(() -> workerLoop(workerWriter), "dsp408-command-queue");
		workerThread.setDaemon(true);
		workerThread.start();
	}

	@Override
	public boolean send(byte[] payload) throws IOException {
		return enqueue(payload, false);
	}

    boolean sendInteractive(byte[] payload) throws IOException {
        return enqueue(payload, false, true);
    }

	@Override
	public void sendReliable(byte[] payload) throws IOException {
		if (!enqueue(payload, true)) {
			throw new IOException("DSP write was superseded before transmission");
		}
	}

	void signalResponse(byte[] payload) {
		trafficGate.recordResponse(System.nanoTime());
		responseTracker.signal(payload);
	}

	DspTrafficSnapshot trafficSnapshot(boolean meterPolling, Long meterPollIntervalMs) {
		DspCommandQueue.Stats stats = commandQueue.stats();
		return new DspTrafficSnapshot(
				stats.pendingCommands(),
				stats.maxPendingCommands(),
				stats.submittedCommands(),
				stats.transmittedCommands(),
				stats.supersededCommands(),
				stats.meterTransmissions(),
				stats.coalescedMeterRequests(),
				stats.controlTransmissions(),
				stats.writeRetries(),
				stats.unacknowledgedWrites(),
				stats.activePriority(),
				stats.lastTransmitEpochMs(),
				meterPolling,
				meterPollIntervalMs
		);
	}

	synchronized boolean hasResources() {
		return workerThread != null;
	}

	synchronized void close(Throwable failure) {
		commandQueue.close(failure);
		Thread worker = workerThread;
		workerThread = null;
		if (worker != null && worker != Thread.currentThread()) {
			worker.interrupt();
		}
		responseTracker.reset();
	}

	void cancelPendingMeters() {
		commandQueue.cancelPendingMeters();
	}

    private boolean enqueue(byte[] payload, boolean reliableWrite) throws IOException {
        return enqueue(payload, reliableWrite, false);
    }

    private boolean enqueue(byte[] payload, boolean reliableWrite, boolean interactive) throws IOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new IOException("Interrupted before submitting DSP command");
		}
		Thread owner;
		CompletableFuture<Boolean> completion;
		synchronized (this) {
			ensureConnected();
			owner = workerThread;
            completion = interactive
                    ? commandQueue.submitInteractive(payload, DspCommandPolicy.priorityOf(payload),
                            DspCommandPolicy.coalesceKey(payload), reliableWrite)
                    : commandQueue.submit(payload, DspCommandPolicy.priorityOf(payload),
                            DspCommandPolicy.coalesceKey(payload), reliableWrite);
		}

		try {
			return completion.get(QUEUE_WAIT_TIMEOUT_SECONDS * 1_000L
					+ DspCommandPolicy.responseTimeoutMs(payload),
					TimeUnit.MILLISECONDS);
		} catch (InterruptedException error) {
			cancelWaitingCommand(completion, payload, owner, error);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for DSP command queue", error);
		} catch (TimeoutException error) {
			cancelWaitingCommand(completion, payload, owner, error);
			throw new IOException("DSP command queue timed out", error);
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			if (cause instanceof IOException io) {
				throw io;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IOException("DSP command failed", cause);
		}
	}

	private void cancelWaitingCommand(CompletableFuture<Boolean> completion, byte[] payload, Thread owner, Exception failure) {
		boolean removed = commandQueue.cancel(completion, failure);
		// An active meter may finish before the next serialized command. An
		// abandoned control command must never leave an unidentifiable ACK.
		if (!removed && !completion.isDone() && !DspProtocol.isRuntimeMeterRequest(payload)) {
			closeSession(owner);
		}
	}

	private boolean transmitAndAwaitResponse(DspCommandQueue.Command command, FrameWriter workerWriter)
			throws IOException {
		byte[] payload = command.payload();
		ensureConnected();
		if (!trafficGate.mayTransmit(payload, System.nanoTime())) {
			if (DspProtocol.isRuntimeMeterRequest(payload)) {
				log("--> TX coalesced runtime meter request");
			}
			return false;
		}

		long observedResponseSequence = responseTracker.snapshot();
		byte[] lastUnexpectedResponse = null;
		ensureCurrentWorker();
		if (!commandQueue.beginTransmission(command)) return false;
		writePayload(payload, workerWriter);
		long timeoutMs = DspCommandPolicy.responseTimeoutMs(payload);
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (true) {
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0L) {
				break;
			}

			DspResponseTracker.ObservedResponse observed;
			try {
				observed = responseTracker.awaitAfter(
						observedResponseSequence,
						Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
				);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for DSP response", error);
			}
			if (observed == null) {
				break;
			}

			observedResponseSequence = observed.sequence();
			byte[] response = observed.payload();
			if (DspCommandPolicy.responseMatches(payload, response)) {
				return true;
			}
			lastUnexpectedResponse = response;
			log("Ignoring unrelated DSP frame while waiting for "
					+ Hex.format(payload) + ": " + Hex.format(response));
		}

		String reason = lastUnexpectedResponse == null
				? "timeout"
				: "timeout; last unrelated frame " + Hex.format(lastUnexpectedResponse);
		if (DspProtocol.isRuntimeMeterRequest(payload)) {
			log("Runtime meter response absent for " + Hex.format(payload)
					+ "; dropping the best-effort sample and leaving a "
					+ MISSING_METER_RECOVERY_QUIET_MS
					+ " ms recovery window before the next queued command.");
			pause(MISSING_METER_RECOVERY_QUIET_MS, "missing DSP meter response");
			return true;
		}
		failUnansweredCommand(payload, reason);
		return false;
	}

	private void writePayload(byte[] payload, FrameWriter workerWriter) throws IOException {
		ensureCurrentWorker();
		byte[] frame = workerWriter.write(payload);
		ensureCurrentWorker();
		long transmitNanos = System.nanoTime();
		trafficGate.recordTransmitted(payload, transmitNanos);
		log("--> TX payload=" + Hex.format(payload) + " | frame=" + Hex.format(frame));
	}

	private void pause(long delayMs, String operation) throws IOException {
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + operation, error);
		}
	}

	private void failUnansweredCommand(byte[] payload, String reason) throws IOException {
		if (DspCommandPolicy.expectsAck(payload)) {
			commandQueue.recordUnacknowledgedWrite();
		}
		IOException failure = new IOException(
				"DSP response failure (" + reason + ") for " + Hex.format(payload)
		);
		log("DSP command/response synchronization lost: " + failure.getMessage());
		ensureCurrentWorker();
		closeSession(Thread.currentThread());
		throw failure;
	}

	private void workerLoop(FrameWriter workerWriter) {
		while (running.getAsBoolean() && workerThread == Thread.currentThread()) {
			DspCommandQueue.Command command;
			try {
				command = commandQueue.take();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				break;
			}
			if (command == null) {
				break;
			}

			try {
				ensureCurrentWorker();
				if (command.completion().isDone()) {
					commandQueue.complete(command, false);
					continue;
				}
				boolean transmitted = transmitAndAwaitResponse(command, workerWriter);
				if (workerThread == Thread.currentThread()) {
					commandQueue.complete(command, transmitted);
				}
			} catch (Exception error) {
				if (workerThread != Thread.currentThread()) {
					break;
				}
				commandQueue.fail(command, error);
				if (error instanceof IOException && connected.getAsBoolean()) {
					closeSession(Thread.currentThread());
				}
				if (running.getAsBoolean()) {
					log("Command queue error: " + error.getMessage());
				}
			}
		}
	}

	private void closeSession(Thread owner) {
		connectionCloser.accept(() -> owner != null && workerThread == owner);
	}

	private void ensureCurrentWorker() throws IOException {
		if (workerThread != Thread.currentThread() || Thread.currentThread().isInterrupted()) {
			throw new IOException("DSP command belongs to a closed session");
		}
	}

	private void ensureConnected() {
		if (!connected.getAsBoolean()) {
			throw new IllegalStateException("Not connected");
		}
	}

	private void log(String message) {
		if (log != null) {
			log.accept(message);
		}
	}
}

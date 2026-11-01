package de.drremote.dsp408controller.core.net;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the keepalive and runtime-meter schedulers for one DSP connection. */
final class DspBackgroundTasks {
	@FunctionalInterface
	interface Task {
		void run() throws Exception;
	}

	private final BooleanSupplier running;
	private final Task keepAliveTask;
	private final Task meterTask;
	private final Consumer<String> log;

	private volatile ScheduledExecutorService keepAliveExecutor;
	private volatile ScheduledExecutorService meterPollExecutor;
	private volatile Long meterPollIntervalMs;

	DspBackgroundTasks(
			BooleanSupplier running,
			Task keepAliveTask,
			Task meterTask,
			Consumer<String> log
	) {
		this.running = running;
		this.keepAliveTask = keepAliveTask;
		this.meterTask = meterTask;
		this.log = log;
	}

	synchronized void startMeterPolling(long intervalMs) {
		if (intervalMs < 50) {
			throw new IllegalArgumentException("Meter poll interval must be >= 50 ms");
		}

		stopMeterPolling();

		meterPollExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "dsp408-meterpoll");
			thread.setDaemon(true);
			return thread;
		});
		meterPollIntervalMs = intervalMs;
		meterPollExecutor.scheduleAtFixedRate(
				() -> runIfConnected(meterTask, "Meter poll error: "),
				// A control transaction pauses and restarts this scheduler. Waiting
				// one normal interval prevents an immediate meter request from being
				// inserted between consecutive writes from an app-side batch.
				intervalMs,
				intervalMs,
				TimeUnit.MILLISECONDS
		);

		log("Meter polling started (" + intervalMs + " ms)");
	}

	synchronized void stopMeterPolling() {
		ScheduledExecutorService executor = meterPollExecutor;
		meterPollExecutor = null;
		meterPollIntervalMs = null;
		if (executor != null) {
			executor.shutdownNow();
			log("Meter polling stopped");
		}
	}

	synchronized void startKeepAlive(long initialDelayMs, long intervalMs) {
		ScheduledExecutorService old = keepAliveExecutor;
		keepAliveExecutor = null;
		if (old != null) {
			old.shutdownNow();
		}

		keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "dsp408-keepalive");
			thread.setDaemon(true);
			return thread;
		});
		keepAliveExecutor.scheduleAtFixedRate(
				() -> runIfConnected(keepAliveTask, "KeepAlive error: "),
				initialDelayMs,
				intervalMs,
				TimeUnit.MILLISECONDS
		);
	}

	boolean isMeterPolling() {
		return meterPollExecutor != null;
	}

	Long meterPollIntervalMs() {
		return meterPollIntervalMs;
	}

	boolean hasResources() {
		return keepAliveExecutor != null || meterPollExecutor != null;
	}

	synchronized void close() {
		ScheduledExecutorService keepAlive = keepAliveExecutor;
		keepAliveExecutor = null;
		if (keepAlive != null) {
			keepAlive.shutdownNow();
		}

		ScheduledExecutorService meterPoll = meterPollExecutor;
		meterPollExecutor = null;
		if (meterPoll != null) {
			meterPoll.shutdownNow();
		}
		meterPollIntervalMs = null;
	}

	private void runIfConnected(Task task, String errorPrefix) {
		if (!running.getAsBoolean()) {
			return;
		}
		try {
			task.run();
		} catch (Exception error) {
			log(errorPrefix + error.getMessage());
		}
	}

	private void log(String message) {
		if (log != null) {
			log.accept(message);
		}
	}
}

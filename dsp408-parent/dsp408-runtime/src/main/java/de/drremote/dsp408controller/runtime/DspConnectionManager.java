package de.drremote.dsp408controller.runtime;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.SettingsCacheDto;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.service.DspController;

/** Owns DSP connection lifecycle, reconnect backoff and watchdog scheduling. */
final class DspConnectionManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DspConnectionManager.class);
    private static final long WATCHDOG_INTERVAL_MS = 1_000L;
    private static final long RECONNECT_INITIAL_DELAY_MS = 25_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 60_000L;

    private final DspController controller;
    private final DspSettingsCache settingsCache;
    private final Supplier<DspConnectionConfig> configSupplier;
    private final Supplier<String> instanceIdSupplier;
    private final Supplier<Boolean> autoConnectSupplier;
    private final Runnable meterPollingStarter;
    private final Consumer<String> deviceLog;
    private final DspReconnectSchedule reconnectSchedule = new DspReconnectSchedule(
            RECONNECT_INITIAL_DELAY_MS, RECONNECT_MAX_DELAY_MS);
    private final Object lifecycleLock = new Object();
    // One running attempt and at most one replacement. Duplicate callers share
    // the attempt result rather than adding tasks or replacing a usable session.
    private final ThreadPoolExecutor lifecycleExecutor;
    private ScheduledExecutorService watchdogExecutor;
    private boolean reconnectDesired;
    private volatile Attempt activeAttempt;
    private volatile boolean closed;

    DspConnectionManager(DspController controller,
                         DspSettingsCache settingsCache,
                         Supplier<DspConnectionConfig> configSupplier,
                         Supplier<String> instanceIdSupplier,
                         Supplier<Boolean> autoConnectSupplier,
                         Runnable meterPollingStarter,
                         Consumer<String> deviceLog) {
        this.controller = controller;
        this.settingsCache = settingsCache;
        this.configSupplier = configSupplier;
        this.instanceIdSupplier = instanceIdSupplier;
        this.autoConnectSupplier = autoConnectSupplier;
        this.meterPollingStarter = meterPollingStarter;
        this.deviceLog = deviceLog;
        this.lifecycleExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "dsp408-connection-" + instanceIdSupplier.get());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    void setReconnectDesired(boolean desired) {
        synchronized (lifecycleLock) {
            reconnectDesired = desired && !closed;
            if (!reconnectDesired) {
                reconnectSchedule.cancel();
            }
        }
    }

    String connect() throws Exception {
        return await(requestConnect());
    }

    /** Enqueues bootstrap without doing network I/O on an OSGi callback thread. */
    CompletableFuture<String> requestConnect() {
        synchronized (lifecycleLock) {
            ensureOpen();
            reconnectDesired = true;
            if (activeAttempt != null) {
                return activeAttempt.result;
            }
            if (controller.isConnected()) {
                reconnectSchedule.recordSuccess();
                return CompletableFuture.completedFuture(connectionMessage(
                        "Already connected to", configSupplier.get(), settingsCache.status()));
            }
            return startAttemptLocked();
        }
    }

    String reconnect() throws Exception {
        return await(requestReconnect());
    }

    CompletableFuture<String> requestReconnect() {
        synchronized (lifecycleLock) {
            ensureOpen();
            reconnectDesired = true;
            cancelAttemptLocked();
            controller.close();
            settingsCache.markDisconnected();
            return startAttemptLocked();
        }
    }

    void disconnect() {
        synchronized (lifecycleLock) {
            reconnectDesired = false;
            reconnectSchedule.cancel();
            cancelAttemptLocked();
            controller.close();
            settingsCache.markDisconnected();
        }
    }

    void startWatchdog() {
        synchronized (lifecycleLock) {
            if (closed || watchdogExecutor != null) {
                return;
            }
            watchdogExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dsp408-connection-watchdog-" + instanceIdSupplier.get());
                thread.setDaemon(true);
                return thread;
            });
            watchdogExecutor.scheduleWithFixedDelay(
                    this::watchdogTick, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    void stopWatchdog() {
        synchronized (lifecycleLock) {
            ScheduledExecutorService executor = watchdogExecutor;
            watchdogExecutor = null;
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            reconnectDesired = false;
            reconnectSchedule.cancel();
            stopWatchdog();
            cancelAttemptLocked();
            lifecycleExecutor.shutdownNow();
            controller.close();
            settingsCache.markDisconnected();
        }
    }

    private CompletableFuture<String> startAttemptLocked() {
        Attempt attempt = new Attempt(configSupplier.get());
        attempt.task = new FutureTask<>(() -> {
            runAttempt(attempt);
            return null;
        });
        activeAttempt = attempt;
        lifecycleExecutor.execute(attempt.task);
        return attempt.result;
    }

    private void runAttempt(Attempt attempt) {
        try {
            ensureCurrent(attempt);
            controller.connect(attempt.config, deviceLog);
            ensureCurrent(attempt);
            SettingsCacheDto cache;
            try {
                cache = settingsCache.synchronize("connect", () -> isCurrent(attempt));
            } catch (Exception error) {
                ensureCurrent(attempt);
                if (!controller.isConnected()) {
                    throw error;
                }
                LOG.warn("Initial DSP settings synchronization failed: {}", safeMessage(error));
                cache = settingsCache.status();
            }
            synchronized (lifecycleLock) {
                ensureCurrent(attempt);
                if (!controller.isConnected()) {
                    throw new IOException("DSP disconnected during settings synchronization");
                }
                meterPollingStarter.run();
                reconnectSchedule.recordSuccess();
                activeAttempt = null;
                attempt.result.complete(connectionMessage("Connected to", attempt.config, cache));
            }
        } catch (Exception error) {
            synchronized (lifecycleLock) {
                // Cancellation already completed the waiting callers. A stale
                // task cannot close a replacement connection or publish success.
                if (activeAttempt != attempt) {
                    return;
                }
                controller.close();
                settingsCache.markDisconnected();
                activeAttempt = null;
                if (!closed && reconnectDesired && Boolean.TRUE.equals(autoConnectSupplier.get())) {
                    long retryInMs = reconnectSchedule.recordFailure(System.currentTimeMillis());
                    LOG.warn("DSP408 {} connect failed; automatic retry in {} ms: {}",
                            instanceIdSupplier.get(), retryInMs, safeMessage(error));
                } else {
                    reconnectDesired = false;
                    reconnectSchedule.cancel();
                    LOG.warn("DSP408 {} connect failed; automatic retry disabled: {}",
                            instanceIdSupplier.get(), safeMessage(error));
                }
                attempt.result.completeExceptionally(error);
            }
        }
    }

    private void cancelAttemptLocked() {
        Attempt attempt = activeAttempt;
        activeAttempt = null;
        if (attempt != null) {
            attempt.task.cancel(true);
            lifecycleExecutor.remove(attempt.task);
            attempt.result.completeExceptionally(new IOException("DSP connection attempt cancelled"));
        }
    }

    private boolean isCurrent(Attempt attempt) {
        return !closed && activeAttempt == attempt && !Thread.currentThread().isInterrupted();
    }

    private void ensureCurrent(Attempt attempt) throws IOException {
        if (!isCurrent(attempt)) {
            throw new IOException("DSP connection attempt cancelled");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DSP connection manager is closed");
        }
    }

    private static String await(CompletableFuture<String> result) throws Exception {
        try {
            return result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw error;
        }
    }

    private static String connectionMessage(String prefix, DspConnectionConfig config, SettingsCacheDto cache) {
        return prefix + " " + config.ip() + ":" + config.port()
                + " (settings cache " + cache.cachedBlockCount()
                + "/" + cache.expectedBlockCount() + ", "
                + cache.phase().toLowerCase(Locale.ROOT) + ")";
    }

    private void watchdogTick() {
        try {
            synchronized (lifecycleLock) {
                if (closed || !reconnectDesired || activeAttempt != null) {
                    return;
                }
                if (controller.isConnected()) {
                    reconnectSchedule.recordSuccess();
                    return;
                }
                long nowEpochMs = System.currentTimeMillis();
                if (!reconnectSchedule.isArmed()) {
                    reconnectSchedule.arm(nowEpochMs);
                    settingsCache.markDisconnected();
                    LOG.warn("DSP408 {} connection lost; automatic reconnect scheduled in {} ms",
                            instanceIdSupplier.get(), RECONNECT_INITIAL_DELAY_MS);
                } else if (reconnectSchedule.isDue(nowEpochMs)) {
                    startAttemptLocked();
                }
            }
        } catch (Exception error) {
            LOG.warn("DSP408 {} connection watchdog failed: {}",
                    instanceIdSupplier.get(), safeMessage(error));
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class Attempt {
        private final DspConnectionConfig config;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private FutureTask<Void> task;

        private Attempt(DspConnectionConfig config) {
            this.config = config;
        }
    }
}

package de.drremote.dsp408controller.matrix;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.DspService;

@Component(
        service = MatrixBotComponent.class,
        immediate = true,
        configurationPid = "de.drremote.dsp408controller.matrix"
)
@Designate(ocd = MatrixBotConfiguration.class)
public final class MatrixBotComponent {
    private static final Logger LOG = LoggerFactory.getLogger(MatrixBotComponent.class);

    @Reference
    private DspService service;

    private volatile MatrixRuntimeConfig config;
    private volatile LimiterWarningMonitor warningMonitor;
    private volatile AtomicBoolean runToken = new AtomicBoolean(false);
    private ExecutorService dspExecutor;
    private Thread syncThread;

    @Activate
    void activate(MatrixBotConfiguration configuration) {
        this.config = MatrixRuntimeConfig.from(configuration);
        startIfEnabled();
    }

    @Modified
    void modified(MatrixBotConfiguration configuration) {
        stop();
        this.config = MatrixRuntimeConfig.from(configuration);
        startIfEnabled();
    }

    @Deactivate
    void deactivate() {
        stop();
    }

    private synchronized void startIfEnabled() {
        MatrixRuntimeConfig cfg = this.config;

        if (cfg == null || !cfg.enabled) {
            LOG.info("Matrix bot is disabled");
            return;
        }

        if (!cfg.isValid()) {
            LOG.warn("Matrix bot configuration is incomplete");
            return;
        }

        if (cfg.connectDspOnStart && !service.isConnected()) {
            try {
                service.connect();
            } catch (Exception e) {
                LOG.warn("DSP not reachable during bot start: {}", e.getMessage());
            }
        }

        dspExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dsp408-matrix-dsp");
            t.setDaemon(true);
            return t;
        });

        AtomicBoolean token = new AtomicBoolean(true);
        runToken = token;

        syncThread = new Thread(() -> runLoop(cfg, token), "dsp408-matrix-sync");
        syncThread.setDaemon(true);
        syncThread.start();

        LOG.info("Matrix bot start requested");
    }

    private void runLoop(MatrixRuntimeConfig cfg, AtomicBoolean token) {
        while (token.get()) {
            try {
                MatrixApiClient api = new MatrixApiClient(cfg.matrixUrl, cfg.accessToken);

                String botUserId = api.whoAmI().path("user_id").asText();
                LOG.info("Matrix bot user: {}", botUserId);

                String since = api.sync(null, 0).path("next_batch").asText();
                if (!token.get()) {
                    break;
                }

                LimiterWarningMonitor monitor = null;
                if (cfg.warningRoomId != null && !cfg.warningRoomId.isBlank()) {
                    monitor = new LimiterWarningMonitor(
                            service,
                            api,
                            cfg.warningRoomId,
                            cfg.limiterWarningsEnabled,
                            cfg.limiterWarningPollMs,
                            cfg.limiterWarningCooldownMs,
                            msg -> LOG.info("[LIMITER] {}", msg)
                    );
                    monitor.start();
                    warningMonitor = monitor;
                }

                MatrixMessageHandler messageHandler = new MatrixMessageHandler(
                        cfg,
                        service,
                        api,
                        dspExecutor,
                        botUserId,
                        monitor,
                        msg -> LOG.info("[MATRIX] {}", msg)
                );

                LOG.info("Matrix bot started. Control room: {}", cfg.controlRoomId);
                if (cfg.volumeRoomId != null && !cfg.volumeRoomId.isBlank()) {
                    LOG.info("Matrix bot volume room: {}", cfg.volumeRoomId);
                }
                if (cfg.warningRoomId != null && !cfg.warningRoomId.isBlank()) {
                    LOG.info("Matrix bot limiter warning room: {}", cfg.warningRoomId);
                }
                if (cfg.machineRoomId != null && !cfg.machineRoomId.isBlank()) {
                    LOG.info("Matrix bot machine room: {}", cfg.machineRoomId);
                }

                while (token.get()) {
                    try {
                        var sync = api.sync(since, cfg.syncTimeoutMs);
                        since = sync.path("next_batch").asText();

                        var joinedRooms = sync.path("rooms").path("join");
                        if (!joinedRooms.isObject()) {
                            continue;
                        }

                        messageHandler.handleRoomEvents(joinedRooms, cfg.controlRoomId, "control");
                        messageHandler.handleRoomEvents(joinedRooms, cfg.volumeRoomId, "volume");
                        messageHandler.handleRoomEvents(joinedRooms, cfg.warningRoomId, "warning");
                        messageHandler.handleRoomEvents(joinedRooms, cfg.machineRoomId, "machine");

                    } catch (Exception e) {
                        if (!token.get()) {
                            break;
                        }

                        LOG.warn("Matrix sync error: {}", e.getMessage());

                        try {
                            Thread.sleep(cfg.reconnectDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

            } catch (Exception e) {
                if (!token.get()) {
                    break;
                }
                LimiterWarningMonitor monitor = warningMonitor;
                if (monitor != null) {
                    monitor.stop();
                    warningMonitor = null;
                }
                LOG.error("Matrix bot start/login failed", e);
                try {
                    Thread.sleep(cfg.reconnectDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOG.info("Matrix bot stopped");
    }

    private synchronized void stop() {
        runToken.set(false);

        LimiterWarningMonitor monitor = warningMonitor;
        if (monitor != null) {
            monitor.stop();
            warningMonitor = null;
        }

        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }

        if (dspExecutor != null) {
            dspExecutor.shutdownNow();
            dspExecutor = null;
        }
    }
}

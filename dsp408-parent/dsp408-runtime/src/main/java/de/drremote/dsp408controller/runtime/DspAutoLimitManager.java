package de.drremote.dsp408controller.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.AutoLimitLeaseDto;
import de.drremote.dsp408.api.AutoLimitStatusDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.LimiterDto;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.SettingsCacheDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

/**
 * Runs the complete server-side Auto-Limit workflow.
 *
 * <p>The runtime component delegates job state, lease ownership, measurement,
 * limiter verification and rollback to this class. DSP writes remain injected
 * operations so the normal runtime cache-update path stays authoritative.</p>
 */
final class DspAutoLimitManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DspAutoLimitManager.class);
    private static final int TIGHTENING_MAX_ROUNDS = 16;

    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspTransactionCoordinator transactions;
    private final DspSettingsCache settingsCache;
    private final BlockReader blockReader;
    private final LimiterWriter limiterWriter;
    private final GainWriter gainWriter;
    private final Runnable rememberLimiterKnees;
    private final AutoLimitLeaseManager autoLimitLease = new AutoLimitLeaseManager();
    private final ExecutorService autoLimitExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "dsp408-auto-limit");
        thread.setDaemon(true);
        return thread;
    });

    private volatile AutoLimitStatusDto autoLimitStatus = AutoLimitStatusDto.idle();
    private volatile Future<?> autoLimitFuture;
    private volatile boolean autoLimitCancelRequested;
    private volatile AutoLimitUndoSnapshot autoLimitUndoSnapshot;

    DspAutoLimitManager(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspTransactionCoordinator transactions,
            DspSettingsCache settingsCache,
            BlockReader blockReader,
            LimiterWriter limiterWriter,
            GainWriter gainWriter,
            Runnable rememberLimiterKnees
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.transactions = transactions;
        this.settingsCache = settingsCache;
        this.blockReader = blockReader;
        this.limiterWriter = limiterWriter;
        this.gainWriter = gainWriter;
        this.rememberLimiterKnees = rememberLimiterKnees;
    }

    synchronized AutoLimitLeaseDto getAutoLimitLease() {
        return autoLimitLease.current();
    }

    synchronized AutoLimitLeaseDto acquireAutoLimitLease(String owner, long ttlMs) {
        return autoLimitLease.acquire(owner, ttlMs);
    }

    synchronized AutoLimitLeaseDto renewAutoLimitLease(String token, long ttlMs) {
        return autoLimitLease.renew(token, ttlMs);
    }

    synchronized AutoLimitLeaseDto releaseAutoLimitLease(String token) {
        return autoLimitLease.release(token);
    }

    synchronized boolean ownsAutoLimitLease(String token) {
        return autoLimitLease.owns(token);
    }

    synchronized AutoLimitStatusDto getAutoLimitStatus() {
        return withCurrentAutoLimitUndo(autoLimitStatus);
    }

    synchronized AutoLimitStatusDto startAutoLimit(
            double headroomDb,
            String targetMode,
            long measurementMs
    ) {
        if (autoLimitStatus.running()) {
            throw new IllegalStateException("Auto-Limit läuft bereits.");
        }
        if (!controller.isConnected()) {
            throw new IllegalStateException("DSP ist nicht verbunden.");
        }

        String normalizedMode = AutoLimitPlanner.normalizeMode(targetMode);
        double normalizedHeadroomDb = AutoLimitPlanner.normalizeHeadroom(headroomDb);
        long normalizedMeasurementMs = AutoLimitPlanner.normalizeMeasurementMs(measurementMs);
        AutoLimitLeaseDto lease = acquireAutoLimitLease(
                "DSP server Auto-Limit", AutoLimitLeaseManager.MAX_TTL_MS);
        if (!lease.acquired() || lease.token() == null) {
            throw new IllegalStateException(lease.message());
        }

        String jobId = UUID.randomUUID().toString();
        long startedAtEpochMs = System.currentTimeMillis();
        autoLimitUndoSnapshot = null;
        autoLimitCancelRequested = false;
        autoLimitStatus = new AutoLimitStatusDto(
                jobId,
                "QUEUED",
                true,
                0,
                normalizedMode,
                normalizedHeadroomDb,
                normalizedMeasurementMs,
                "Auto-Limit wurde auf dem Server gestartet.",
                Map.of(),
                Map.of(),
                List.of(),
                startedAtEpochMs,
                null,
                false,
                false,
                null,
                null
        );
        autoLimitFuture = autoLimitExecutor.submit(() -> runAutoLimit(jobId, lease.token()));
        return autoLimitStatus;
    }

    synchronized AutoLimitStatusDto cancelAutoLimit() {
        if (!autoLimitStatus.running()) {
            return getAutoLimitStatus();
        }
        if (autoLimitStatus.phase().startsWith("UNDO")) {
            return getAutoLimitStatus();
        }
        autoLimitCancelRequested = true;
        publishAutoLimitStatus(
                "CANCELLING",
                true,
                autoLimitStatus.progressPercent(),
                "Auto-Limit wird abgebrochen; vorherige Werte werden wiederhergestellt.",
                autoLimitStatus.measuredPeaksDb(),
                autoLimitStatus.appliedThresholdsDb(),
                new LinkedHashSet<>(autoLimitStatus.activeLimiterIds()),
                null,
                autoLimitStatus.rollbackPerformed()
        );
        Future<?> running = autoLimitFuture;
        if (running != null) {
            running.cancel(true);
        }
        return autoLimitStatus;
    }

    synchronized AutoLimitStatusDto undoAutoLimit() {
        if (autoLimitStatus.running()) {
            throw new IllegalStateException("Auto-Limit oder Rückgängig läuft bereits.");
        }
        if (!controller.isConnected()) {
            throw new IllegalStateException("DSP ist nicht verbunden.");
        }
        AutoLimitUndoSnapshot snapshot = autoLimitUndoSnapshot;
        if (snapshot == null) {
            throw new IllegalStateException("Kein Auto-Limit-Vorher-Zustand verfügbar.");
        }
        AutoLimitLeaseDto lease = acquireAutoLimitLease(
                "DSP server Auto-Limit undo",
                AutoLimitLeaseManager.MAX_TTL_MS
        );
        if (!lease.acquired() || lease.token() == null) {
            throw new IllegalStateException(lease.message());
        }

        String jobId = UUID.randomUUID().toString();
        long startedAtEpochMs = System.currentTimeMillis();
        autoLimitCancelRequested = false;
        autoLimitStatus = withCurrentAutoLimitUndo(new AutoLimitStatusDto(
                jobId,
                "UNDO_QUEUED",
                true,
                0,
                autoLimitStatus.targetMode(),
                autoLimitStatus.headroomDb(),
                autoLimitStatus.measurementMs(),
                "Vorherige Auto-Limit-Werte werden auf dem Server vorbereitet.",
                Map.of(),
                snapshot.thresholdsDb(),
                List.of(),
                startedAtEpochMs,
                null,
                false,
                true,
                snapshot.sourceJobId(),
                snapshot.createdAtEpochMs()
        ));
        autoLimitFuture = autoLimitExecutor.submit(
                () -> runAutoLimitUndo(jobId, snapshot, lease.token())
        );
        return autoLimitStatus;
    }


    synchronized void invalidateUndo() {
        autoLimitUndoSnapshot = null;
    }

    @Override
    public synchronized void close() {
        autoLimitCancelRequested = true;
        Future<?> running = autoLimitFuture;
        if (running != null) {
            running.cancel(true);
        }
        autoLimitExecutor.shutdownNow();
    }

    private void runAutoLimit(String jobId, String leaseToken) {
        Map<String, LimiterSettings> previousById = new LinkedHashMap<>();
        LinkedHashSet<String> appliedIds = new LinkedHashSet<>();
        Map<String, Double> previousGainsDb = new LinkedHashMap<>();
        Map<String, Double> adjustedGainsDb = new LinkedHashMap<>();
        LinkedHashSet<String> appliedGainIds = new LinkedHashSet<>();
        Map<String, Double> peaksDb = new LinkedHashMap<>();
        Map<String, Double> thresholdsDb = new LinkedHashMap<>();
        Set<String> activeIds = new LinkedHashSet<>();
        boolean rollbackPerformed = false;
        try {
            List<LimiterDto> limiters = readConfirmedAutoLimiters();
            List<String> outputIds = limiters.stream().map(LimiterDto::id).toList();
            for (LimiterDto limiter : limiters) {
                if (limiter.thresholdDb() != null
                        && limiter.attackMs() != null
                        && limiter.releaseMs() != null) {
                    previousById.put(
                            limiter.id(),
                            new LimiterSettings(
                                    limiter.thresholdDb(),
                                    limiter.attackMs(),
                                    limiter.releaseMs()
                            )
                    );
                }
            }
            if (previousById.size() != outputIds.size()) {
                throw new IllegalStateException("Nicht alle vorherigen Limiterwerte sind verfügbar.");
            }

            AutoLimitStatusDto initialStatus = getAutoLimitStatus();
            long measurementMs = initialStatus.measurementMs();
            publishAutoLimitStatus(
                    "MEASURING",
                    true,
                    1,
                    "Lauteste Passage abspielen: Server misst die Ausgänge …",
                    peaksDb,
                    thresholdsDb,
                    activeIds,
                    null,
                    false
            );
            int measurementSamples = Math.max(1, (int) Math.ceil(measurementMs / 375.0));
            long lastMeasurementEpochMs = 0L;
            for (int sample = 0; sample < measurementSamples; sample++) {
                checkAutoLimitCancelled();
                MeterStateDto meters = awaitFreshAutoLimitMeters(lastMeasurementEpochMs);
                lastMeasurementEpochMs = meters.observedAtEpochMs();
                rejectInputClipping(meters);
                for (MeterDto meter : meters.channels()) {
                    if (meter.id().startsWith("out")
                            && meter.levelConfirmedFromDevice()
                            && meter.levelDb() != null
                            && meter.levelDb() > AutoLimitPlanner.MIN_SIGNAL_DB) {
                        peaksDb.merge(meter.id(), meter.levelDb(), Math::max);
                    }
                }
                int progress = 2 + ((sample + 1) * 33 / measurementSamples);
                publishAutoLimitStatus(
                        "MEASURING",
                        true,
                        progress,
                        "Referenzpegel wird gemessen (" + (sample + 1) + "/" + measurementSamples + ") …",
                        peaksDb,
                        thresholdsDb,
                        activeIds,
                        null,
                        false
                );
                if (sample + 1 < measurementSamples) {
                    Thread.sleep(375L);
                }
            }
            if (peaksDb.isEmpty()) {
                throw new IllegalStateException("Kein aktiver Ausgang über -50 dB erkannt.");
            }

            String targetMode = initialStatus.targetMode();
            thresholdsDb = new LinkedHashMap<>(AutoLimitPlanner.initialThresholds(
                    outputIds,
                    peaksDb,
                    initialStatus.headroomDb(),
                    targetMode
            ));
            if (thresholdsDb.isEmpty()) {
                throw new IllegalStateException("Keine Ausgangsschwelle konnte berechnet werden.");
            }

            List<String> targetIds = thresholdsDb.keySet().stream().toList();
            renewServerAutoLimitLease(leaseToken);
            checkAutoLimitCancelled();
            publishAutoLimitStatus(
                    "WRITING",
                    true,
                    36,
                    "Schreibe " + targetIds.size() + " Limiter in einer DSP-Transaktion …",
                    peaksDb,
                    thresholdsDb,
                    activeIds,
                    null,
                    false
            );
            setLimitersReliably(
                    limiterSettingsFor(
                            targetIds,
                            thresholdsDb,
                            AutoLimitPlanner.ATTACK_MS,
                            AutoLimitPlanner.RELEASE_MS
                    ),
                    appliedIds
            );

            publishAutoLimitStatus(
                    "VERIFYING",
                    true,
                    72,
                    "Echte LIMIT-Signale werden geprüft …",
                    peaksDb,
                    thresholdsDb,
                    activeIds,
                    null,
                    false
            );
            activeIds = observeActiveLimiters(new LinkedHashSet<>(targetIds));
            int clearanceRound = 0;
            while (true) {
                while (!activeIds.isEmpty()) {
                    checkAutoLimitCancelled();
                    Map<String, Double> raised = AutoLimitPlanner.raiseActiveThresholds(
                            thresholdsDb,
                            activeIds
                    );
                    List<String> changedIds = new ArrayList<>();
                    for (String id : raised.keySet()) {
                        if (!raised.get(id).equals(thresholdsDb.get(id))) {
                            changedIds.add(id);
                        }
                    }
                    if (changedIds.isEmpty()) {
                        Map<String, Double> loweredGainsDb = new LinkedHashMap<>();
                        renewServerAutoLimitLease(leaseToken);
                        for (String id : activeIds) {
                            double currentGainDb;
                            if (adjustedGainsDb.containsKey(id)) {
                                currentGainDb = adjustedGainsDb.get(id);
                            } else {
                                ChannelDto channel = readConfirmedOutputGain(id);
                                currentGainDb = channel.gainDb();
                                previousGainsDb.putIfAbsent(id, currentGainDb);
                            }
                            double loweredGainDb = Math.max(-60.0, currentGainDb - 1.0);
                            if (loweredGainDb >= currentGainDb) {
                                continue;
                            }
                            adjustedGainsDb.put(id, loweredGainDb);
                            loweredGainsDb.put(id, loweredGainDb);
                        }
                        if (loweredGainsDb.isEmpty()) {
                            throw new IllegalStateException(
                                    "Limiter bleibt trotz minimalem Ausgangs-Gain aktiv (" +
                                            String.join(", ", activeIds) + ")."
                            );
                        }
                        publishAutoLimitStatus(
                                "GAIN_CLEARING",
                                true,
                                92,
                                "LIMIT bleibt bei +20 dB aktiv: senke Gain für " +
                                        String.join(", ", loweredGainsDb.keySet()) + " …",
                                peaksDb,
                                thresholdsDb,
                                activeIds,
                                null,
                                false
                        );
                        setGainsReliably(loweredGainsDb, appliedGainIds);
                        activeIds = observeActiveLimiters(new LinkedHashSet<>(targetIds));
                        continue;
                    }

                    clearanceRound++;
                    renewServerAutoLimitLease(leaseToken);
                    checkAutoLimitCancelled();
                    publishAutoLimitStatus(
                            "CLEARING",
                            true,
                            Math.min(94, 74 + clearanceRound * 2),
                            "LIMIT auf " + String.join(", ", changedIds) +
                                    ": Schwellen gemeinsam erhöhen …",
                            peaksDb,
                            raised,
                            activeIds,
                            null,
                            false
                    );
                    setLimitersReliably(
                            limiterSettingsFor(
                                    changedIds,
                                    raised,
                                    AutoLimitPlanner.ATTACK_MS,
                                    AutoLimitPlanner.RELEASE_MS
                            ),
                            appliedIds
                    );
                    thresholdsDb = new LinkedHashMap<>(raised);
                    activeIds = observeActiveLimiters(new LinkedHashSet<>(targetIds));
                }

                if (!AutoLimitPlanner.ALL_OUTPUTS.equals(targetMode)) {
                    break;
                }

                double commonThresholdDb = AutoLimitPlanner.commonThreshold(thresholdsDb);
                List<String> unsynchronizedIds = new ArrayList<>();
                for (String id : targetIds) {
                    if (!thresholdsDb.get(id).equals(commonThresholdDb)) {
                        unsynchronizedIds.add(id);
                    }
                }
                if (unsynchronizedIds.isEmpty()) {
                    break;
                }

                renewServerAutoLimitLease(leaseToken);
                checkAutoLimitCancelled();
                publishAutoLimitStatus(
                        "SYNCHRONIZING",
                        true,
                        95,
                        "Setze gemeinsame Grenze " + commonThresholdDb + " dB auf " +
                                String.join(", ", unsynchronizedIds) + " …",
                        peaksDb,
                        thresholdsDb,
                        activeIds,
                        null,
                        false
                );
                Map<String, Double> synchronizedThresholds = new LinkedHashMap<>();
                unsynchronizedIds.forEach(id -> synchronizedThresholds.put(id, commonThresholdDb));
                setLimitersReliably(
                        limiterSettingsFor(
                                unsynchronizedIds,
                                synchronizedThresholds,
                                AutoLimitPlanner.ATTACK_MS,
                                AutoLimitPlanner.RELEASE_MS
                        ),
                        appliedIds
                );
                for (String id : unsynchronizedIds) {
                    thresholdsDb.put(id, commonThresholdDb);
                }
                activeIds = observeActiveLimiters(new LinkedHashSet<>(targetIds));
            }

            if (AutoLimitPlanner.ALL_OUTPUTS.equals(targetMode)) {
                double knownFreeThresholdDb =
                        AutoLimitPlanner.commonThreshold(thresholdsDb);
                Double activeProbeThresholdDb = null;
                for (int tighteningRound = 1;
                     tighteningRound <= TIGHTENING_MAX_ROUNDS;
                     tighteningRound++) {
                    checkAutoLimitCancelled();
                    double probeThresholdDb =
                            AutoLimitPlanner.lowerCoarseProbeThreshold(knownFreeThresholdDb);
                    if (probeThresholdDb >= knownFreeThresholdDb) {
                        break;
                    }

                    Map<String, Double> probeThresholds =
                            commonThresholds(targetIds, probeThresholdDb);
                    renewServerAutoLimitLease(leaseToken);
                    publishAutoLimitStatus(
                            "TIGHTENING",
                            true,
                            96,
                            "Suche erstes LIMIT-Flackern bei " + probeThresholdDb + " dB …",
                            peaksDb,
                            probeThresholds,
                            activeIds,
                            null,
                            false
                    );
                    setLimitersReliably(
                            limiterSettingsFor(
                                    targetIds,
                                    probeThresholds,
                                    AutoLimitPlanner.ATTACK_MS,
                                    AutoLimitPlanner.RELEASE_MS
                            ),
                            appliedIds
                    );
                    thresholdsDb = new LinkedHashMap<>(probeThresholds);
                    activeIds = observeLimiterFlicker(new LinkedHashSet<>(targetIds));
                    if (activeIds.isEmpty()) {
                        knownFreeThresholdDb = probeThresholdDb;
                        continue;
                    }
                    activeProbeThresholdDb = probeThresholdDb;
                    break;
                }

                if (activeProbeThresholdDb != null) {
                    while (knownFreeThresholdDb - activeProbeThresholdDb
                            > AutoLimitPlanner.TIGHTENING_STEP_DB) {
                        checkAutoLimitCancelled();
                        double fineProbeThresholdDb = AutoLimitPlanner.midpointThreshold(
                                activeProbeThresholdDb,
                                knownFreeThresholdDb
                        );
                        if (fineProbeThresholdDb <= activeProbeThresholdDb
                                || fineProbeThresholdDb >= knownFreeThresholdDb) {
                            break;
                        }
                        Map<String, Double> fineProbeThresholds =
                                commonThresholds(targetIds, fineProbeThresholdDb);
                        renewServerAutoLimitLease(leaseToken);
                        publishAutoLimitStatus(
                                "TIGHTENING",
                                true,
                                97,
                                "Feinsuche nach erstem LIMIT-Flackern bei " +
                                        fineProbeThresholdDb + " dB …",
                                peaksDb,
                                fineProbeThresholds,
                                activeIds,
                                null,
                                false
                        );
                        setLimitersReliably(
                                limiterSettingsFor(
                                        targetIds,
                                        fineProbeThresholds,
                                        AutoLimitPlanner.ATTACK_MS,
                                        AutoLimitPlanner.RELEASE_MS
                                ),
                                appliedIds
                        );
                        thresholdsDb = new LinkedHashMap<>(fineProbeThresholds);
                        activeIds = observeLimiterFlicker(
                                new LinkedHashSet<>(targetIds)
                        );
                        if (activeIds.isEmpty()) {
                            knownFreeThresholdDb = fineProbeThresholdDb;
                        } else {
                            activeProbeThresholdDb = fineProbeThresholdDb;
                        }
                    }

                    double finalThresholdDb = Math.max(
                            knownFreeThresholdDb,
                            AutoLimitPlanner.freeThresholdAboveActiveProbe(
                                    activeProbeThresholdDb,
                                    initialStatus.headroomDb()
                            )
                    );
                    while (true) {
                        checkAutoLimitCancelled();
                        Map<String, Double> finalThresholds =
                                commonThresholds(targetIds, finalThresholdDb);
                        renewServerAutoLimitLease(leaseToken);
                        publishAutoLimitStatus(
                                "SECURING",
                                true,
                                98,
                                "Setze schnelle Grenze " + finalThresholdDb +
                                        " dB mit " + initialStatus.headroomDb() +
                                        " dB Reserve …",
                                peaksDb,
                                finalThresholds,
                                activeIds,
                                null,
                                false
                        );
                        setLimitersReliably(
                                limiterSettingsFor(
                                        targetIds,
                                        finalThresholds,
                                        AutoLimitPlanner.ATTACK_MS,
                                        AutoLimitPlanner.RELEASE_MS
                                ),
                                appliedIds
                        );
                        thresholdsDb = new LinkedHashMap<>(finalThresholds);
                        activeIds = observeLimiterFlicker(
                                new LinkedHashSet<>(targetIds)
                        );
                        if (activeIds.isEmpty()) {
                            break;
                        }

                        double raisedThresholdDb =
                                AutoLimitPlanner.freeThresholdAboveActiveProbe(
                                        finalThresholdDb,
                                        0.0
                                );
                        if (raisedThresholdDb <= finalThresholdDb) {
                            throw new IllegalStateException(
                                    "Limiter bleibt an der maximalen Einsatzgrenze aktiv."
                            );
                        }
                        finalThresholdDb = raisedThresholdDb;
                    }
                }
            }

            autoLimitUndoSnapshot = new AutoLimitUndoSnapshot(
                    jobId,
                    System.currentTimeMillis(),
                    previousById,
                    previousGainsDb
            );
            publishAutoLimitStatus(
                    "COMPLETED",
                    false,
                    100,
                    adjustedGainsDb.isEmpty()
                            ? "Auto-Limit abgeschlossen: LIMIT beim Referenzpegel frei."
                            : "Auto-Limit abgeschlossen: LIMIT frei; Gain angepasst: " +
                                    adjustedGainsDb,
                    peaksDb,
                    thresholdsDb,
                    Set.of(),
                    System.currentTimeMillis(),
                    false
            );
            LOG.info("Server Auto-Limit {} completed: {}", jobId, thresholdsDb);
        } catch (Exception error) {
            boolean cancelled = autoLimitCancelRequested || error instanceof InterruptedException;
            Thread.interrupted();
            if (!appliedIds.isEmpty() || !appliedGainIds.isEmpty()) {
                publishAutoLimitStatus(
                        "ROLLING_BACK",
                        true,
                        autoLimitStatus.progressPercent(),
                        "Vorherige Limiterwerte werden wiederhergestellt …",
                        peaksDb,
                        thresholdsDb,
                        activeIds,
                        null,
                        false
                );
                boolean limitersRestored = appliedIds.isEmpty()
                        || restoreLimiters(previousById, appliedIds, leaseToken);
                boolean gainsRestored = appliedGainIds.isEmpty()
                        || restoreOutputGains(previousGainsDb, appliedGainIds);
                rollbackPerformed = limitersRestored && gainsRestored;
            }
            String message = cancelled
                    ? "Auto-Limit abgebrochen; vorherige Werte wurden wiederhergestellt."
                    : "Auto-Limit fehlgeschlagen: " + safeMessage(error);
            publishAutoLimitStatus(
                    cancelled ? "CANCELLED" : "FAILED",
                    false,
                    autoLimitStatus.progressPercent(),
                    message,
                    peaksDb,
                    thresholdsDb,
                    activeIds,
                    System.currentTimeMillis(),
                    rollbackPerformed
            );
            if (cancelled) {
                LOG.info("Server Auto-Limit {} cancelled", jobId);
            } else {
                LOG.warn("Server Auto-Limit {} failed", jobId, error);
            }
        } finally {
            releaseAutoLimitLease(leaseToken);
            synchronized (this) {
                autoLimitFuture = null;
                autoLimitCancelRequested = false;
            }
        }
    }

    private void runAutoLimitUndo(
            String jobId,
            AutoLimitUndoSnapshot snapshot,
            String leaseToken
    ) {
        try {
            publishAutoLimitStatus(
                    "UNDOING_LIMITERS",
                    true,
                    10,
                    "Stelle die acht Limiterwerte vor Auto-Limit wieder her …",
                    Map.of(),
                    snapshot.thresholdsDb(),
                    Set.of(),
                    null,
                    false
            );
            boolean restored = restoreAutoLimitSnapshot(snapshot, leaseToken);
            if (!restored) {
                throw new IllegalStateException(
                        "Nicht alle vorherigen Limiter- und Gain-Werte konnten wiederhergestellt werden."
                );
            }
            synchronized (this) {
                if (autoLimitUndoSnapshot == snapshot) {
                    autoLimitUndoSnapshot = null;
                }
            }
            publishAutoLimitStatus(
                    "UNDONE",
                    false,
                    100,
                    "Auto-Limit wurde rückgängig gemacht; vorherige Limiter- und Gain-Werte sind aktiv.",
                    Map.of(),
                    snapshot.thresholdsDb(),
                    Set.of(),
                    System.currentTimeMillis(),
                    true
            );
            LOG.info("Server Auto-Limit undo {} restored snapshot from {}", jobId, snapshot.sourceJobId());
        } catch (Exception error) {
            Thread.interrupted();
            publishAutoLimitStatus(
                    "UNDO_FAILED",
                    false,
                    autoLimitStatus.progressPercent(),
                    "Auto-Limit rückgängig fehlgeschlagen: " + safeMessage(error),
                    Map.of(),
                    snapshot.thresholdsDb(),
                    Set.of(),
                    System.currentTimeMillis(),
                    false
            );
            LOG.warn("Server Auto-Limit undo {} failed", jobId, error);
        } finally {
            releaseAutoLimitLease(leaseToken);
            synchronized (this) {
                autoLimitFuture = null;
                autoLimitCancelRequested = false;
            }
        }
    }

    private MeterStateDto awaitFreshAutoLimitMeters(long newerThanEpochMs) throws InterruptedException {
        long deadlineEpochMs = System.currentTimeMillis() + 15_000L;
        while (true) {
            checkAutoLimitCancelled();
            MeterStateDto meters = dtoMapper.meters();
            Long observedAtEpochMs = meters.observedAtEpochMs();
            if (meters.rawPayloadConfirmedFromDevice()
                    && observedAtEpochMs != null
                    && observedAtEpochMs > newerThanEpochMs
                    && System.currentTimeMillis() - observedAtEpochMs <= 3_000L) {
                return meters;
            }
            if (System.currentTimeMillis() >= deadlineEpochMs) {
                throw new IllegalStateException("Keine frischen DSP-Meterdaten verfügbar.");
            }
            Thread.sleep(250L);
        }
    }

    private List<LimiterDto> readConfirmedAutoLimiters() throws Exception {
        publishAutoLimitStatus(
                "INITIALIZING",
                true,
                0,
                "Limiterwerte werden aus dem Server-Cache vorbereitet …",
                Map.of(),
                Map.of(),
                Set.of(),
                null,
                false
        );
        SettingsCacheDto cacheStatus = settingsCache.status();
        if (!cacheStatus.ready()) {
            throw new IllegalStateException(
                    "DSP-Einstellungscache ist nicht vollständig ("
                            + cacheStatus.cachedBlockCount() + "/"
                            + cacheStatus.expectedBlockCount()
                            + "). Zuerst /refresh ausführen."
            );
        }

        List<LimiterDto> limiters = sortedLimiters();
        List<String> missingIds = limiters.stream()
                .filter(limiter -> !isConfirmedLimiterSnapshot(limiter))
                .map(LimiterDto::id)
                .toList();
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException(
                    "Dekodierte Limiterwerte fehlen im Cache für "
                            + String.join(", ", missingIds) + "."
            );
        }

        rememberLimiterKnees.run();
        return limiters;
    }

    private List<LimiterDto> sortedLimiters() {
        return dtoMapper.limiters().stream()
                .sorted((left, right) -> Integer.compare(left.index(), right.index()))
                .toList();
    }

    private boolean isConfirmedLimiterSnapshot(LimiterDto limiter) {
        return limiter.thresholdDb() != null
                && limiter.attackMs() != null
                && limiter.releaseMs() != null
                && limiter.kneeDb() != null
                && limiter.thresholdConfirmedFromDevice()
                && limiter.attackConfirmedFromDevice()
                && limiter.releaseConfirmedFromDevice()
                && limiter.kneeConfirmedFromDevice();
    }

    private ChannelDto readConfirmedOutputGain(String outputId) throws Exception {
        ChannelDto channel = channel(outputId);
        if (channel.gainDb() != null && channel.gainConfirmedFromDevice()) {
            return channel;
        }
        String block = outputGainBlock(outputId);
        for (int attempt = 1; attempt <= 12; attempt++) {
            checkAutoLimitCancelled();
            blockReader.read(block);
            channel = channel(outputId);
            if (channel.gainDb() != null && channel.gainConfirmedFromDevice()) {
                return channel;
            }
        }
        throw new IllegalStateException("Ausgangs-Gain fehlt für " + outputId + ".");
    }

    private String outputGainBlock(String outputId) {
        return switch (outputId) {
            case "out1" -> "0D";
            case "out2" -> "0F";
            case "out3" -> "11";
            case "out4" -> "13";
            case "out5" -> "16";
            case "out6" -> "18";
            case "out7" -> "1A";
            case "out8" -> "1C";
            default -> throw new IllegalArgumentException("Unknown output: " + outputId);
        };
    }

    private void rejectInputClipping(MeterStateDto meters) {
        List<String> clippingInputs = meters.channels().stream()
                .filter(meter -> meter.id().startsWith("in"))
                .filter(MeterDto::clipThresholdConfirmed)
                .filter(meter -> Boolean.TRUE.equals(meter.clipActive()))
                .map(MeterDto::id)
                .toList();
        if (!clippingInputs.isEmpty()) {
            throw new IllegalStateException(
                    "Input-Clipping erkannt (" + String.join(", ", clippingInputs) +
                            "). Eingangspegel reduzieren."
            );
        }
    }

    private Set<String> observeActiveLimiters(Set<String> targetIds) throws InterruptedException {
        return observeActiveLimiters(targetIds, false);
    }

    private Set<String> observeLimiterFlicker(Set<String> targetIds)
            throws InterruptedException {
        return observeActiveLimiters(targetIds, true);
    }

    private Set<String> observeActiveLimiters(
            Set<String> targetIds,
            boolean returnOnFirstFlicker
    ) throws InterruptedException {
        LinkedHashSet<String> activeIds = new LinkedHashSet<>();
        LinkedHashSet<String> confirmedIds = new LinkedHashSet<>();
        long lastObservationEpochMs = System.currentTimeMillis();
        for (int sample = 0; sample < 5; sample++) {
            checkAutoLimitCancelled();
            Thread.sleep(sample == 0 ? 900L : 650L);
            MeterStateDto meters = awaitFreshAutoLimitMeters(lastObservationEpochMs);
            lastObservationEpochMs = meters.observedAtEpochMs();
            rejectInputClipping(meters);
            for (MeterDto meter : meters.channels()) {
                if (!targetIds.contains(meter.id()) || !meter.limiterConfirmedFromDevice()) {
                    continue;
                }
                confirmedIds.add(meter.id());
                if (Boolean.TRUE.equals(meter.limiterActive())) {
                    activeIds.add(meter.id());
                }
            }
            if (returnOnFirstFlicker
                    && !activeIds.isEmpty()
                    && confirmedIds.containsAll(targetIds)) {
                return activeIds;
            }
        }
        LinkedHashSet<String> missingIds = new LinkedHashSet<>(targetIds);
        missingIds.removeAll(confirmedIds);
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException(
                    "LIMIT-Rückmeldung fehlt für " + String.join(", ", missingIds) + "."
            );
        }
        return activeIds;
    }

    private synchronized boolean restoreLimiters(
            Map<String, LimiterSettings> previousById,
            Set<String> appliedIds,
            String leaseToken
    ) {
        boolean[] restoredAll = {true};
        renewAutoLimitLease(leaseToken, AutoLimitLeaseManager.MAX_TTL_MS);
        List<String> reverseIds = new ArrayList<>(appliedIds);
        java.util.Collections.reverse(reverseIds);
        rememberLimiterKnees.run();
        try {
            transactions.withMetersPaused(() -> {
                for (String id : reverseIds) {
                    LimiterSettings previous = previousById.get(id);
                    if (previous == null) {
                        restoredAll[0] = false;
                        continue;
                    }
                    try {
                        DspChannel output = DspChannel.parse(id);
                        writeAutoLimitLimiter(output, previous);
                    } catch (Exception restoreError) {
                        restoredAll[0] = false;
                        LOG.warn("Could not restore limiter {}", id, restoreError);
                    }
                }
                return null;
            });
        } catch (Exception transactionError) {
            restoredAll[0] = false;
            LOG.warn("Could not complete limiter rollback transaction", transactionError);
        }
        return restoredAll[0];
    }

    private synchronized boolean restoreAutoLimitSnapshot(
            AutoLimitUndoSnapshot snapshot,
            String leaseToken
    ) {
        LinkedHashSet<String> limiterIds = new LinkedHashSet<>(snapshot.limitersById().keySet());
        LinkedHashSet<String> gainIds = new LinkedHashSet<>(snapshot.outputGainsDb().keySet());
        boolean limitersRestored = restoreLimiters(
                snapshot.limitersById(),
                limiterIds,
                leaseToken
        );
        boolean gainsRestored = gainIds.isEmpty()
                || restoreOutputGains(snapshot.outputGainsDb(), gainIds);
        return limitersRestored && gainsRestored;
    }

    private synchronized boolean restoreOutputGains(
            Map<String, Double> previousGainsDb,
            Set<String> appliedGainIds
    ) {
        boolean[] restoredAll = {true};
        List<String> reverseIds = new ArrayList<>(appliedGainIds);
        java.util.Collections.reverse(reverseIds);
        try {
            transactions.withMetersPaused(() -> {
                for (String id : reverseIds) {
                    Double previousGainDb = previousGainsDb.get(id);
                    if (previousGainDb == null) {
                        restoredAll[0] = false;
                        continue;
                    }
                    try {
                        gainWriter.write(
                                DspChannel.parse(id),
                                previousGainDb,
                                "auto-limit rollback gain tx repeated"
                        );
                    } catch (Exception restoreError) {
                        restoredAll[0] = false;
                        LOG.warn("Could not restore output gain {}", id, restoreError);
                    }
                }
                return null;
            });
        } catch (Exception transactionError) {
            restoredAll[0] = false;
            LOG.warn("Could not complete output-gain rollback transaction", transactionError);
        }
        return restoredAll[0];
    }

    private Map<String, LimiterSettings> limiterSettingsFor(
            List<String> outputIds,
            Map<String, Double> thresholdsDb,
            double attackMs,
            double releaseMs
    ) {
        Map<String, LimiterSettings> settings = new LinkedHashMap<>();
        for (String id : outputIds) {
            settings.put(id, new LimiterSettings(thresholdsDb.get(id), attackMs, releaseMs));
        }
        return settings;
    }

    private Map<String, Double> commonThresholds(
            List<String> outputIds,
            double thresholdDb
    ) {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        outputIds.forEach(id -> thresholds.put(id, thresholdDb));
        return thresholds;
    }

    private synchronized void setLimitersReliably(
            Map<String, LimiterSettings> settingsById,
            Set<String> appliedIds
    ) throws Exception {
        rememberLimiterKnees.run();
        transactions.withMetersPaused(() -> {
            for (Map.Entry<String, LimiterSettings> entry : settingsById.entrySet()) {
                checkAutoLimitCancelled();
                LimiterSettings settings = entry.getValue();
                // The core waits for the matching DSP ACK before this returns.
                // Runtime LIMIT bits provide the later closed-loop verification.
                writeAutoLimitLimiter(DspChannel.parse(entry.getKey()), settings);
                appliedIds.add(entry.getKey());
            }
            return null;
        });
    }

    private void writeAutoLimitLimiter(
            DspChannel output,
            LimiterSettings settings
    ) throws Exception {
        limiterWriter.write(
                output,
                settings.thresholdDb(),
                settings.attackMs(),
                settings.releaseMs(),
                "auto-limit tx"
        );
    }


    private synchronized void setGainsReliably(
            Map<String, Double> gainsDb,
            Set<String> appliedGainIds
    ) throws Exception {
        transactions.withMetersPaused(() -> {
            for (Map.Entry<String, Double> entry : gainsDb.entrySet()) {
                checkAutoLimitCancelled();
                DspChannel output = DspChannel.parse(entry.getKey());
                gainWriter.write(output, entry.getValue(), "auto-limit gain tx repeated");
                appliedGainIds.add(entry.getKey());
            }
            return null;
        });
    }

    private void renewServerAutoLimitLease(String leaseToken) {
        AutoLimitLeaseDto renewed = renewAutoLimitLease(
                leaseToken, AutoLimitLeaseManager.MAX_TTL_MS);
        if (!renewed.acquired()) {
            throw new IllegalStateException("Server Auto-Limit lease was lost.");
        }
    }

    private void checkAutoLimitCancelled() throws InterruptedException {
        if (autoLimitCancelRequested || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Auto-Limit cancellation requested.");
        }
    }

    private synchronized void publishAutoLimitStatus(
            String phase,
            boolean running,
            int progressPercent,
            String message,
            Map<String, Double> measuredPeaksDb,
            Map<String, Double> appliedThresholdsDb,
            Set<String> activeLimiterIds,
            Long finishedAtEpochMs,
            boolean rollbackPerformed
    ) {
        AutoLimitStatusDto previous = autoLimitStatus;
        autoLimitStatus = withCurrentAutoLimitUndo(new AutoLimitStatusDto(
                previous.jobId(),
                phase,
                running,
                progressPercent,
                previous.targetMode(),
                previous.headroomDb(),
                previous.measurementMs(),
                message,
                measuredPeaksDb,
                appliedThresholdsDb,
                activeLimiterIds.stream().sorted().toList(),
                previous.startedAtEpochMs(),
                finishedAtEpochMs,
                rollbackPerformed,
                false,
                null,
                null
        ));
    }

    private AutoLimitStatusDto withCurrentAutoLimitUndo(AutoLimitStatusDto status) {
        AutoLimitUndoSnapshot snapshot = autoLimitUndoSnapshot;
        return new AutoLimitStatusDto(
                status.jobId(),
                status.phase(),
                status.running(),
                status.progressPercent(),
                status.targetMode(),
                status.headroomDb(),
                status.measurementMs(),
                status.message(),
                status.measuredPeaksDb(),
                status.appliedThresholdsDb(),
                status.activeLimiterIds(),
                status.startedAtEpochMs(),
                status.finishedAtEpochMs(),
                status.rollbackPerformed(),
                snapshot != null && !status.running(),
                snapshot == null ? null : snapshot.sourceJobId(),
                snapshot == null ? null : snapshot.createdAtEpochMs()
        );
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }


    private record LimiterSettings(double thresholdDb, double attackMs, double releaseMs) {
    }

    private record AutoLimitUndoSnapshot(
            String sourceJobId,
            long createdAtEpochMs,
            Map<String, LimiterSettings> limitersById,
            Map<String, Double> outputGainsDb
    ) {
        private AutoLimitUndoSnapshot {
            limitersById = Map.copyOf(new LinkedHashMap<>(limitersById));
            outputGainsDb = Map.copyOf(new LinkedHashMap<>(outputGainsDb));
        }

        private Map<String, Double> thresholdsDb() {
            Map<String, Double> thresholds = new LinkedHashMap<>();
            limitersById.forEach((id, settings) -> thresholds.put(id, settings.thresholdDb()));
            return Map.copyOf(thresholds);
        }
    }


    private ChannelDto channel(String channelId) {
        return dtoMapper.channel(controller.state().channel(DspChannel.parse(channelId)));
    }

    @FunctionalInterface
    interface BlockReader {
        void read(String blockIndexHex) throws Exception;
    }

    @FunctionalInterface
    interface LimiterWriter {
        void write(
                DspChannel output,
                double thresholdDb,
                double attackMs,
                double releaseMs,
                String source
        ) throws Exception;
    }

    @FunctionalInterface
    interface GainWriter {
        void write(DspChannel channel, double gainDb, String source) throws Exception;
    }

}

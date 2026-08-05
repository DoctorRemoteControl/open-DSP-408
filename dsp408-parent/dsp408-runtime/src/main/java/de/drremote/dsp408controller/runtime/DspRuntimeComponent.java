package de.drremote.dsp408controller.runtime;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.BlockDto;
import de.drremote.dsp408.api.AutoLimitLeaseDto;
import de.drremote.dsp408.api.AutoLimitStatusDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.DeviceInfoDto;
import de.drremote.dsp408.api.DspInstanceDto;
import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.InputCompressorDto;
import de.drremote.dsp408.api.InputGateDto;
import de.drremote.dsp408.api.LimiterDto;
import de.drremote.dsp408.api.MatrixCrosspointDto;
import de.drremote.dsp408.api.MatrixOutputDto;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.PeqBandDto;
import de.drremote.dsp408.api.PeqChannelDto;
import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408.api.PresetSnapshotDto;
import de.drremote.dsp408.api.PresetStoreResultDto;
import de.drremote.dsp408.api.SettingsCacheDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408.api.TrafficStatusDto;
import de.drremote.dsp408controller.command.DspCommandProcessor;
import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.net.DspTrafficSnapshot;
import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.CrossoverFilterState;
import de.drremote.dsp408controller.core.state.InputGateState;
import de.drremote.dsp408controller.core.state.LimiterState;
import de.drremote.dsp408controller.core.state.MatrixOutputState;
import de.drremote.dsp408controller.core.state.MeterChannelState;
import de.drremote.dsp408controller.core.state.MeterLevelDecoder;
import de.drremote.dsp408controller.core.state.MeterState;
import de.drremote.dsp408controller.core.state.PeqBandState;
import de.drremote.dsp408controller.util.Hex;

@Component(
        service = DspService.class,
        immediate = true,
        configurationPid = "de.drremote.dsp408controller"
)
@Designate(ocd = Dsp408Configuration.class)
public final class DspRuntimeComponent implements DspService {
    private static final Logger LOG = LoggerFactory.getLogger(DspRuntimeComponent.class);
    private static final long AUTO_LIMIT_LEASE_DEFAULT_MS = 30_000L;
    private static final long AUTO_LIMIT_LEASE_MIN_MS = 5_000L;
    private static final long AUTO_LIMIT_LEASE_MAX_MS = 120_000L;
    private static final int BLOCK_READ_REQUEST_ATTEMPTS = 4;
    private static final long CONTROL_QUIET_MS = 800L;
    private static final long BLOCK_RESPONSE_TIMEOUT_MS = 350L;
    private static final long BLOCK_RESPONSE_POLL_MS = 50L;
    private static final long PRESET_STORE_SETTLE_MS = 8_000L;
    private static final int SETTINGS_SYNC_MAX_DUMP_PASSES = 8;
    private static final int SETTINGS_SYNC_BLOCK_ATTEMPTS_PER_PASS = 4;
    private static final long SETTINGS_SYNC_TIMEOUT_MS = 90_000L;
    private static final long SETTINGS_SYNC_SETTLE_MS = 300L;
    private static final int AUTO_LIMIT_TIGHTENING_MAX_ROUNDS = 16;
    private static final int CACHED_WRITE_REPEATS = 2;
    private static final long CACHED_WRITE_REPEAT_DELAY_MS = 350L;
    private static final long CONNECTION_WATCHDOG_INTERVAL_MS = 1_000L;
    private static final long DSP_RECONNECT_INITIAL_DELAY_MS = 25_000L;
    private static final long DSP_RECONNECT_MAX_DELAY_MS = 60_000L;

    private final boolean manageDevicePool;
    private final DspController controller = new DspController();
    private final Object confirmedWriteLock = new Object();
    private final VolumeRoomHandler volumeRoomHandler = new VolumeRoomHandler(this);
    private final ExecutorService autoLimitExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "dsp408-auto-limit");
        thread.setDaemon(true);
        return thread;
    });

    private final DspCommandProcessor shellProcessor =
            new DspCommandProcessor(
                    controller,
                    this::currentConfig,
                    this::log,
                    DspCommandProcessor.CommandMode.CLI
            );

    private final DspCommandProcessor matrixProcessor =
            new DspCommandProcessor(
                    controller,
                    this::currentConfig,
                    this::log,
                    DspCommandProcessor.CommandMode.MATRIX
            );

    private volatile Dsp408Configuration configuration;
    private volatile String instanceId = "main";
    private volatile Map<String, DspRuntimeComponent> devices = Map.of();
    private String autoLimitLeaseOwner;
    private String autoLimitLeaseToken;
    private long autoLimitLeaseExpiresAtEpochMs;
    private volatile AutoLimitStatusDto autoLimitStatus = AutoLimitStatusDto.idle();
    private volatile Future<?> autoLimitFuture;
    private volatile boolean autoLimitCancelRequested;
    private volatile AutoLimitUndoSnapshot autoLimitUndoSnapshot;
    private volatile SettingsCacheDto settingsCacheStatus = SettingsCacheDto.empty();
    private final DspReconnectSchedule reconnectSchedule = new DspReconnectSchedule(
            DSP_RECONNECT_INITIAL_DELAY_MS,
            DSP_RECONNECT_MAX_DELAY_MS
    );
    private volatile ScheduledExecutorService connectionWatchdogExecutor;
    private volatile boolean reconnectDesired;
    private volatile boolean reconnectAttemptRunning;
    private long settingsCacheGeneration;
    private final Map<DspChannel, Integer> confirmedLimiterKnees = new EnumMap<>(DspChannel.class);

    public DspRuntimeComponent() {
        this(true);
    }

    private DspRuntimeComponent(boolean manageDevicePool) {
        this.manageDevicePool = manageDevicePool;
    }

    @Activate
    void activate(Dsp408Configuration configuration) {
        this.configuration = configuration;
        this.instanceId = normalizeDspId(configuration.dsp_id());
        controller.setPreferredLibraryType(configuration.dsp_library());
        if (manageDevicePool) {
            rebuildDevicePool(configuration);
        }
        reconnectDesired = configuration.auto_connect();
        startConnectionWatchdog();

        LOG.info("DSP408 {} activated ({}:{})", instanceId, configuration.dsp_ip(), configuration.dsp_port());

        if (configuration.auto_connect()) {
            try {
                connect();
            } catch (Exception e) {
                LOG.error("Auto-connect failed", e);
            }
        }
    }

    @Modified
    void modified(Dsp408Configuration configuration) {
        Dsp408Configuration oldConfiguration = this.configuration;
        String oldId = instanceId;
        boolean reconnectNeeded =
                oldConfiguration != null
                        && controller.isConnected()
                        && connectionChanged(oldConfiguration, configuration);

        this.configuration = configuration;
        this.instanceId = normalizeDspId(configuration.dsp_id());
        controller.setPreferredLibraryType(configuration.dsp_library());
        if (manageDevicePool) {
            rebuildDevicePool(configuration);
        }

        if (!oldId.equals(instanceId)) {
            LOG.info("DSP408 instance id changed: {} -> {}", oldId, instanceId);
        }

        if (reconnectNeeded) {
            try {
                reconnect();
            } catch (Exception e) {
                LOG.error("Reconnect after config change failed", e);
            }
        } else if (controller.isConnected()
                && (oldConfiguration == null
                || oldConfiguration.meter_poll_interval_ms() != configuration.meter_poll_interval_ms())) {
            startCentralMeterPolling();
        }
    }

    @Deactivate
    void deactivate() {
        reconnectDesired = false;
        reconnectSchedule.cancel();
        stopConnectionWatchdog();
        closeDevicePool();
        autoLimitCancelRequested = true;
        Future<?> runningAutoLimit = autoLimitFuture;
        if (runningAutoLimit != null) {
            runningAutoLimit.cancel(true);
        }
        autoLimitExecutor.shutdownNow();
        try {
            controller.close();
        } catch (Exception e) {
            LOG.warn("Error while closing DSP controller", e);
        }
    }

    @Override
    public String dspId() {
        return instanceId;
    }

    @Override
    public synchronized List<DspInstanceDto> getDspInstances() {
        if (!manageDevicePool) {
            return List.of(toInstanceDto(true));
        }

        List<DspInstanceDto> instances = new ArrayList<>();
        instances.add(toInstanceDto(true));
        for (DspRuntimeComponent device : devices.values()) {
            instances.add(device.toInstanceDto(false));
        }
        return List.copyOf(instances);
    }

    @Override
    public DspService forDsp(String dspId) {
        String normalized = normalizeDspId(dspId);
        if (normalized.equals(instanceId)) {
            return this;
        }
        DspRuntimeComponent device = devices.get(normalized);
        if (device == null) {
            throw new IllegalArgumentException("Unknown DSP id: " + dspId);
        }
        return device;
    }

    @Override
    public synchronized String helpText() {
        return executeWithCapture(shellProcessor, "help", true);
    }

    @Override
    public synchronized String executeShell(String line) throws Exception {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        String lower = normalized.toLowerCase(Locale.ROOT);

        if (lower.equals("connect") || lower.equals("dsp connect") || lower.equals("!dsp connect")) {
            return connect();
        }
        if (lower.equals("disconnect") || lower.equals("dsp disconnect") || lower.equals("!dsp disconnect")) {
            disconnect();
            return "Disconnected.";
        }
        if (lower.equals("reconnect") || lower.equals("dsp reconnect") || lower.equals("!dsp reconnect")) {
            return reconnect();
        }

        return executeWithCapture(shellProcessor, normalized, true);
    }

    @Override
    public synchronized String executeMatrix(String line, boolean isAdmin) throws Exception {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return executeWithCapture(matrixProcessor, normalized, isAdmin);
    }

    @Override
    public synchronized String executeVolumeRoom(String line) throws Exception {
        return volumeRoomHandler.handleVolumeRoom(line);
    }

    @Override
    public synchronized String connect() throws Exception {
        reconnectDesired = true;
        reconnectAttemptRunning = true;
        try {
            String message = connectNow();
            reconnectSchedule.recordSuccess();
            return message;
        } catch (Exception error) {
            controller.close();
            markSettingsCacheDisconnected();
            long retryInMs = reconnectSchedule.recordFailure(System.currentTimeMillis());
            LOG.warn(
                    "DSP408 {} connect failed; automatic retry in {} ms: {}",
                    instanceId,
                    retryInMs,
                    safeMessage(error)
            );
            throw error;
        } finally {
            reconnectAttemptRunning = false;
        }
    }

    private String connectNow() throws Exception {
        DspConnectionConfig config = currentConfig();
        controller.connect(config, this::log);

        SettingsCacheDto cache;
        try {
            cache = synchronizeSettingsCache("connect");
        } catch (Exception error) {
            LOG.warn("Initial DSP settings synchronization failed: {}", safeMessage(error));
            cache = settingsCacheStatus;
        }
        startCentralMeterPolling();

        return "Connected to " + config.ip() + ":" + config.port()
                + " (settings cache " + cache.cachedBlockCount()
                + "/" + cache.expectedBlockCount() + ", "
                + cache.phase().toLowerCase(Locale.ROOT) + ")";
    }

    @Override
    public synchronized String reconnect() throws Exception {
        reconnectDesired = true;
        autoLimitUndoSnapshot = null;
        controller.close();
        return connect();
    }

    @Override
    public synchronized void disconnect() {
        reconnectDesired = false;
        autoLimitUndoSnapshot = null;
        reconnectSchedule.cancel();
        controller.close();
        markSettingsCacheDisconnected();
    }

    @Override
    public synchronized boolean isConnected() {
        return controller.isConnected();
    }

    @Override
    public synchronized DeviceInfoDto getDeviceInfo() {
        return toDeviceInfoDto();
    }

    @Override
    public synchronized List<ChannelDto> getChannels() {
        return controller.state().allChannels().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public synchronized SettingsCacheDto getSettingsCacheStatus() {
        return settingsCacheStatus;
    }

    @Override
    public synchronized ChannelDto getChannel(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized List<MatrixOutputDto> getMatrixOutputs() {
        return toMatrixOutputDtos();
    }

    @Override
    public synchronized MatrixOutputDto getMatrixOutput(String outputId) {
        DspChannel output = DspChannel.parse(outputId);
        if (output.index() < DspChannel.OUT_1.index()) {
            throw new IllegalArgumentException("Output channel required: Out1..Out8");
        }
        return toMatrixOutputDto(controller.state().matrixOutput(output));
    }

    @Override
    public synchronized List<PeqChannelDto> getPeqChannels() {
        return toPeqChannelDtos();
    }

    @Override
    public synchronized PeqChannelDto getPeqChannel(String channelId) {
        return toPeqChannelDto(DspChannel.parse(channelId));
    }

    @Override
    public synchronized List<InputGateDto> getInputGates() {
        return toInputGateDtos();
    }

    @Override
    public synchronized List<InputCompressorDto> getInputCompressors() {
        return toInputCompressorDtos();
    }

    @Override
    public synchronized InputCompressorDto getInputCompressor(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        if (channel.index() > DspChannel.IN_D.index()) {
            throw new IllegalArgumentException("Input channel required: InA..InD");
        }
        return toInputCompressorDto(controller.state().compressor(channel));
    }

    @Override
    public synchronized List<LimiterDto> getLimiters() {
        return toLimiterDtos();
    }

    @Override
    public synchronized LimiterDto getLimiter(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        if (channel.index() < DspChannel.OUT_1.index()) {
            throw new IllegalArgumentException("Output channel required: Out1..Out8");
        }
        return toLimiterDto(controller.state().limiter(channel));
    }

    @Override
    public synchronized MeterStateDto getMeters() {
        return toMeterStateDto();
    }

    @Override
    public synchronized MeterDto getMeter(String channelId) {
        DspChannel channel = DspChannel.parse(channelId);
        return toMeterDto(controller.state().meterState().channel(channel));
    }

    @Override
    public synchronized TrafficStatusDto getTrafficStatus() {
        DspTrafficSnapshot traffic = controller.trafficSnapshot();
        return new TrafficStatusDto(
                controller.isConnected(),
                traffic.pendingCommands(),
                traffic.maxPendingCommands(),
                traffic.submittedCommands(),
                traffic.transmittedCommands(),
                traffic.supersededCommands(),
                traffic.meterTransmissions(),
                traffic.coalescedMeterRequests(),
                traffic.controlTransmissions(),
                traffic.writeRetries(),
                traffic.unacknowledgedWrites(),
                traffic.activePriority(),
                traffic.lastTransmitEpochMs() == 0L ? null : traffic.lastTransmitEpochMs(),
                traffic.centralMeterPolling(),
                traffic.meterPollIntervalMs(),
                controller.state().meterState().lastRuntimePayloadObservedAtEpochMs()
        );
    }

    @Override
    public synchronized AutoLimitLeaseDto getAutoLimitLease() {
        clearExpiredAutoLimitLease();
        return autoLimitLeaseDto(false, null);
    }

    @Override
    public synchronized AutoLimitLeaseDto acquireAutoLimitLease(String owner, long ttlMs) {
        clearExpiredAutoLimitLease();
        String normalizedOwner = owner == null ? "" : owner.trim();
        if (normalizedOwner.isBlank()) {
            throw new IllegalArgumentException("Auto-Limit owner is required.");
        }
        if (normalizedOwner.length() > 80) {
            normalizedOwner = normalizedOwner.substring(0, 80);
        }
        if (autoLimitLeaseToken != null) {
            return autoLimitLeaseDto(
                    false,
                    "Auto-Limit is already running on " + autoLimitLeaseOwner + "."
            );
        }

        autoLimitLeaseOwner = normalizedOwner;
        autoLimitLeaseToken = UUID.randomUUID().toString();
        autoLimitLeaseExpiresAtEpochMs = System.currentTimeMillis() + normalizeAutoLimitTtl(ttlMs);
        return autoLimitLeaseDto(true, "Auto-Limit lease acquired.");
    }

    @Override
    public synchronized AutoLimitLeaseDto renewAutoLimitLease(String token, long ttlMs) {
        clearExpiredAutoLimitLease();
        if (!ownsAutoLimitLeaseInternal(token)) {
            return autoLimitLeaseDto(false, "Auto-Limit lease is not owned by this client.");
        }
        autoLimitLeaseExpiresAtEpochMs = System.currentTimeMillis() + normalizeAutoLimitTtl(ttlMs);
        return autoLimitLeaseDto(true, "Auto-Limit lease renewed.");
    }

    @Override
    public synchronized AutoLimitLeaseDto releaseAutoLimitLease(String token) {
        clearExpiredAutoLimitLease();
        if (!ownsAutoLimitLeaseInternal(token)) {
            return autoLimitLeaseDto(false, "Auto-Limit lease is not owned by this client.");
        }
        clearAutoLimitLease();
        return new AutoLimitLeaseDto(
                false, true, null, null, null, "Auto-Limit lease released."
        );
    }

    @Override
    public synchronized boolean ownsAutoLimitLease(String token) {
        clearExpiredAutoLimitLease();
        return ownsAutoLimitLeaseInternal(token);
    }

    @Override
    public synchronized AutoLimitStatusDto getAutoLimitStatus() {
        return withCurrentAutoLimitUndo(autoLimitStatus);
    }

    @Override
    public synchronized AutoLimitStatusDto startAutoLimit(
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
        AutoLimitLeaseDto lease = acquireAutoLimitLease("DSP server Auto-Limit", AUTO_LIMIT_LEASE_MAX_MS);
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

    @Override
    public synchronized AutoLimitStatusDto cancelAutoLimit() {
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

    @Override
    public synchronized AutoLimitStatusDto undoAutoLimit() {
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
                AUTO_LIMIT_LEASE_MAX_MS
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

    @Override
    public synchronized ChannelDto setGain(String channelId, double db) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        writeGainAndUpdateCache(channel, db, "api gain tx repeated");
        if (channel.index() >= DspChannel.OUT_1.index()) {
            autoLimitUndoSnapshot = null;
        }
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized ChannelDto mute(String channelId) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.mute(channel);
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized ChannelDto unmute(String channelId) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.unmute(channel);
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized List<String> getCachedBlockIndices() {
        return controller.state().cachedBlockIndices().stream()
                .map(this::formatIndexHex)
                .toList();
    }

    @Override
    public synchronized BlockDto getCachedBlock(String blockIndexHex) {
        int index = parseHexIndex(blockIndexHex);
        byte[] data = controller.state().getBlock(index);
        return toBlockDto(index, data);
    }

    @Override
    public synchronized BlockDto readBlock(String blockIndexHex) throws Exception {
        int index = parseHexIndex(blockIndexHex);
        byte[] data = awaitFreshParameterBlock(index);
        promoteCompleteSettingsCache();
        return toBlockDto(index, data);
    }

    @Override
    public synchronized StateDto scanBlocks() throws Exception {
        synchronizeSettingsCache("explicit refresh");
        return toStateDto();
    }

    @Override
    public synchronized PresetSnapshotDto exportPresetSnapshot() throws Exception {
        synchronizeSettingsCache("preset JSON export");

        List<BlockDto> blocks = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index <= controller.maxParameterBlockIndex(); index++) {
            byte[] data = controller.state().getBlock(index);
            if (data == null) {
                throw new IllegalStateException(
                        "Preset export is incomplete: parameter block " + formatIndexHex(index) + " is missing."
                );
            }
            digest.update((byte) index);
            digest.update((byte) (data.length & 0xFF));
            digest.update(data);
            blocks.add(toBlockDto(index, data));
        }

        byte[] blockZero = controller.state().getBlock(0);
        return new PresetSnapshotDto(
                "fir408-preset-snapshot/1",
                dspId(),
                controller.libraryType(),
                System.currentTimeMillis(),
                decodeCurrentPresetName(blockZero),
                Hex.format(digest.digest()).replace(" ", "").toLowerCase(Locale.ROOT),
                toStateDto(),
                blocks
        );
    }

    @Override
    public synchronized RawTxDto sendRawHex(String payloadHex) throws Exception {
        byte[] payload = Hex.parseFlexible(payloadHex);
        byte[] frame = DspFrameCodec.encode(payload);
        controller.sendRaw(payload);
        autoLimitUndoSnapshot = null;
        markSettingsCacheStale(
                "A raw command was sent; refresh is required before cached settings are authoritative."
        );
        return new RawTxDto(
                Hex.format(payload),
                Hex.format(frame),
                payload.length,
                frame.length
        );
    }

    @Override
    public synchronized void loginPin(String pin) throws Exception {
        controller.login(pin);
    }

    @Override
    public synchronized RawTxDto loadPreset(String slotLabel) throws Exception {
        controller.loadPreset(slotLabel);
        autoLimitUndoSnapshot = null;
        Thread.sleep(1_500L);
        synchronizeSettingsCache("preset recall " + slotLabel);
        return buildRawTxDto(DspProtocol.loadPreset(slotLabel));
    }

    @Override
    public synchronized RawTxDto loadPreset(int presetIndex) throws Exception {
        controller.loadPreset(presetIndex);
        autoLimitUndoSnapshot = null;
        Thread.sleep(1_500L);
        synchronizeSettingsCache("preset recall " + presetIndex);
        return buildRawTxDto(DspProtocol.loadPreset(presetIndex));
    }

    @Override
    public synchronized PresetStoreResultDto storePreset(String slotLabel, String name) throws Exception {
        int presetIndex = DspProtocol.parsePresetSlot(slotLabel);
        byte[] namePayload = DspProtocol.buildPresetName(name);
        byte[] storePayload = DspProtocol.storePreset(presetIndex);
        String normalizedName = decodePresetNamePayload(namePayload);

        String confirmedName = withMeterPollingPaused(() -> {
            String readbackName = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                controller.setPresetName(normalizedName);
                controller.storePreset(presetIndex);
                controller.sendRaw(DspProtocol.keepAlive());
                // FIR408 writes the complete active configuration to flash.
                // During that operation it stays silent for roughly 8 seconds,
                // so an earlier read would be reported as a false failure.
                Thread.sleep(PRESET_STORE_SETTLE_MS);
                byte[] blockZero = awaitFreshParameterBlock(0);
                readbackName = decodeCurrentPresetName(blockZero);
                if (normalizedName.equals(readbackName)) {
                    return readbackName;
                }
                log("Preset store block-0 readback mismatch for " + slotLabel
                        + " attempt " + attempt + ": expected=" + normalizedName
                        + " actual=" + readbackName);
            }
            return readbackName;
        });

        boolean confirmed = normalizedName.equals(confirmedName);
        if (!confirmed) {
            throw new IllegalStateException(
                    "Preset " + slotLabel + " could not be confirmed after storing; read back name was "
                            + (confirmedName == null ? "missing" : confirmedName) + "."
            );
        }

        return new PresetStoreResultDto(
                String.format(Locale.ROOT, "U%02d", presetIndex),
                presetIndex,
                normalizedName,
                System.currentTimeMillis(),
                true,
                confirmedName,
                List.of(buildRawTxDto(namePayload), buildRawTxDto(storePayload))
        );
    }

    @Override
    public synchronized RawTxDto readPresetName(int presetIndex) throws Exception {
        controller.readPresetName(presetIndex);
        return buildRawTxDto(DspProtocol.readPresetName(presetIndex));
    }

    @Override
    public synchronized RawTxDto setDelayUnit(String unit) throws Exception {
        controller.setDelayUnit(unit);
        return buildRawTxDto(DspProtocol.buildDelayUnit(unit));
    }

    @Override
    public synchronized RawTxDto setChannelName(String channelId, String name) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setChannelName(channel, name);
        return buildRawTxDto(DspProtocol.buildChannelName(channel, name));
    }

    @Override
    public synchronized ChannelDto setPhase(String channelId, boolean inverted) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setPhase(channel, inverted);
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized ChannelDto setDelay(String channelId, double ms) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setDelay(channel, ms);
        return toDto(controller.state().channel(channel));
    }

    @Override
    public synchronized RawTxDto setMatrixRoute(String outputId, String inputId) throws Exception {
        return setMatrixRoutes(outputId, List.of(inputId));
    }

    @Override
    public synchronized RawTxDto setMatrixRoutes(String outputId, List<String> inputIds) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        if (inputIds == null) {
            throw new IllegalArgumentException("Matrix inputs are required");
        }
        List<DspChannel> inputs = inputIds.stream().map(DspChannel::parse).toList();
        controller.setMatrixRoutes(output, inputs);
        return buildRawTxDto(DspProtocol.buildMatrixRoutes(output, inputs));
    }

    @Override
    public synchronized RawTxDto setMatrixCrosspointGain(String outputId, String inputId, double db) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        DspChannel input = DspChannel.parse(inputId);
        controller.setMatrixCrosspointGain(output, input, db);
        return buildRawTxDto(DspProtocol.buildMatrixCrosspointGain(output, input, db));
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverSlope parsedSlope = bypass && (slope == null || slope.isBlank()) ? CrossoverSlope.LR_48 : CrossoverSlope.parse(slope);
        controller.setCrossoverHighPass(channel, frequencyHz, parsedSlope, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, parsedSlope, bypass));
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassFrequency(String channelId, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        controller.setCrossoverHighPassFrequency(channel, frequencyHz);
        CrossoverSlope slope = resolvedSlope(existing);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, slope, Boolean.TRUE.equals(existing.bypass())));
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassSlope(String channelId, String slope) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        CrossoverSlope parsedSlope = CrossoverSlope.parse(slope);
        controller.setCrossoverHighPassSlope(channel, parsedSlope);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, existing.frequencyHz(), parsedSlope, Boolean.TRUE.equals(existing.bypass())));
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassBypass(String channelId, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).highPass();
        controller.setCrossoverHighPassBypass(channel, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverHighPass(channel, existing.frequencyHz(), resolvedSlope(existing), bypass));
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverSlope parsedSlope = bypass && (slope == null || slope.isBlank()) ? CrossoverSlope.LR_48 : CrossoverSlope.parse(slope);
        controller.setCrossoverLowPass(channel, frequencyHz, parsedSlope, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, parsedSlope, bypass));
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassFrequency(String channelId, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        controller.setCrossoverLowPassFrequency(channel, frequencyHz);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, resolvedSlope(existing), Boolean.TRUE.equals(existing.bypass())));
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassSlope(String channelId, String slope) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        CrossoverSlope parsedSlope = CrossoverSlope.parse(slope);
        controller.setCrossoverLowPassSlope(channel, parsedSlope);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, existing.frequencyHz(), parsedSlope, Boolean.TRUE.equals(existing.bypass())));
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassBypass(String channelId, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        CrossoverFilterState existing = controller.state().crossover(channel).lowPass();
        controller.setCrossoverLowPassBypass(channel, bypass);
        return buildRawTxDto(DspProtocol.buildCrossoverLowPass(channel, existing.frequencyHz(), resolvedSlope(existing), bypass));
    }

    @Override
    public synchronized RawTxDto setFirProcessingMode(String outputId, String mode) throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        FirProcessingMode parsedMode = FirProcessingMode.parse(mode);
        controller.setFirProcessingMode(output, parsedMode);
        return buildRawTxDto(DspProtocol.buildFirProcessingMode(output, parsedMode));
    }

    @Override
    public synchronized RawTxDto setFirGenerator(String outputId, String type, String window,
                                                 double highPassFrequencyHz, double lowPassFrequencyHz, int taps)
            throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        FirFilterType parsedType = FirFilterType.parse(type);
        FirWindowFunction parsedWindow = FirWindowFunction.parse(window);
        controller.setFirGenerator(output, parsedType, parsedWindow, highPassFrequencyHz, lowPassFrequencyHz, taps);
        return buildRawTxDto(DspProtocol.buildFirGenerator(
                output,
                parsedType,
                parsedWindow,
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        ));
    }

    @Override
    public synchronized List<RawTxDto> uploadExternalFir(String channelId, String name, List<Double> coefficients)
            throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        double[] coefficientArray = toDoubleArray(coefficients);
        return controller.uploadExternalFir(channel, name, coefficientArray).stream()
                .map(this::buildRawTxDto)
                .toList();
    }

    @Override
    public synchronized RawTxDto setOutputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                              double q, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        PeqFilterType type = PeqFilterType.parse(filterType);
        controller.setOutputPeq(channel, peqIndex, type, frequencyHz, q, gainDb);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqFrequency(String channelId, int peqIndex, double hz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqFrequency(channel, peqIndex, hz);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqQ(String channelId, int peqIndex, double q) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqQ(channel, peqIndex, q);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqQRaw(String channelId, int peqIndex, int raw) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqQRaw(channel, peqIndex, raw);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqGain(channel, peqIndex, gainDb);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqGainCode(String channelId, int peqIndex, int code) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqGainCode(channel, peqIndex, code);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqType(String channelId, int peqIndex, String filterType) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqType(channel, peqIndex, PeqFilterType.parse(filterType));
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setOutputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setOutputPeqBypass(channel, peqIndex, bypass);
        return buildOutputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setInputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                             double q, double gainDb, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        PeqFilterType type = PeqFilterType.parse(filterType);
        controller.setInputPeq(channel, peqIndex, type, frequencyHz, q, gainDb, bypass);
        return buildRawTxDto(DspProtocol.buildInputPeq(inputNumber(channel), peqIndex, gainDb, frequencyHz, q, type, bypass));
    }

    @Override
    public synchronized RawTxDto setInputPeqFrequency(String channelId, int peqIndex, double frequencyHz) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqFrequency(channel, peqIndex, frequencyHz);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setInputPeqQ(String channelId, int peqIndex, double q) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqQ(channel, peqIndex, q);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setInputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqGain(channel, peqIndex, gainDb);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setInputPeqType(String channelId, int peqIndex, String filterType) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqType(channel, peqIndex, PeqFilterType.parse(filterType));
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized RawTxDto setInputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputPeqBypass(channel, peqIndex, bypass);
        return buildInputPeqRawTxDto(channel, peqIndex);
    }

    @Override
    public synchronized List<RawTxDto> setAllPeqBypass(boolean bypass) throws Exception {
        controller.setAllPeqBypass(bypass);

        List<RawTxDto> payloads = new ArrayList<>(104);
        for (DspChannel input : List.of(
                DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D)) {
            for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
                payloads.add(buildInputPeqRawTxDto(input, peqIndex));
            }
        }
        for (DspChannel output : List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8)) {
            for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
                payloads.add(buildOutputPeqRawTxDto(output, peqIndex));
            }
        }
        return List.copyOf(payloads);
    }

    @Override
    public synchronized RawTxDto setInputGeq(String channelId, int bandIndex, double gainDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputGeq(channel, bandIndex, gainDb);
        return buildRawTxDto(DspProtocol.buildInputGeq(inputNumber(channel), bandIndex, gainDb));
    }

    @Override
    public synchronized RawTxDto setInputGate(String channelId, double thresholdDb, double holdMs, double attackMs,
                                              double releaseMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        controller.setInputGate(channel, thresholdDb, holdMs, attackMs, releaseMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), thresholdDb, holdMs, attackMs, releaseMs));
    }

    @Override
    public synchronized RawTxDto setInputGateThreshold(String channelId, double thresholdDb) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateThreshold(channel, thresholdDb);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), thresholdDb, gate.holdMs(), gate.attackMs(), gate.releaseMs()));
    }

    @Override
    public synchronized RawTxDto setInputGateHold(String channelId, double holdMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateHold(channel, holdMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), holdMs, gate.attackMs(), gate.releaseMs()));
    }

    @Override
    public synchronized RawTxDto setInputGateAttack(String channelId, double attackMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateAttack(channel, attackMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), gate.holdMs(), attackMs, gate.releaseMs()));
    }

    @Override
    public synchronized RawTxDto setInputGateRelease(String channelId, double releaseMs) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        InputGateState gate = controller.state().inputGate(channel);
        controller.setInputGateRelease(channel, releaseMs);
        return buildRawTxDto(DspProtocol.buildInputGate(inputNumber(channel), gate.thresholdDb(), gate.holdMs(), gate.attackMs(), releaseMs));
    }

    @Override
    public synchronized RawTxDto setCompressor(String inputId, double thresholdDb, String ratioLabel, double attackMs,
                                               double releaseMs, double kneeDb) throws Exception {
        DspChannel input = DspChannel.parse(inputId);
        controller.setCompressor(input, thresholdDb, ratioLabel, attackMs, releaseMs, kneeDb);
        CompressorState state = controller.state().compressor(input);
        return buildRawTxDto(DspProtocol.buildInputCompressor(
                input,
                state.ratioIndex(),
                DspCodecRegistry.dynamicsTimeMsToRaw(state.attackMs(), 1.0, 999.0),
                DspCodecRegistry.dynamicsTimeMsToRaw(state.releaseMs(), 10.0, 3000.0),
                (int) Math.round(state.kneeDb()),
                DspCodecRegistry.dynamicsThresholdDbToRaw(state.thresholdDb(), -90.0, 20.0),
                DspCodecRegistry.peqFrequency().doubleToRaw(state.frequencyHz()),
                state.typeContextRaw(),
                state.typeRaw()
        ));
    }

    @Override
    public synchronized RawTxDto setLimiter(String outputId, double thresholdDb, double attackMs, double releaseMs)
            throws Exception {
        DspChannel output = DspChannel.parse(outputId);
        rememberConfirmedLimiterKnees();
        int kneeRaw = writeLimiterAndUpdateCache(
                output,
                new LimiterSettings(thresholdDb, attackMs, releaseMs),
                "api limiter tx repeated"
        );
        autoLimitUndoSnapshot = null;
        return buildRawTxDto(DspProtocol.buildLimiter(output, attackMs, releaseMs, kneeRaw, thresholdDb));
    }

    @Override
    public synchronized RawTxDto setTestToneSource(String source) throws Exception {
        int sourceRaw = switch (source == null ? "" : source.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
            case "analoginput", "analog", "input", "off" -> 0;
            case "pinknoise", "pink" -> 1;
            case "whitenoise", "white" -> 2;
            case "sine" -> 3;
            default -> throw new IllegalArgumentException("Unknown test tone source: " + source);
        };
        if (sourceRaw == 0) {
            controller.disableTestTone();
        } else {
            controller.sendRaw(DspProtocol.buildTestToneSource(sourceRaw));
            controller.state().markTestToneSource(sourceRaw, DspProtocol.testToneSourceLabel(sourceRaw), "tx");
        }
        return buildRawTxDto(DspProtocol.buildTestToneSource(sourceRaw));
    }

    @Override
    public synchronized RawTxDto setTestToneSineFrequency(double hz) throws Exception {
        controller.enableTestTone(hz);
        return buildRawTxDto(DspProtocol.buildTestToneSineFrequencyHz(hz));
    }

    @Override
    public synchronized RawTxDto setTestToneSineFrequencyRaw(int selectorRaw) throws Exception {
        controller.sendRaw(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
        controller.state().markTestToneSource(0x03, DspProtocol.testToneSourceLabel(0x03), "tx");
        controller.state().markTestToneSineFrequency(selectorRaw, DspProtocol.testToneSineFrequencyHz(selectorRaw), "tx");
        return buildRawTxDto(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
    }

    @Override
    public synchronized RawTxDto disableTestTone() throws Exception {
        controller.disableTestTone();
        return buildRawTxDto(DspProtocol.buildTestToneOff());
    }

    @Override
    public synchronized StateDto requestRuntimeMeters() throws Exception {
        // The server-owned poller is the only source of fresh DSP meter requests.
        // Keep the legacy endpoint cache-only so old clients cannot flood the DSP.
        return toStateDto();
    }

    public synchronized <T> T withController(ControllerCallback<T> callback) throws Exception {
        return callback.execute(controller);
    }

    @FunctionalInterface
    public interface ControllerCallback<T> {
        T execute(DspController controller) throws Exception;
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
                     tighteningRound <= AUTO_LIMIT_TIGHTENING_MAX_ROUNDS;
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
            MeterStateDto meters = getMeters();
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
        if (!settingsCacheStatus.ready()) {
            throw new IllegalStateException(
                    "DSP-Einstellungscache ist nicht vollständig ("
                            + settingsCacheStatus.cachedBlockCount() + "/"
                            + settingsCacheStatus.expectedBlockCount()
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

        for (LimiterDto limiter : limiters) {
            confirmedLimiterKnees.put(
                    DspChannel.parse(limiter.id()),
                    (int) Math.round(limiter.kneeDb())
            );
        }
        return limiters;
    }

    private List<LimiterDto> sortedLimiters() {
        return getLimiters().stream()
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
        ChannelDto channel = getChannel(outputId);
        if (channel.gainDb() != null && channel.gainConfirmedFromDevice()) {
            return channel;
        }
        String block = outputGainBlock(outputId);
        for (int attempt = 1; attempt <= 12; attempt++) {
            checkAutoLimitCancelled();
            readBlock(block);
            channel = getChannel(outputId);
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
        renewAutoLimitLease(leaseToken, AUTO_LIMIT_LEASE_MAX_MS);
        List<String> reverseIds = new ArrayList<>(appliedIds);
        java.util.Collections.reverse(reverseIds);
        rememberConfirmedLimiterKnees();
        try {
            withMeterPollingPaused(() -> {
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
            withMeterPollingPaused(() -> {
                for (String id : reverseIds) {
                    Double previousGainDb = previousGainsDb.get(id);
                    if (previousGainDb == null) {
                        restoredAll[0] = false;
                        continue;
                    }
                    try {
                        writeGainAndUpdateCache(
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
        rememberConfirmedLimiterKnees();
        withMeterPollingPaused(() -> {
            for (Map.Entry<String, LimiterSettings> entry : settingsById.entrySet()) {
                checkAutoLimitCancelled();
                LimiterSettings settings = entry.getValue();
                /*
                 * FIR408 acknowledges parameter writes reliably, but block-read
                 * replies can be suppressed for several seconds. The core sends
                 * every Auto-Limit write twice with pacing. Runtime LIMIT bits
                 * then provide the closed-loop verification that matters.
                 */
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
        writeLimiterAndUpdateCache(output, settings, "auto-limit tx repeated");
    }

    private int writeLimiterAndUpdateCache(
            DspChannel output,
            LimiterSettings settings,
            String source
    ) throws Exception {
        int attackRaw = DspCodecRegistry.dynamicsTimeMsToRaw(
                settings.attackMs(),
                1.0,
                999.0
        );
        int releaseRaw = DspCodecRegistry.dynamicsTimeMsToRaw(
                settings.releaseMs(),
                10.0,
                3000.0
        );
        int thresholdRaw = DspCodecRegistry.dynamicsThresholdDbToRaw(
                settings.thresholdDb(),
                -90.0,
                20.0
        );
        int kneeRaw = resolveCachedLimiterKnee(output);
        byte[] payload = DspProtocol.buildLimiter(
                output,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw
        );
        for (int attempt = 1; attempt <= CACHED_WRITE_REPEATS; attempt++) {
            controller.sendRaw(payload);
            if (attempt < CACHED_WRITE_REPEATS) {
                Thread.sleep(CACHED_WRITE_REPEAT_DELAY_MS);
            }
        }
        controller.state().markLimiter(
                output,
                DspCodecRegistry.rawToDynamicsTimeMs(attackRaw),
                DspCodecRegistry.rawToDynamicsTimeMs(releaseRaw),
                kneeRaw,
                DspCodecRegistry.rawToDynamicsThresholdDb(thresholdRaw),
                source
        );
        Fir408WriteVerifier.LimiterLocation location =
                Fir408WriteVerifier.limiterLocation(output);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.attackOffset(), attackRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.releaseOffset(), releaseRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.kneeOffset(), kneeRaw);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.thresholdOffset(), thresholdRaw);
        confirmedLimiterKnees.put(output, kneeRaw);
        return kneeRaw;
    }

    private void writeGainAndUpdateCache(
            DspChannel channel,
            double requestedDb,
            String source
    ) throws Exception {
        int gainRaw = DspCodecRegistry.gain().doubleToRaw(requestedDb);
        double normalizedDb = DspCodecRegistry.gain().rawToDouble(gainRaw);
        byte[] payload = DspProtocol.buildGain(channel.index(), normalizedDb);
        for (int attempt = 1; attempt <= CACHED_WRITE_REPEATS; attempt++) {
            controller.sendRaw(payload);
            if (attempt < CACHED_WRITE_REPEATS) {
                Thread.sleep(CACHED_WRITE_REPEAT_DELAY_MS);
            }
        }
        controller.state().markGain(channel, normalizedDb, source);
        Fir408WriteVerifier.GainLocation location = Fir408WriteVerifier.gainLocation(channel);
        controller.state().patchCachedBlockUInt16LE(
                location.blockIndex(), location.dataOffset(), gainRaw);
    }

    private synchronized void setGainsReliably(
            Map<String, Double> gainsDb,
            Set<String> appliedGainIds
    ) throws Exception {
        withMeterPollingPaused(() -> {
            for (Map.Entry<String, Double> entry : gainsDb.entrySet()) {
                checkAutoLimitCancelled();
                DspChannel output = DspChannel.parse(entry.getKey());
                writeGainAndUpdateCache(output, entry.getValue(), "auto-limit gain tx repeated");
                appliedGainIds.add(entry.getKey());
            }
            return null;
        });
    }

    private void renewServerAutoLimitLease(String leaseToken) {
        AutoLimitLeaseDto renewed = renewAutoLimitLease(leaseToken, AUTO_LIMIT_LEASE_MAX_MS);
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

    private <T> T withMeterPollingPaused(CheckedSupplier<T> action) throws Exception {
        synchronized (confirmedWriteLock) {
            DspTrafficSnapshot before = controller.trafficSnapshot();
            boolean resumeMeterPolling = before.centralMeterPolling();
            if (resumeMeterPolling) {
                controller.stopMeterPolling();
                Thread.sleep(CONTROL_QUIET_MS);
            }

            try {
                return action.get();
            } finally {
                if (resumeMeterPolling && controller.isConnected()) {
                    startCentralMeterPolling();
                }
            }
        }
    }

    private int resolveCachedLimiterKnee(DspChannel output) {
        LimiterState state = controller.state().limiter(output);
        if (state != null && state.kneeDb() != null
                && state.kneeDb() >= 0.0 && state.kneeDb() <= 12.0) {
            int kneeRaw = (int) Math.round(state.kneeDb());
            confirmedLimiterKnees.put(output, kneeRaw);
            return kneeRaw;
        }

        Integer rememberedKneeRaw = confirmedLimiterKnees.get(output);
        if (rememberedKneeRaw != null) {
            return rememberedKneeRaw;
        }
        throw new IllegalStateException(
                "Limiter-Knee für " + output.displayName()
                        + " fehlt im Server-Cache. Zuerst /refresh ausführen."
        );
    }

    private void rememberConfirmedLimiterKnees() {
        for (DspChannel channel : DspChannel.values()) {
            if (channel.index() < DspChannel.OUT_1.index()) {
                continue;
            }
            LimiterState state = controller.state().limiter(channel);
            if (state == null || state.kneeDb() == null
                    || !state.kneeConfirmedFromDevice()
                    || state.kneeDb() < 0.0 || state.kneeDb() > 12.0) {
                continue;
            }
            confirmedLimiterKnees.put(channel, (int) Math.round(state.kneeDb()));
        }
    }

    private SettingsCacheDto synchronizeSettingsCache(String reason) throws Exception {
        return withMeterPollingPaused(() -> synchronizeSettingsCacheWithMetersStopped(reason));
    }

    private SettingsCacheDto synchronizeSettingsCacheWithMetersStopped(String reason) throws Exception {
        int maxBlockIndex = controller.maxParameterBlockIndex();
        int expectedBlockCount = maxBlockIndex + 1;
        long generation = ++settingsCacheGeneration;
        long startedAtEpochMs = System.currentTimeMillis();
        controller.state().clearCachedBlocks();

        settingsCacheStatus = new SettingsCacheDto(
                "SYNCHRONIZING",
                false,
                expectedBlockCount,
                0,
                allBlockIndices(maxBlockIndex),
                startedAtEpochMs,
                null,
                generation,
                "Reading the complete DSP settings dump (" + reason + ")."
        );

        Exception dumpFailure = null;
        List<Integer> missing = missingCachedBlocks(maxBlockIndex);
        long deadlineEpochMs = startedAtEpochMs + SETTINGS_SYNC_TIMEOUT_MS;
        for (int pass = 1;
             pass <= SETTINGS_SYNC_MAX_DUMP_PASSES
                     && !missing.isEmpty()
                     && controller.isConnected()
                     && System.currentTimeMillis() < deadlineEpochMs;
             pass++) {
            /*
             * The FIR408 silently drops block requests when another request
             * follows before its answer has been consumed. Read each missing
             * block synchronously and retry it before advancing.
             */
            for (int blockIndex : List.copyOf(missing)) {
                if (System.currentTimeMillis() >= deadlineEpochMs) {
                    break;
                }
                try {
                    awaitFreshParameterBlock(
                            blockIndex,
                            SETTINGS_SYNC_BLOCK_ATTEMPTS_PER_PASS
                    );
                } catch (Exception error) {
                    dumpFailure = error;
                    LOG.warn(
                            "DSP settings dump pass {}/{} block {} failed: {}",
                            pass,
                            SETTINGS_SYNC_MAX_DUMP_PASSES,
                            formatIndexHex(blockIndex),
                            safeMessage(error)
                    );
                }
            }
            Thread.sleep(SETTINGS_SYNC_SETTLE_MS);
            missing = missingCachedBlocks(maxBlockIndex);
            settingsCacheStatus = new SettingsCacheDto(
                    "SYNCHRONIZING",
                    false,
                    expectedBlockCount,
                    expectedBlockCount - missing.size(),
                    missing.stream().map(this::formatIndexHex).toList(),
                    startedAtEpochMs,
                    null,
                    generation,
                    "Reading DSP settings dump; pass " + pass + "/"
                            + SETTINGS_SYNC_MAX_DUMP_PASSES + "."
            );
        }

        boolean ready = missing.isEmpty();
        int cachedBlockCount = expectedBlockCount - missing.size();
        long completedAtEpochMs = System.currentTimeMillis();
        String message;
        if (ready) {
            message = "Complete DSP settings dump is cached; settings reads are RAM-only.";
            rememberConfirmedLimiterKnees();
        } else {
            String suffix = dumpFailure == null
                    ? ""
                    : " Last pass: " + safeMessage(dumpFailure);
            message = "DSP settings cache is incomplete (" + cachedBlockCount + "/"
                    + expectedBlockCount + " blocks)." + suffix;
        }

        settingsCacheStatus = new SettingsCacheDto(
                ready ? "READY" : "DEGRADED",
                ready,
                expectedBlockCount,
                cachedBlockCount,
                missing.stream().map(this::formatIndexHex).toList(),
                startedAtEpochMs,
                completedAtEpochMs,
                generation,
                message
        );
        return settingsCacheStatus;
    }

    private void promoteCompleteSettingsCache() {
        SettingsCacheDto previous = settingsCacheStatus;
        if (previous.ready()
                || (!"DEGRADED".equals(previous.phase())
                && !"SYNCHRONIZING".equals(previous.phase()))) {
            return;
        }

        int maxBlockIndex = controller.maxParameterBlockIndex();
        List<Integer> missing = missingCachedBlocks(maxBlockIndex);
        if (!missing.isEmpty()) {
            return;
        }

        rememberConfirmedLimiterKnees();
        settingsCacheStatus = new SettingsCacheDto(
                "READY",
                true,
                maxBlockIndex + 1,
                maxBlockIndex + 1,
                List.of(),
                previous.startedAtEpochMs(),
                System.currentTimeMillis(),
                previous.generation(),
                "Complete DSP settings dump is cached; settings reads are RAM-only."
        );
    }

    private List<Integer> missingCachedBlocks(int maxBlockIndex) {
        List<Integer> missing = new ArrayList<>();
        for (int blockIndex = 0; blockIndex <= maxBlockIndex; blockIndex++) {
            if (controller.state().getBlock(blockIndex) == null) {
                missing.add(blockIndex);
            }
        }
        return missing;
    }

    private List<String> allBlockIndices(int maxBlockIndex) {
        List<String> indices = new ArrayList<>();
        for (int blockIndex = 0; blockIndex <= maxBlockIndex; blockIndex++) {
            indices.add(formatIndexHex(blockIndex));
        }
        return List.copyOf(indices);
    }

    private void markSettingsCacheDisconnected() {
        SettingsCacheDto previous = settingsCacheStatus;
        settingsCacheStatus = new SettingsCacheDto(
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

    private void markSettingsCacheStale(String message) {
        SettingsCacheDto previous = settingsCacheStatus;
        settingsCacheStatus = new SettingsCacheDto(
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

    private byte[] awaitFreshParameterBlock(int blockIndex) throws Exception {
        return awaitFreshParameterBlock(blockIndex, BLOCK_READ_REQUEST_ATTEMPTS);
    }

    private byte[] awaitFreshParameterBlock(int blockIndex, int requestAttempts) throws Exception {
        long previousRevision = controller.state().blockRevision(blockIndex);
        int attempts = Math.max(1, requestAttempts);
        for (int requestAttempt = 1; requestAttempt <= attempts; requestAttempt++) {
            if (controller.state().blockRevision(blockIndex) > previousRevision) {
                byte[] alreadyReceived = controller.state().getBlock(blockIndex);
                if (alreadyReceived != null) {
                    return alreadyReceived;
                }
            }

            controller.readParameterBlock(blockIndex);
            long deadlineEpochMs = System.currentTimeMillis() + BLOCK_RESPONSE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadlineEpochMs) {
                if (controller.state().blockRevision(blockIndex) > previousRevision) {
                    byte[] data = controller.state().getBlock(blockIndex);
                    if (data != null) {
                        return data;
                    }
                }
                Thread.sleep(BLOCK_RESPONSE_POLL_MS);
            }
        }
        throw new IllegalStateException(
                String.format(Locale.ROOT, "Keine frische DSP-Antwort für Block %02X.", blockIndex)
        );
    }

    private static String decodeCurrentPresetName(byte[] blockZero) {
        if (blockZero == null || blockZero.length <= 2) {
            return null;
        }
        int end = Math.min(blockZero.length, 16);
        while (end > 2 && (blockZero[end - 1] == 0x00 || blockZero[end - 1] == 0x20)) {
            end--;
        }
        String value = new String(blockZero, 2, end - 2, StandardCharsets.US_ASCII);
        return value.isBlank() ? null : value;
    }

    private static String decodePresetNamePayload(byte[] payload) {
        int end = payload.length;
        while (end > 4 && payload[end - 1] == 0x20) {
            end--;
        }
        return new String(payload, 4, end - 4, StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
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

    private DspConnectionConfig currentConfig() {
        Dsp408Configuration cfg = configuration;
        if (cfg == null) {
            return DspConnectionConfig.defaultConfig("192.168.0.166", 9761);
        }
        return DspConnectionConfig.defaultConfig(cfg.dsp_ip(), cfg.dsp_port());
    }

    private void startCentralMeterPolling() {
        Dsp408Configuration cfg = configuration;
        long intervalMs = cfg == null ? 200L : cfg.meter_poll_interval_ms();
        controller.startMeterPolling(Math.max(200L, intervalMs));
    }

    private synchronized void startConnectionWatchdog() {
        ScheduledExecutorService existing = connectionWatchdogExecutor;
        if (existing != null && !existing.isShutdown()) {
            return;
        }
        connectionWatchdogExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dsp408-reconnect-" + instanceId);
            thread.setDaemon(true);
            return thread;
        });
        connectionWatchdogExecutor.scheduleWithFixedDelay(
                this::connectionWatchdogTick,
                CONNECTION_WATCHDOG_INTERVAL_MS,
                CONNECTION_WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private synchronized void stopConnectionWatchdog() {
        ScheduledExecutorService executor = connectionWatchdogExecutor;
        connectionWatchdogExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void connectionWatchdogTick() {
        try {
            detectOrRepairLostConnection();
        } catch (Exception error) {
            LOG.warn("DSP408 {} connection watchdog failed: {}", instanceId, safeMessage(error));
        }
    }

    private synchronized void detectOrRepairLostConnection() {
        if (!reconnectDesired || reconnectAttemptRunning) {
            return;
        }
        if (controller.isConnected()) {
            reconnectSchedule.recordSuccess();
            return;
        }

        long nowEpochMs = System.currentTimeMillis();
        if (!reconnectSchedule.isArmed()) {
            reconnectSchedule.arm(nowEpochMs);
            markSettingsCacheDisconnected();
            LOG.warn(
                    "DSP408 {} connection lost; automatic reconnect scheduled in {} ms",
                    instanceId,
                    DSP_RECONNECT_INITIAL_DELAY_MS
            );
            return;
        }
        if (!reconnectSchedule.isDue(nowEpochMs)) {
            return;
        }

        reconnectAttemptRunning = true;
        try {
            LOG.info("DSP408 {} automatic reconnect starts", instanceId);
            controller.close();
            String message = connectNow();
            reconnectSchedule.recordSuccess();
            LOG.info("DSP408 {} automatic reconnect succeeded: {}", instanceId, message);
        } catch (Exception error) {
            controller.close();
            markSettingsCacheDisconnected();
            long retryInMs = reconnectSchedule.recordFailure(System.currentTimeMillis());
            LOG.warn(
                    "DSP408 {} automatic reconnect failed; next retry in {} ms: {}",
                    instanceId,
                    retryInMs,
                    safeMessage(error)
            );
        } finally {
            reconnectAttemptRunning = false;
        }
    }

    private long normalizeAutoLimitTtl(long ttlMs) {
        long requested = ttlMs <= 0L ? AUTO_LIMIT_LEASE_DEFAULT_MS : ttlMs;
        return Math.max(AUTO_LIMIT_LEASE_MIN_MS, Math.min(AUTO_LIMIT_LEASE_MAX_MS, requested));
    }

    private void clearExpiredAutoLimitLease() {
        if (autoLimitLeaseToken != null
                && System.currentTimeMillis() >= autoLimitLeaseExpiresAtEpochMs) {
            clearAutoLimitLease();
        }
    }

    private void clearAutoLimitLease() {
        autoLimitLeaseOwner = null;
        autoLimitLeaseToken = null;
        autoLimitLeaseExpiresAtEpochMs = 0L;
    }

    private boolean ownsAutoLimitLeaseInternal(String token) {
        return autoLimitLeaseToken != null
                && token != null
                && autoLimitLeaseToken.equals(token.trim());
    }

    private AutoLimitLeaseDto autoLimitLeaseDto(boolean acquired, String message) {
        boolean active = autoLimitLeaseToken != null;
        return new AutoLimitLeaseDto(
                active,
                acquired,
                autoLimitLeaseOwner,
                acquired ? autoLimitLeaseToken : null,
                active ? autoLimitLeaseExpiresAtEpochMs : null,
                message == null
                        ? (active ? "Auto-Limit is running." : "Auto-Limit is available.")
                        : message
        );
    }

    double currentVolumeStepDb() {
        Dsp408Configuration cfg = configuration;
        if (cfg == null) {
            return 1.0;
        }
        double step = cfg.volume_step_db();
        return step > 0.0 ? step : 1.0;
    }

    private void log(String message) {
        LOG.info("[DSP408] {}", message);
    }

    private ChannelDto toDto(ChannelState state) {
        if (state == null) {
            return null;
        }
        DspChannel channel = state.channel();
        return new ChannelDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                state.gainDb(),
                state.muted(),
                state.phaseInverted(),
                state.delayMs(),
                state.gainConfirmedFromDevice(),
                state.muteConfirmedFromDevice(),
                state.phaseConfirmedFromDevice(),
                state.delayConfirmedFromDevice(),
                state.gainDirty(),
                state.muteDirty(),
                state.phaseDirty(),
                state.delayDirty(),
                state.lastGainUpdateSource(),
                state.lastMuteUpdateSource(),
                state.lastPhaseUpdateSource(),
                state.lastDelayUpdateSource()
        );
    }

    private DeviceInfoDto toDeviceInfoDto() {
        String version = controller.deviceVersion();
        byte[] sysinfo = controller.lastSystemInfoPayload();
        if (version == null && sysinfo == null) {
            return new DeviceInfoDto(null, null, null, null);
        }
        String sysinfoHex = sysinfo == null ? null : Hex.format(sysinfo);
        String sysinfoAscii = sysinfo == null ? null : Hex.ascii(sysinfo);
        Integer sysinfoLength = sysinfo == null ? null : sysinfo.length;
        return new DeviceInfoDto(version, sysinfoHex, sysinfoAscii, sysinfoLength);
    }

    private StateDto toStateDto() {
        return new StateDto(
                controller.isConnected(),
                toDeviceInfoDto(),
                getChannels(),
                getCachedBlockIndices(),
                toMatrixOutputDtos(),
                toPeqChannelDtos(),
                toInputGateDtos(),
                toInputCompressorDtos(),
                toLimiterDtos(),
                toMeterStateDto()
        );
    }

    private List<MatrixOutputDto> toMatrixOutputDtos() {
        return List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
        ).stream()
                .map(channel -> toMatrixOutputDto(controller.state().matrixOutput(channel)))
                .toList();
    }

    private List<PeqChannelDto> toPeqChannelDtos() {
        return List.of(
                DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D,
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
        ).stream().map(this::toPeqChannelDto).toList();
    }

    private PeqChannelDto toPeqChannelDto(DspChannel channel) {
        boolean input = channel.index() <= DspChannel.IN_D.index();
        int bandCount = input ? 8 : 9;
        List<PeqBandDto> bands = new ArrayList<>(bandCount);
        for (int bandIndex = 1; bandIndex <= bandCount; bandIndex++) {
            PeqBandState band = input
                    ? controller.state().inputPeq(channel).band(bandIndex)
                    : controller.state().outputPeq(channel).band(bandIndex);
            bands.add(toPeqBandDto(bandIndex, band));
        }
        return new PeqChannelDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                input,
                bandCount,
                bands
        );
    }

    private static PeqBandDto toPeqBandDto(int bandIndex, PeqBandState band) {
        if (band == null) {
            return new PeqBandDto(
                    bandIndex, null, null, null, null, null,
                    false, false, false, false, false,
                    null, null, null, null, null
            );
        }
        return new PeqBandDto(
                bandIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType() == null ? null : band.filterType().displayName(),
                band.bypass(),
                band.gainConfirmedFromDevice(),
                band.frequencyConfirmedFromDevice(),
                band.qConfirmedFromDevice(),
                band.typeConfirmedFromDevice(),
                band.bypassConfirmedFromDevice(),
                band.lastGainUpdateSource(),
                band.lastFrequencyUpdateSource(),
                band.lastQUpdateSource(),
                band.lastTypeUpdateSource(),
                band.lastBypassUpdateSource()
        );
    }

    private MatrixOutputDto toMatrixOutputDto(MatrixOutputState matrix) {
        DspChannel output = matrix.output();
        List<String> routedInputIds = matrix.routedInputsSnapshot().stream()
                .sorted(java.util.Comparator.comparingInt(DspChannel::index))
                .map(this::toChannelId)
                .toList();
        List<MatrixCrosspointDto> crosspoints = List.of(
                DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D
        ).stream()
                .map(input -> new MatrixCrosspointDto(
                        toChannelId(input),
                        input.displayName(),
                        input.index(),
                        matrix.crosspointGain(input),
                        matrix.crosspointGainConfirmedFromDevice(input),
                        matrix.crosspointGainUpdateSource(input)
                ))
                .toList();
        return new MatrixOutputDto(
                toChannelId(output),
                output.displayName(),
                output.index(),
                routedInputIds,
                crosspoints
        );
    }

    private List<InputGateDto> toInputGateDtos() {
        return List.of(DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D).stream()
                .map(channel -> toInputGateDto(controller.state().inputGate(channel)))
                .toList();
    }

    private InputGateDto toInputGateDto(InputGateState gate) {
        DspChannel channel = gate.input();
        return new InputGateDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                gate.thresholdDb(),
                gate.holdMs(),
                gate.attackMs(),
                gate.releaseMs(),
                gate.thresholdConfirmedFromDevice(),
                gate.holdConfirmedFromDevice(),
                gate.attackConfirmedFromDevice(),
                gate.releaseConfirmedFromDevice(),
                gate.lastThresholdUpdateSource(),
                gate.lastHoldUpdateSource(),
                gate.lastAttackUpdateSource(),
                gate.lastReleaseUpdateSource()
        );
    }

    private List<InputCompressorDto> toInputCompressorDtos() {
        return List.of(DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D).stream()
                .map(channel -> toInputCompressorDto(controller.state().compressor(channel)))
                .toList();
    }

    private InputCompressorDto toInputCompressorDto(CompressorState compressor) {
        DspChannel channel = compressor.channel();
        return new InputCompressorDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                compressor.ratioIndex(),
                compressor.ratioLabel(),
                compressor.attackMs(),
                compressor.releaseMs(),
                compressor.kneeDb(),
                compressor.thresholdDb(),
                compressor.frequencyHz(),
                compressor.typeContextRaw(),
                compressor.typeRaw(),
                compressor.typeLabel(),
                compressor.ratioConfirmedFromDevice(),
                compressor.attackConfirmedFromDevice(),
                compressor.releaseConfirmedFromDevice(),
                compressor.kneeConfirmedFromDevice(),
                compressor.thresholdConfirmedFromDevice(),
                compressor.frequencyConfirmedFromDevice(),
                compressor.typeContextConfirmedFromDevice(),
                compressor.typeConfirmedFromDevice(),
                compressor.lastRatioUpdateSource(),
                compressor.lastAttackUpdateSource(),
                compressor.lastReleaseUpdateSource(),
                compressor.lastKneeUpdateSource(),
                compressor.lastThresholdUpdateSource(),
                compressor.lastFrequencyUpdateSource(),
                compressor.lastTypeContextUpdateSource(),
                compressor.lastTypeUpdateSource()
        );
    }

    private List<LimiterDto> toLimiterDtos() {
        return List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
        ).stream()
                .map(channel -> toLimiterDto(controller.state().limiter(channel)))
                .toList();
    }

    private LimiterDto toLimiterDto(LimiterState limiter) {
        DspChannel channel = limiter.output();
        return new LimiterDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                limiter.attackMs(),
                limiter.releaseMs(),
                limiter.thresholdDb(),
                limiter.kneeDb(),
                limiter.runtimeActive(),
                limiter.attackConfirmedFromDevice(),
                limiter.releaseConfirmedFromDevice(),
                limiter.thresholdConfirmedFromDevice(),
                limiter.kneeConfirmedFromDevice(),
                limiter.runtimeConfirmedFromDevice(),
                limiter.lastAttackUpdateSource(),
                limiter.lastReleaseUpdateSource(),
                limiter.lastThresholdUpdateSource(),
                limiter.lastKneeUpdateSource(),
                limiter.lastRuntimeUpdateSource()
        );
    }

    private MeterStateDto toMeterStateDto() {
        MeterState meterState = controller.state().meterState();
        byte[] rawPayload = meterState.lastRuntimePayload();
        List<Integer> tailBytes = new ArrayList<>();
        if (rawPayload != null) {
            for (int offset = 40; offset < rawPayload.length; offset++) {
                tailBytes.add(rawPayload[offset] & 0xFF);
            }
        }
        return new MeterStateDto(
                meterState.allChannels().stream()
                        .map(this::toMeterDto)
                        .toList(),
                meterState.statusByteRaw(),
                meterState.statusConfirmedFromDevice(),
                meterState.lastStatusUpdateSource(),
                rawPayload == null ? null : Hex.format(rawPayload),
                tailBytes,
                meterState.lastRuntimePayloadObservedAtEpochMs(),
                meterState.runtimePayloadConfirmedFromDevice(),
                meterState.lastRuntimePayloadUpdateSource()
        );
    }

    private MeterDto toMeterDto(MeterChannelState meter) {
        if (meter == null) {
            return null;
        }

        DspChannel channel = meter.channel();
        LimiterState limiter = channel.index() >= DspChannel.OUT_1.index()
                ? controller.state().limiter(channel)
                : null;
        Integer rawValue = meter.rawValue();

        return new MeterDto(
                toChannelId(channel),
                channel.displayName(),
                channel.index(),
                meter.rawLowByte(),
                meter.rawHighByte(),
                rawValue,
                rawValue == null ? null : MeterLevelDecoder.decodeDb(rawValue, meter.slotByte2()),
                meter.slotByte2(),
                limiter == null ? null : limiter.runtimeActive(),
                meter.clipActive(),
                meter.confirmedFromDevice(),
                limiter != null && limiter.runtimeConfirmedFromDevice(),
                meter.clipDerivedFromDeviceMeter(),
                controller.meterClipThresholdConfirmed(channel),
                controller.meterChannelMappingConfirmed(),
                controller.meterLevelCalibrationConfirmed(),
                limiter != null && controller.meterLimiterMappingConfirmed(),
                meter.lastUpdateSource(),
                limiter == null ? null : limiter.lastRuntimeUpdateSource(),
                meter.lastClipUpdateSource()
        );
    }

    private RawTxDto buildRawTxDto(byte[] payload) {
        byte[] frame = DspFrameCodec.encode(payload);
        return new RawTxDto(
                Hex.format(payload),
                Hex.format(frame),
                payload.length,
                frame.length
        );
    }

    private BlockDto toBlockDto(int index, byte[] data) {
        boolean present = data != null;
        String dataHex = data == null ? null : Hex.format(data);
        String dataAscii = data == null ? null : Hex.ascii(data);
        Integer length = data == null ? null : data.length;
        return new BlockDto(formatIndexHex(index), index & 0xFF, present, dataHex, dataAscii, length);
    }

    private String formatIndexHex(int index) {
        return Hex.byteToHex(index);
    }

    private int parseHexIndex(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Block index is missing");
        }
        if (!cleaned.matches("(?i)[0-9a-f]{1,2}")) {
            throw new IllegalArgumentException(
                    "Block index must be a hex value in range 00.." + formatIndexHex(controller.maxParameterBlockIndex())
            );
        }
        int index = Integer.parseInt(cleaned, 16);
        int max = controller.maxParameterBlockIndex();
        if (index < 0x00 || index > max) {
            throw new IllegalArgumentException("Block index must be in range 00.." + formatIndexHex(max));
        }
        return index;
    }

    private String toChannelId(DspChannel channel) {
        return switch (channel) {
            case IN_A -> "ina";
            case IN_B -> "inb";
            case IN_C -> "inc";
            case IN_D -> "ind";
            case OUT_1 -> "out1";
            case OUT_2 -> "out2";
            case OUT_3 -> "out3";
            case OUT_4 -> "out4";
            case OUT_5 -> "out5";
            case OUT_6 -> "out6";
            case OUT_7 -> "out7";
            case OUT_8 -> "out8";
        };
    }

    private static int outputNumber(DspChannel channel) {
        return channel.index() - 3;
    }

    private static int inputNumber(DspChannel channel) {
        return channel.index();
    }

    private static double[] toDoubleArray(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("FIR coefficients are required");
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null) {
                throw new IllegalArgumentException("FIR coefficients must not contain null values");
            }
            out[i] = value;
        }
        return out;
    }

    private static CrossoverSlope resolvedSlope(CrossoverFilterState state) {
        return state.slope() == null ? CrossoverSlope.LR_48 : state.slope();
    }

    private RawTxDto buildOutputPeqRawTxDto(DspChannel channel, int peqIndex) {
        PeqBandState band = controller.state().outputPeq(channel).band(peqIndex);
        if (band == null
                || band.gainDb() == null
                || band.frequencyHz() == null
                || band.q() == null
                || band.filterType() == null
                || band.bypass() == null) {
            throw new IllegalStateException("Output PEQ state is incomplete for " + channel.displayName() + " PEQ" + peqIndex);
        }

        byte[] payload = DspProtocol.buildOutputPeq(
                outputNumber(channel),
                peqIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType(),
                band.bypass()
        );
        return buildRawTxDto(payload);
    }

    private RawTxDto buildInputPeqRawTxDto(DspChannel channel, int peqIndex) {
        PeqBandState band = controller.state().inputPeq(channel).band(peqIndex);
        if (band == null
                || band.gainDb() == null
                || band.frequencyHz() == null
                || band.q() == null
                || band.filterType() == null
                || band.bypass() == null) {
            throw new IllegalStateException("Input PEQ state is incomplete for " + channel.displayName() + " PEQ" + peqIndex);
        }

        byte[] payload = DspProtocol.buildInputPeq(
                inputNumber(channel),
                peqIndex,
                band.gainDb(),
                band.frequencyHz(),
                band.q(),
                band.filterType(),
                band.bypass()
        );
        return buildRawTxDto(payload);
    }

    private String executeWithCapture(DspCommandProcessor template, String line, boolean isAdmin) {
        List<String> lines = new CopyOnWriteArrayList<>();
        DspCommandProcessor processor = new DspCommandProcessor(
                controller,
                this::currentConfig,
                lines::add,
                template == shellProcessor
                        ? DspCommandProcessor.CommandMode.CLI
                        : DspCommandProcessor.CommandMode.MATRIX
        );

        String prefixed = line == null ? "" : line.trim();
        if (!prefixed.isEmpty() && !prefixed.toLowerCase(Locale.ROOT).startsWith("!dsp")) {
            prefixed = "dsp " + prefixed;
        }

        processor.process(prefixed, isAdmin);
        return String.join(System.lineSeparator(), lines);
    }

    private synchronized DspInstanceDto toInstanceDto(boolean defaultDevice) {
        DspConnectionConfig config = currentConfig();
        return new DspInstanceDto(
                instanceId,
                config.ip(),
                config.port(),
                defaultDevice,
                controller.isConnected(),
                toDeviceInfoDto()
        );
    }

    private void rebuildDevicePool(Dsp408Configuration configuration) {
        Map<String, DspRuntimeComponent> replacement = new LinkedHashMap<>();
        for (DspDefinition definition : parseAdditionalDsps(configuration)) {
            if (definition.id().equals(instanceId)) {
                LOG.warn("Ignoring additional DSP '{}' because it is the default DSP id", definition.id());
                continue;
            }
            DspRuntimeComponent device = new DspRuntimeComponent(false);
            device.activate(new StaticDsp408Configuration(
                    definition.id(),
                    definition.ip(),
                    definition.port(),
                    configuration.dsp_library(),
                    configuration.auto_connect(),
                    configuration.auto_read_on_connect(),
                    configuration.meter_poll_interval_ms(),
                    configuration.volume_step_db(),
                    new String[0]
            ));
            replacement.put(definition.id(), device);
        }

        Map<String, DspRuntimeComponent> old = devices;
        devices = Map.copyOf(replacement);
        for (DspRuntimeComponent device : old.values()) {
            device.deactivate();
        }
    }

    private void closeDevicePool() {
        Map<String, DspRuntimeComponent> old = devices;
        devices = Map.of();
        for (DspRuntimeComponent device : old.values()) {
            device.deactivate();
        }
    }

    private static List<DspDefinition> parseAdditionalDsps(Dsp408Configuration configuration) {
        List<DspDefinition> definitions = new ArrayList<>();
        String[] entries = configuration.dsps();
        if (entries == null) {
            return definitions;
        }

        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.trim();
            String[] split = text.split("=", 2);
            if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                throw new IllegalArgumentException("DSP definition must use id=ip or id=ip:port: " + entry);
            }

            String id = normalizeDspId(split[0]);
            String endpoint = split[1].trim();
            int port = 9761;
            String ip = endpoint;
            int colon = endpoint.lastIndexOf(':');
            if (colon > 0 && colon < endpoint.length() - 1) {
                ip = endpoint.substring(0, colon).trim();
                port = Integer.parseInt(endpoint.substring(colon + 1).trim());
            }
            if (ip.isBlank()) {
                throw new IllegalArgumentException("DSP IP is missing in definition: " + entry);
            }
            definitions.add(new DspDefinition(id, ip, port));
        }

        return definitions;
    }

    private static String normalizeDspId(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "main";
        }
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("DSP id must contain only letters, digits, dot, underscore or dash: " + id);
        }
        return normalized;
    }

    private static boolean connectionChanged(Dsp408Configuration oldCfg, Dsp408Configuration newCfg) {
        return !oldCfg.dsp_ip().equals(newCfg.dsp_ip())
                || oldCfg.dsp_port() != newCfg.dsp_port()
                || !oldCfg.dsp_library().equalsIgnoreCase(newCfg.dsp_library());
    }

    private record DspDefinition(String id, String ip, int port) {
    }

    private record StaticDsp408Configuration(String dspId,
                                             String dspIp,
                                             int dspPort,
                                             String dspLibrary,
                                             boolean autoConnect,
                                             boolean autoReadOnConnect,
                                             long meterPollIntervalMs,
                                             double volumeStepDb,
                                             String[] dsps) implements Dsp408Configuration {
        @Override
        public String dsp_id() {
            return dspId;
        }

        @Override
        public String dsp_ip() {
            return dspIp;
        }

        @Override
        public int dsp_port() {
            return dspPort;
        }

        @Override
        public String dsp_library() {
            return dspLibrary;
        }

        @Override
        public boolean auto_connect() {
            return autoConnect;
        }

        @Override
        public boolean auto_read_on_connect() {
            return autoReadOnConnect;
        }

        @Override
        public long meter_poll_interval_ms() {
            return meterPollIntervalMs;
        }

        @Override
        public double volume_step_db() {
            return volumeStepDb;
        }

        @Override
        public String[] dsps() {
            return dsps.clone();
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Dsp408Configuration.class;
        }
    }
}

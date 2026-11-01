package de.drremote.dsp408controller.runtime;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;
import java.util.Locale;

import org.osgi.framework.BundleContext;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.drremote.dsp408.api.CapabilitiesDto;
import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;
import de.drremote.dsp408.api.BlockDto;
import de.drremote.dsp408.api.AutoLimitLeaseDto;
import de.drremote.dsp408.api.AutoLimitStatusDto;
import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408.api.DeviceInfoDto;
import de.drremote.dsp408.api.DspInstanceDto;
import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.DspDeviceConflictException;
import de.drremote.dsp408.api.DspDeviceDefinitionDto;
import de.drremote.dsp408.api.DspDeviceRegistrationDto;
import de.drremote.dsp408.api.DspDeviceRegistryDto;
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
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

@Component(
        service = DspService.class,
        immediate = true,
        configurationPid = "de.drremote.dsp408controller"
)
@Designate(ocd = Dsp408Configuration.class)
public final class DspRuntimeComponent implements DspService {
    private static final Logger LOG = LoggerFactory.getLogger(DspRuntimeComponent.class);
    private final DspRuntimeSettings runtimeSettings = new DspRuntimeSettings();
    private final DspController controller = new DspController();
    private final DspRuntimeDtoMapper dtoMapper = new DspRuntimeDtoMapper(controller);
    private final DspStateQueries stateQueries = new DspStateQueries(controller, dtoMapper);
    private final DspTransactionCoordinator transactions = new DspTransactionCoordinator(
            controller,
            this::startCentralMeterPolling
    );
    private final DspParameterCapabilities parameterCapabilities = new DspParameterCapabilities(
            controller, this::dspId);
    private final DspParameterWrites parameterWrites = new DspParameterWrites(
            controller, transactions, this::dspId);
    private final DspDynamicsCommands dynamics = new DspDynamicsCommands(
            controller,
            dtoMapper,
            transactions
    );
    private final DspSettingsCache settingsCache = new DspSettingsCache(
            controller,
            transactions,
            dynamics::rememberConfirmedLimiterKnees
    );
    private final DspLevelCommands levels = new DspLevelCommands(
            controller,
            dtoMapper,
            transactions,
            settingsCache,
            this::log
    );
    private final DspBlockManager blocks = new DspBlockManager(
            controller,
            dtoMapper,
            transactions,
            settingsCache
    );
    private final DspAutoLimitManager autoLimit = new DspAutoLimitManager(
            controller,
            dtoMapper,
            transactions,
            settingsCache,
            blockIndex -> readBlock(blockIndex),
            (output, thresholdDb, attackMs, releaseMs, source) ->
                    dynamics.writeLimiterAndUpdateCache(
                            output, thresholdDb, attackMs, releaseMs, source),
            levels::writeGainAndUpdateCache,
            dynamics::rememberConfirmedLimiterKnees
    );
    private final DspRawCommandGateway rawCommands = new DspRawCommandGateway(
            controller,
            dtoMapper,
            settingsCache,
            autoLimit::invalidateUndo
    );
    private final DspPresetManager presets = new DspPresetManager(
            controller,
            dtoMapper,
            transactions,
            settingsCache,
            this::dspId,
            autoLimit::invalidateUndo,
            this::log
    );
    private final DspFilterCommands filters = new DspFilterCommands(controller, dtoMapper);
    private final DspTestToneCommands testTone = new DspTestToneCommands(controller, dtoMapper);
    private final DspChannelRoutingCommands channelRouting = new DspChannelRoutingCommands(
            controller,
            dtoMapper
    );
    private final DspConnectionManager connectionManager = new DspConnectionManager(
            controller,
            settingsCache,
            runtimeSettings::connectionConfig,
            runtimeSettings::instanceId,
            runtimeSettings::autoConnect,
            this::startCentralMeterPolling,
            this::log
    );
    private final DspTextCommandGateway textCommands = new DspTextCommandGateway(
            this,
            controller,
            runtimeSettings::connectionConfig
    );

    private final DspDevicePool devicePool;
    private volatile DspManagedDeviceRegistry managedRegistry;
    private BundleContext bundleContext;
    private final boolean lazyWatchdog;

    public DspRuntimeComponent() {
        this(true);
    }

    DspRuntimeComponent(boolean manageDevicePool) {
        this(manageDevicePool, false);
    }

    DspRuntimeComponent(boolean manageDevicePool, boolean lazyWatchdog) {
        this.devicePool = new DspDevicePool(this, manageDevicePool);
        this.lazyWatchdog = lazyWatchdog;
    }

    @Activate
    synchronized void activateComponent(BundleContext context, Dsp408Configuration configuration) {
        bundleContext = context;
        activate(configuration);
    }

    synchronized void activate(Dsp408Configuration configuration) {
        if (isManaged(configuration)) {
            DspManagedDeviceRegistry registry = new DspManagedDeviceRegistry(registryPath(configuration), configuration);
            runtimeSettings.apply(configuration);
            managedRegistry = registry;
            registry.startAutoConnections();
            LOG.info("DSP408 managed device registry activated ({})", registry.path());
            return;
        }
        runtimeSettings.apply(configuration);
        controller.setPreferredLibraryType(configuration.dsp_library());
        devicePool.rebuild(configuration);
        connectionManager.setReconnectDesired(configuration.auto_connect());
        if (!lazyWatchdog || configuration.auto_connect()) connectionManager.startWatchdog();

        LOG.info(
                "DSP408 {} activated ({}:{})",
                runtimeSettings.instanceId(),
                configuration.dsp_ip(),
                configuration.dsp_port());

        if (configuration.auto_connect()) {
            connectionManager.requestConnect();
        }
    }

    @Modified
    synchronized void modified(Dsp408Configuration configuration) {
        boolean nextManaged = isManaged(configuration);
        DspManagedDeviceRegistry current = managedRegistry;
        Path nextPath = nextManaged ? registryPath(configuration) : null;
        if (current != null && (!nextManaged || !current.path().equals(nextPath)) && !current.isEmpty()) {
            throw new DspDeviceConflictException("Remove all registered devices before changing registry mode or storage path");
        }
        if (nextManaged) {
            if (current != null && current.path().equals(nextPath)) {
                current.updateConfiguration(configuration);
                runtimeSettings.apply(configuration);
                return;
            }
            DspManagedDeviceRegistry replacement = new DspManagedDeviceRegistry(nextPath, configuration);
            connectionManager.disconnect();
            connectionManager.stopWatchdog();
            devicePool.close();
            if (current != null) current.close();
            managedRegistry = replacement;
            runtimeSettings.apply(configuration);
            replacement.startAutoConnections();
            return;
        }
        if (current != null) {
            current.close();
            managedRegistry = null;
            connectionManager.startWatchdog();
        }
        DspRuntimeSettings.Update update = runtimeSettings.apply(configuration);
        boolean reconnectNeeded = update.connectionChanged();
        controller.setPreferredLibraryType(configuration.dsp_library());
        devicePool.rebuild(configuration);

        if (update.instanceIdChanged()) {
            LOG.info(
                    "DSP408 instance id changed: {} -> {}",
                    update.oldInstanceId(),
                    update.newInstanceId());
        }

        connectionManager.setReconnectDesired(configuration.auto_connect());
        if (configuration.auto_connect()) connectionManager.startWatchdog();
        if (reconnectNeeded) {
            autoLimit.invalidateUndo();
            if (configuration.auto_connect() || controller.isConnected()) {
                connectionManager.requestReconnect();
            } else {
                connectionManager.disconnect();
            }
        } else if (configuration.auto_connect() && !controller.isConnected()) {
            connectionManager.requestConnect();
        } else if (controller.isConnected() && update.meterPollIntervalChanged()) {
            startCentralMeterPolling();
        }
    }

    @Deactivate
    synchronized void deactivate() {
        DspManagedDeviceRegistry current = managedRegistry;
        if (current != null) current.close();
        connectionManager.close();
        devicePool.close();
        autoLimit.close();
    }

    @Override
    public String dspId() {
        return managedRegistry == null ? runtimeSettings.instanceId() : "";
    }

    @Override
    public List<DspInstanceDto> getDspInstances() {
        DspManagedDeviceRegistry current = managedRegistry;
        return current == null ? devicePool.instances() : current.instances();
    }

    @Override
    public DspService forDsp(String dspId) {
        DspManagedDeviceRegistry current = managedRegistry;
        return current == null ? devicePool.resolve(dspId) : current.resolve(dspId);
    }

    @Override
    public DspDeviceRegistryDto getDeviceRegistry() {
        DspManagedDeviceRegistry current = managedRegistry;
        return current == null ? new DspDeviceRegistryDto("static", false, List.of()) : current.snapshot();
    }

    @Override
    public synchronized DspDeviceRegistrationDto registerDevice(DspDeviceDefinitionDto definition) {
        return requireManagedRegistry().register(definition);
    }

    @Override
    public synchronized void removeDevice(String id) {
        requireManagedRegistry().remove(id);
    }

    private DspManagedDeviceRegistry requireManagedRegistry() {
        DspManagedDeviceRegistry current = managedRegistry;
        if (current == null) throw new UnsupportedOperationException("Set registry_mode=managed to register devices");
        return current;
    }

    private static boolean isManaged(Dsp408Configuration configuration) {
        String mode = configuration.registry_mode().trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("static") && !mode.equals("managed")) {
            throw new IllegalArgumentException("registry_mode must be static or managed");
        }
        return mode.equals("managed");
    }

    private Path registryPath(Dsp408Configuration configuration) {
        String configured = configuration.registry_file();
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        java.io.File fallback = bundleContext == null ? null : bundleContext.getDataFile("device-registry.json");
        if (fallback == null) throw new IllegalStateException("Managed mode requires registry_file or an OSGi bundle data directory");
        return fallback.toPath().toAbsolutePath().normalize();
    }

    private void requirePhysicalDevice() {
        if (managedRegistry != null) {
            throw new DspDeviceConflictException("Select a registered DSP id; the managed registry has no default device");
        }
    }

    @Override
    public String helpText() {
        return textCommands.helpText();
    }

    @Override
    public String executeShell(String line) throws Exception {
        return textCommands.executeShell(line);
    }

    @Override
    public String executeMatrix(String line, boolean isAdmin) throws Exception {
        return textCommands.executeMatrix(line, isAdmin);
    }

    @Override
    public String executeVolumeRoom(String line) throws Exception {
        return textCommands.executeVolumeRoom(line);
    }

    @Override
    public String connect() throws Exception {
        CompletableFuture<String> result;
        synchronized (this) {
            requirePhysicalDevice();
            connectionManager.startWatchdog();
            result = connectionManager.requestConnect();
        }
        return DspConnectionManager.await(result);
    }

    @Override
    public String reconnect() throws Exception {
        CompletableFuture<String> result;
        synchronized (this) {
            requirePhysicalDevice();
            autoLimit.invalidateUndo();
            connectionManager.startWatchdog();
            result = connectionManager.requestReconnect();
        }
        return DspConnectionManager.await(result);
    }

    @Override
    public void disconnect() {
        autoLimit.invalidateUndo();
        connectionManager.disconnect();
        if (lazyWatchdog) connectionManager.stopWatchdog();
    }

    @Override
    public boolean isConnected() {
        return controller.isConnected();
    }

    @Override
    public DeviceInfoDto getDeviceInfo() {
        return stateQueries.deviceInfo();
    }

    @Override
    public CapabilitiesDto getCapabilities() {
        return parameterCapabilities.get();
    }

    @Override
    public ParameterWriteResultDto writeParameter(ParameterWriteRequestDto request) {
        return recordParameterWrite(parameterWrites.write(request));
    }

    @Override
    public ParameterWriteResultDto writeParameterInteractive(ParameterWriteRequestDto request) {
        return recordParameterWrite(parameterWrites.writeInteractive(request));
    }

    private ParameterWriteResultDto recordParameterWrite(ParameterWriteResultDto result) {
        if (result.status() != ParameterWriteStatus.REJECTED) {
            autoLimit.invalidateUndo();
            if (controller.isConnected()) {
                // Verification covers one parameter, not every raw block in the
                // complete settings snapshot or possible linked-device effects.
                settingsCache.markStale("A parameter write may have changed the device; "
                        + "refresh the full snapshot before treating it as authoritative.");
            } else {
                settingsCache.markDisconnected();
            }
        }
        return result;
    }

    @Override
    public List<ChannelDto> getChannels() {
        return stateQueries.channels();
    }

    @Override
    public SettingsCacheDto getSettingsCacheStatus() {
        return settingsCache.status();
    }

    @Override
    public ChannelDto getChannel(String channelId) {
        return stateQueries.channel(channelId);
    }

    @Override
    public List<MatrixOutputDto> getMatrixOutputs() {
        return stateQueries.matrixOutputs();
    }

    @Override
    public MatrixOutputDto getMatrixOutput(String outputId) {
        return stateQueries.matrixOutput(outputId);
    }

    @Override
    public List<PeqChannelDto> getPeqChannels() {
        return stateQueries.peqChannels();
    }

    @Override
    public PeqChannelDto getPeqChannel(String channelId) {
        return stateQueries.peqChannel(channelId);
    }

    @Override
    public List<InputGateDto> getInputGates() {
        return stateQueries.inputGates();
    }

    @Override
    public List<InputCompressorDto> getInputCompressors() {
        return stateQueries.inputCompressors();
    }

    @Override
    public InputCompressorDto getInputCompressor(String channelId) {
        return stateQueries.inputCompressor(channelId);
    }

    @Override
    public List<LimiterDto> getLimiters() {
        return stateQueries.limiters();
    }

    @Override
    public LimiterDto getLimiter(String channelId) {
        return stateQueries.limiter(channelId);
    }

    @Override
    public MeterStateDto getMeters() {
        return stateQueries.meters();
    }

    @Override
    public MeterDto getMeter(String channelId) {
        return stateQueries.meter(channelId);
    }

    @Override
    public TrafficStatusDto getTrafficStatus() {
        return stateQueries.trafficStatus();
    }

    @Override
    public AutoLimitLeaseDto getAutoLimitLease() {
        return autoLimit.getAutoLimitLease();
    }

    @Override
    public AutoLimitLeaseDto acquireAutoLimitLease(String owner, long ttlMs) {
        return autoLimit.acquireAutoLimitLease(owner, ttlMs);
    }

    @Override
    public AutoLimitLeaseDto renewAutoLimitLease(String token, long ttlMs) {
        return autoLimit.renewAutoLimitLease(token, ttlMs);
    }

    @Override
    public AutoLimitLeaseDto releaseAutoLimitLease(String token) {
        return autoLimit.releaseAutoLimitLease(token);
    }

    @Override
    public boolean ownsAutoLimitLease(String token) {
        return autoLimit.ownsAutoLimitLease(token);
    }

    @Override
    public AutoLimitStatusDto getAutoLimitStatus() {
        return autoLimit.getAutoLimitStatus();
    }

    @Override
    public AutoLimitStatusDto startAutoLimit(
            double headroomDb,
            String targetMode,
            long measurementMs
    ) {
        return autoLimit.startAutoLimit(headroomDb, targetMode, measurementMs);
    }

    @Override
    public AutoLimitStatusDto cancelAutoLimit() {
        return autoLimit.cancelAutoLimit();
    }

    @Override
    public AutoLimitStatusDto undoAutoLimit() {
        return autoLimit.undoAutoLimit();
    }

    @Override
    public synchronized ChannelDto setGain(String channelId, double db) throws Exception {
        DspChannel channel = DspChannel.parse(channelId);
        ChannelDto result = levels.setGain(channel, db);
        if (channel.index() >= DspChannel.OUT_1.index()) {
            autoLimit.invalidateUndo();
        }
        return result;
    }

    @Override
    public synchronized ChannelDto mute(String channelId) throws Exception {
        return levels.setMute(channelId, true);
    }

    @Override
    public synchronized ChannelDto unmute(String channelId) throws Exception {
        return levels.setMute(channelId, false);
    }

    @Override
    public List<String> getCachedBlockIndices() {
        return blocks.cachedBlockIndices();
    }

    @Override
    public BlockDto getCachedBlock(String blockIndexHex) {
        return blocks.cachedBlock(blockIndexHex);
    }

    @Override
    public synchronized BlockDto readBlock(String blockIndexHex) throws Exception {
        return blocks.read(blockIndexHex);
    }

    @Override
    public synchronized StateDto scanBlocks() throws Exception {
        return blocks.scan();
    }

    @Override
    public synchronized PresetSnapshotDto exportPresetSnapshot() throws Exception {
        return presets.exportSnapshot();
    }

    @Override
    public synchronized RawTxDto sendRawHex(String payloadHex) throws Exception {
        return rawCommands.sendRawHex(payloadHex);
    }

    @Override
    public synchronized void loginPin(String pin) throws Exception {
        rawCommands.loginPin(pin);
    }

    @Override
    public synchronized RawTxDto loadPreset(String slotLabel) throws Exception {
        return presets.load(slotLabel);
    }

    @Override
    public synchronized RawTxDto loadPreset(int presetIndex) throws Exception {
        return presets.load(presetIndex);
    }

    @Override
    public synchronized PresetStoreResultDto storePreset(String slotLabel, String name) throws Exception {
        return presets.store(slotLabel, name);
    }

    @Override
    public synchronized RawTxDto readPresetName(int presetIndex) throws Exception {
        return presets.readName(presetIndex);
    }

    @Override
    public synchronized RawTxDto setDelayUnit(String unit) throws Exception {
        return channelRouting.setDelayUnit(unit);
    }

    @Override
    public synchronized RawTxDto setChannelName(String channelId, String name) throws Exception {
        return channelRouting.setChannelName(channelId, name);
    }

    @Override
    public synchronized ChannelDto setPhase(String channelId, boolean inverted) throws Exception {
        return channelRouting.setPhase(channelId, inverted);
    }

    @Override
    public synchronized ChannelDto setDelay(String channelId, double ms) throws Exception {
        return channelRouting.setDelay(channelId, ms);
    }

    @Override
    public synchronized RawTxDto setMatrixRoute(String outputId, String inputId) throws Exception {
        return channelRouting.setMatrixRoute(outputId, inputId);
    }

    @Override
    public synchronized RawTxDto setMatrixRoutes(String outputId, List<String> inputIds) throws Exception {
        return channelRouting.setMatrixRoutes(outputId, inputIds);
    }

    @Override
    public synchronized RawTxDto setMatrixCrosspointGain(String outputId, String inputId, double db) throws Exception {
        return channelRouting.setMatrixCrosspointGain(outputId, inputId, db);
    }
    @Override
    public synchronized RawTxDto setCrossoverHighPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception{
        return filters.setCrossoverHighPass(channelId, frequencyHz, slope, bypass);
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassFrequency(String channelId, double frequencyHz) throws Exception{
        return filters.setCrossoverHighPassFrequency(channelId, frequencyHz);
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassSlope(String channelId, String slope) throws Exception{
        return filters.setCrossoverHighPassSlope(channelId, slope);
    }

    @Override
    public synchronized RawTxDto setCrossoverHighPassBypass(String channelId, boolean bypass) throws Exception{
        return filters.setCrossoverHighPassBypass(channelId, bypass);
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPass(String channelId, double frequencyHz, String slope, boolean bypass)
            throws Exception{
        return filters.setCrossoverLowPass(channelId, frequencyHz, slope, bypass);
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassFrequency(String channelId, double frequencyHz) throws Exception{
        return filters.setCrossoverLowPassFrequency(channelId, frequencyHz);
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassSlope(String channelId, String slope) throws Exception{
        return filters.setCrossoverLowPassSlope(channelId, slope);
    }

    @Override
    public synchronized RawTxDto setCrossoverLowPassBypass(String channelId, boolean bypass) throws Exception{
        return filters.setCrossoverLowPassBypass(channelId, bypass);
    }

    @Override
    public synchronized RawTxDto setFirProcessingMode(String outputId, String mode) throws Exception{
        return filters.setFirProcessingMode(outputId, mode);
    }

    @Override
    public synchronized RawTxDto setFirGenerator(String outputId, String type, String window,
                                                 double highPassFrequencyHz, double lowPassFrequencyHz, int taps)
            throws Exception{
        return filters.setFirGenerator(outputId, type, window, highPassFrequencyHz, lowPassFrequencyHz, taps);
    }

    @Override
    public synchronized List<RawTxDto> uploadExternalFir(String channelId, String name, List<Double> coefficients)
            throws Exception{
        return filters.uploadExternalFir(channelId, name, coefficients);
    }

    @Override
    public synchronized RawTxDto setOutputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                              double q, double gainDb) throws Exception{
        return filters.setOutputPeq(channelId, peqIndex, filterType, frequencyHz, q, gainDb);
    }

    @Override
    public synchronized RawTxDto setOutputPeqFrequency(String channelId, int peqIndex, double hz) throws Exception{
        return filters.setOutputPeqFrequency(channelId, peqIndex, hz);
    }

    @Override
    public synchronized RawTxDto setOutputPeqQ(String channelId, int peqIndex, double q) throws Exception{
        return filters.setOutputPeqQ(channelId, peqIndex, q);
    }

    @Override
    public synchronized RawTxDto setOutputPeqQRaw(String channelId, int peqIndex, int raw) throws Exception{
        return filters.setOutputPeqQRaw(channelId, peqIndex, raw);
    }

    @Override
    public synchronized RawTxDto setOutputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception{
        return filters.setOutputPeqGain(channelId, peqIndex, gainDb);
    }

    @Override
    public synchronized RawTxDto setOutputPeqGainCode(String channelId, int peqIndex, int code) throws Exception{
        return filters.setOutputPeqGainCode(channelId, peqIndex, code);
    }

    @Override
    public synchronized RawTxDto setOutputPeqType(String channelId, int peqIndex, String filterType) throws Exception{
        return filters.setOutputPeqType(channelId, peqIndex, filterType);
    }

    @Override
    public synchronized RawTxDto setOutputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception{
        return filters.setOutputPeqBypass(channelId, peqIndex, bypass);
    }

    @Override
    public synchronized RawTxDto setInputPeq(String channelId, int peqIndex, String filterType, double frequencyHz,
                                             double q, double gainDb, boolean bypass) throws Exception{
        return filters.setInputPeq(channelId, peqIndex, filterType, frequencyHz, q, gainDb, bypass);
    }

    @Override
    public synchronized RawTxDto setInputPeqFrequency(String channelId, int peqIndex, double frequencyHz) throws Exception{
        return filters.setInputPeqFrequency(channelId, peqIndex, frequencyHz);
    }

    @Override
    public synchronized RawTxDto setInputPeqQ(String channelId, int peqIndex, double q) throws Exception{
        return filters.setInputPeqQ(channelId, peqIndex, q);
    }

    @Override
    public synchronized RawTxDto setInputPeqGain(String channelId, int peqIndex, double gainDb) throws Exception{
        return filters.setInputPeqGain(channelId, peqIndex, gainDb);
    }

    @Override
    public synchronized RawTxDto setInputPeqType(String channelId, int peqIndex, String filterType) throws Exception{
        return filters.setInputPeqType(channelId, peqIndex, filterType);
    }

    @Override
    public synchronized RawTxDto setInputPeqBypass(String channelId, int peqIndex, boolean bypass) throws Exception{
        return filters.setInputPeqBypass(channelId, peqIndex, bypass);
    }

    @Override
    public synchronized List<RawTxDto> setAllPeqBypass(boolean bypass) throws Exception{
        return filters.setAllPeqBypass(bypass);
    }

    @Override
    public synchronized RawTxDto setInputGeq(String channelId, int bandIndex, double gainDb) throws Exception{
        return filters.setInputGeq(channelId, bandIndex, gainDb);
    }
    @Override
    public synchronized RawTxDto setInputGate(String channelId, double thresholdDb, double holdMs, double attackMs,
                                              double releaseMs) throws Exception{
        return dynamics.setInputGate(channelId, thresholdDb, holdMs, attackMs, releaseMs);
    }

    @Override
    public synchronized RawTxDto setInputGateThreshold(String channelId, double thresholdDb) throws Exception{
        return dynamics.setInputGateThreshold(channelId, thresholdDb);
    }

    @Override
    public synchronized RawTxDto setInputGateHold(String channelId, double holdMs) throws Exception{
        return dynamics.setInputGateHold(channelId, holdMs);
    }

    @Override
    public synchronized RawTxDto setInputGateAttack(String channelId, double attackMs) throws Exception{
        return dynamics.setInputGateAttack(channelId, attackMs);
    }

    @Override
    public synchronized RawTxDto setInputGateRelease(String channelId, double releaseMs) throws Exception{
        return dynamics.setInputGateRelease(channelId, releaseMs);
    }

    @Override
    public synchronized RawTxDto setCompressor(String inputId, double thresholdDb, String ratioLabel, double attackMs,
                                               double releaseMs, double kneeDb) throws Exception{
        return dynamics.setCompressor(inputId, thresholdDb, ratioLabel, attackMs, releaseMs, kneeDb);
    }

    @Override
    public synchronized RawTxDto setLimiter(String outputId, double thresholdDb, double attackMs, double releaseMs)
            throws Exception{
        RawTxDto result = dynamics.setLimiter(outputId, thresholdDb, attackMs, releaseMs);
        autoLimit.invalidateUndo();
        return result;
    }

    @Override
    public synchronized RawTxDto setTestToneSource(String source) throws Exception {
        return testTone.setSource(source);
    }

    @Override
    public synchronized RawTxDto setTestToneSineFrequency(double hz) throws Exception {
        return testTone.setSineFrequency(hz);
    }

    @Override
    public synchronized RawTxDto setTestToneSineFrequencyRaw(int selectorRaw) throws Exception {
        return testTone.setSineFrequencyRaw(selectorRaw);
    }

    @Override
    public synchronized RawTxDto disableTestTone() throws Exception {
        return testTone.disable();
    }

    @Override
    public synchronized StateDto requestRuntimeMeters() throws Exception {
        // The server-owned poller is the only source of fresh DSP meter requests.
        // Keep the legacy endpoint cache-only so old clients cannot flood the DSP.
        return stateQueries.state();
    }

    public synchronized <T> T withController(ControllerCallback<T> callback) throws Exception {
        return callback.execute(controller);
    }

    @FunctionalInterface
    public interface ControllerCallback<T> {
        T execute(DspController controller) throws Exception;
    }
    private void startCentralMeterPolling() {
        controller.startMeterPolling(runtimeSettings.meterPollIntervalMs());
    }

    double currentVolumeStepDb() {
        return runtimeSettings.volumeStepDb();
    }

    private void log(String message) {
        LOG.info("[DSP408] {}", message);
    }

    DspInstanceDto toInstanceDto(boolean defaultDevice) {
        DspConnectionConfig config = runtimeSettings.connectionConfig();
        return new DspInstanceDto(
                runtimeSettings.instanceId(),
                config.ip(),
                config.port(),
                defaultDevice,
                controller.isConnected(),
                stateQueries.deviceInfo()
        );
    }

}

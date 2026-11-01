package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.decode.DspPayloadRouter;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.library.MuteReadSpec;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspTrafficSnapshot;
import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.state.DspState;

public final class DspController {
	private static final String LIBRARY_AUTO = "auto";
	private static final String LIBRARY_DSP408 = "dsp408";
	private static final String LIBRARY_FIR408 = "fir408";

	private final DspState state = new DspState();
	private volatile DspLibrary library;
	private final DspChannelCommands channelCommands = new DspChannelCommands(
			state,
			this::requireClient,
			this::requireVerifiedWrite
	);
	private final DspPresetCommands presetCommands = new DspPresetCommands(this::requireClient);
	private final DspMatrixCommands matrixCommands = new DspMatrixCommands(state, this::requireClient);
	private final DspPeqCommands peqCommands = new DspPeqCommands(
			state,
			this::requireClient,
			() -> library,
			this::requireVerifiedWrite
	);
	private final DspCrossoverCommands crossoverCommands = new DspCrossoverCommands(
			state,
			this::requireClient,
			this::requireVerifiedWrite
	);
	private final DspDynamicsCommands dynamicsCommands = new DspDynamicsCommands(
			state,
			this::requireClient,
			() -> library,
			this::requireVerifiedWrite
	);
	private final DspFirCommands firCommands = new DspFirCommands(
			this::requireClient,
			() -> library,
			this::requireVerifiedWrite
	);
	private volatile String preferredLibraryType = LIBRARY_AUTO;

	private final Object lifecycleLock = new Object();
	private volatile DspClient client;
	private volatile boolean startupReady;
	private volatile DspPayloadRouter router;
	private volatile Consumer<String> activeLog;
	private volatile String deviceVersion;
	private volatile byte[] lastSystemInfoPayload;

	public DspController() {
		this(DspLibraryLoader.loadDefault());
	}

	public DspController(Path libraryPath) {
		this(DspLibraryLoader.load(libraryPath));
	}

	private DspController(DspLibrary library) {
		this.library = library;
	}

	public boolean isConnected() {
		DspClient current = client;
		return startupReady && current != null && current.isConnected();
	}

	public DspState state() {
		return state;
	}

	public String deviceVersion() {
		return deviceVersion;
	}

	public byte[] lastSystemInfoPayload() {
		return lastSystemInfoPayload == null ? null
				: Arrays.copyOf(lastSystemInfoPayload, lastSystemInfoPayload.length);
	}

	public String libraryType() {
		return library.libraryType();
	}

	public int maxParameterBlockIndex() {
		return library.maxParameterBlockIndex();
	}

	public boolean meterChannelMappingConfirmed() {
		return library.meterLayout().channelMappingConfirmed();
	}

	public boolean meterLevelCalibrationConfirmed() {
		return library.meterLayout().levelCalibrationConfirmed();
	}

	public boolean meterLimiterMappingConfirmed() {
		return library.meterLayout().limiterMappingConfirmed();
	}

	public boolean meterClipThresholdConfirmed(DspChannel channel) {
		return library.meterLayout().clipThresholdConfirmed(channel);
	}

	public boolean hasFirFeatures() {
		return library.hasFirFeatures();
	}

	public void setPreferredLibraryType(String libraryType) {
		String normalized = libraryType == null
				? LIBRARY_AUTO
				: libraryType.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			normalized = LIBRARY_AUTO;
		}
		if (!List.of(LIBRARY_AUTO, LIBRARY_DSP408, LIBRARY_FIR408).contains(normalized)) {
			throw new IllegalArgumentException("DSP library must be auto|dsp408|fir408: " + libraryType);
		}
        synchronized (lifecycleLock) {
            preferredLibraryType = normalized;
            if (client == null) {
                library = loadPreferredLibrary();
            }
        }
	}

	public void connect(DspConnectionConfig config, Consumer<String> log) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("DSP connection canceled");
        }
        DspClient candidate = new DspClient(config, log);
        DspClient previous;
        synchronized (lifecycleLock) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("DSP connection canceled");
            }
            previous = client;
            startupReady = false;
            client = candidate;
            state.reset();
            deviceVersion = null;
            lastSystemInfoPayload = null;
            activeLog = log;
            library = loadPreferredLibrary();
            router = createRouter(log);
            candidate.setPayloadListener(payload -> {
                synchronized (lifecycleLock) {
                    if (client == candidate) {
                        routePayload(payload);
                    }
                }
            });
        }
        if (previous != null) {
            previous.close();
        }
        try {
            ensureCurrentClient(candidate);
            candidate.connect(library.hasFirFeatures(), () -> client == candidate);
            ensureCurrentClient(candidate);
            candidate.sendObservedStartupSequence(library.hasFirFeatures());
            synchronized (lifecycleLock) {
                ensureCurrentClient(candidate);
                if (!candidate.isConnected()) {
                    throw new IOException("DSP connection closed during startup");
                }
                startupReady = true;
            }
        } catch (IOException | RuntimeException error) {
            synchronized (lifecycleLock) {
                if (client == candidate) {
                    startupReady = false;
                    client = null;
                    router = null;
                    activeLog = null;
                }
            }
            candidate.close();
            throw error;
        }
	}

    private void ensureCurrentClient(DspClient candidate) throws IOException {
        if (client != candidate || Thread.currentThread().isInterrupted()) {
            throw new IOException("DSP connection canceled");
        }
    }

	public void close() {
        DspClient previous;
        synchronized (lifecycleLock) {
            startupReady = false;
            previous = client;
            client = null;
            router = null;
            activeLog = null;
        }
        if (previous != null) {
            previous.close();
        }
	}

	public void sendHandshakeSequence() throws IOException {
		requireClient().sendHandshakeSequence();
	}

	public void sendKeepAlive() throws IOException {
		requireClient().sendKeepAlive();
	}

	public void requestSystemInfo() throws IOException {
		requireClient().requestSystemInfo();
	}

	public boolean requestRuntimeMeters() throws IOException {
		return requireClient().requestRuntimeMeters();
	}

	public void startMeterPolling(long intervalMs) {
		requireClient().startMeterPolling(intervalMs);
	}

	public void stopMeterPolling() {
		requireClient().stopMeterPolling();
	}

	public DspTrafficSnapshot trafficSnapshot() {
        DspClient current = client;
		if (current == null) {
			return new DspTrafficSnapshot(
					0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
					null, 0L, false, null
			);
		}
		return current.trafficSnapshot();
	}

	public void login(String pin) throws IOException {
		requireClient().login(pin);
	}

	public void setGain(DspChannel channel, double db) throws IOException {
		channelCommands.setGain(channel, db);
	}

	public void mute(DspChannel channel) throws IOException {
		setMute(channel, true);
	}

	public void unmute(DspChannel channel) throws IOException {
		setMute(channel, false);
	}

	public void setPhase(DspChannel channel, boolean inverted) throws IOException {
		channelCommands.setPhase(channel, inverted);
	}

	public void setDelay(DspChannel channel, double ms) throws IOException {
		channelCommands.setDelay(channel, ms);
	}

	public void sendRaw(byte[] payload) throws IOException {
		requireClient().sendPayload(payload);
	}

	public void readParameterBlock(int blockIndex) throws IOException {
		requireClient().readParameterBlock(blockIndex);
	}

	public void readPresetName(int presetIndex) throws IOException {
		presetCommands.readName(presetIndex);
	}

	public void loadPreset(int presetIndex) throws IOException {
		presetCommands.load(presetIndex);
	}

	public void loadPreset(String slotLabel) throws IOException {
		presetCommands.load(slotLabel);
	}

	public void setPresetName(String name) throws IOException {
		presetCommands.setName(name);
	}

	public void storePreset(int presetIndex) throws IOException {
		presetCommands.store(presetIndex);
	}

	public void storePreset(String slotLabel) throws IOException {
		presetCommands.store(slotLabel);
	}

	public void setDelayUnit(int unitRaw) throws IOException {
		channelCommands.setDelayUnit(unitRaw);
	}

	public void setDelayUnit(String unit) throws IOException {
		channelCommands.setDelayUnit(unit);
	}

	public void setChannelName(DspChannel channel, String name) throws IOException {
		channelCommands.setChannelName(channel, name);
	}

	public void setMute(DspChannel channel, boolean muted) throws IOException {
		channelCommands.setMute(channel, muted);
		mirrorMuteIntoCachedBlock(channel, muted);
	}

	private void mirrorMuteIntoCachedBlock(DspChannel channel, boolean muted) {
		MuteReadSpec spec = library.muteReadSpec();
		if (spec == null || channel == null) {
			return;
		}
		boolean input = channel.index() < DspChannel.OUT_1.index();
		int bitIndex = input ? channel.index() : channel.index() - DspChannel.OUT_1.index();
		if (bitIndex < 0 || bitIndex > 7) {
			return;
		}
		int dataOffset = input ? spec.inputDataOffset() : spec.outputDataOffset();
		state.patchCachedBlockBit(spec.blockIndex(), dataOffset, 1 << bitIndex, muted);
	}

	public void setOutputPeqFrequency(DspChannel channel, int peqIndex, double hz) throws IOException {
		peqCommands.setOutputPeqFrequency(channel, peqIndex, hz);
	}

	public void setOutputPeqQRaw(DspChannel channel, int peqIndex, int qRaw) throws IOException {
		peqCommands.setOutputPeqQRaw(channel, peqIndex, qRaw);
	}

	public void setOutputPeqGainCode(DspChannel channel, int peqIndex, int gainCode) throws IOException {
		peqCommands.setOutputPeqGainCode(channel, peqIndex, gainCode);
	}

	public void setOutputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		peqCommands.setOutputPeqQ(channel, peqIndex, q);
	}

	public void setOutputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		peqCommands.setOutputPeqGain(channel, peqIndex, gainDb);
	}

	public void setOutputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		peqCommands.setOutputPeqType(channel, peqIndex, filterType);
	}

	public void setOutputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb) throws IOException {
		peqCommands.setOutputPeq(channel, peqIndex, filterType, frequencyHz, q, gainDb);
	}

	public void setOutputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb, boolean bypass) throws IOException {
		peqCommands.setOutputPeq(channel, peqIndex, filterType, frequencyHz, q, gainDb, bypass);
	}

	public void setOutputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		peqCommands.setOutputPeqBypass(channel, peqIndex, bypass);
	}

	public void setInputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb, boolean bypass) throws IOException {
		peqCommands.setInputPeq(channel, peqIndex, filterType, frequencyHz, q, gainDb, bypass);
	}

	public void setInputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		peqCommands.setInputPeqGain(channel, peqIndex, gainDb);
	}

	public void setInputPeqFrequency(DspChannel channel, int peqIndex, double frequencyHz) throws IOException {
		peqCommands.setInputPeqFrequency(channel, peqIndex, frequencyHz);
	}

	public void setInputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		peqCommands.setInputPeqQ(channel, peqIndex, q);
	}

	public void setInputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		peqCommands.setInputPeqType(channel, peqIndex, filterType);
	}

	public void setInputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		peqCommands.setInputPeqBypass(channel, peqIndex, bypass);
	}

	/**
	 * Sets all 104 FIR408 PEQ bypass flags after validating the complete command state up front.
	 * No write is sent when any band is missing gain, frequency, Q, type or bypass readback.
	 */
	public void setAllPeqBypass(boolean bypass) throws IOException {
		peqCommands.setAllPeqBypass(bypass);
	}

	public void setInputGeq(DspChannel channel, int bandIndex, double gainDb) throws IOException {
		peqCommands.setInputGeq(channel, bandIndex, gainDb);
	}

	public void setFirProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
		firCommands.setProcessingMode(output, mode);
	}

	public void setFirGenerator(DspChannel output, FirFilterType type, FirWindowFunction window,
			double highPassFrequencyHz, double lowPassFrequencyHz, int taps) throws IOException {
		firCommands.setGenerator(output, type, window, highPassFrequencyHz, lowPassFrequencyHz, taps);
	}

	public List<byte[]> uploadExternalFir(DspChannel channel, String name, double[] coefficients) throws IOException {
		return firCommands.uploadExternalFir(channel, name, coefficients);
	}

	public void setInputGate(DspChannel channel, double thresholdDb, double holdMs, double attackMs, double releaseMs)
			throws IOException {
		dynamicsCommands.setInputGate(channel, thresholdDb, holdMs, attackMs, releaseMs);
	}

	public void setInputGateThreshold(DspChannel channel, double thresholdDb) throws IOException {
		dynamicsCommands.setInputGateThreshold(channel, thresholdDb);
	}

	public void setInputGateHold(DspChannel channel, double holdMs) throws IOException {
		dynamicsCommands.setInputGateHold(channel, holdMs);
	}

	public void setInputGateAttack(DspChannel channel, double attackMs) throws IOException {
		dynamicsCommands.setInputGateAttack(channel, attackMs);
	}

	public void setInputGateRelease(DspChannel channel, double releaseMs) throws IOException {
		dynamicsCommands.setInputGateRelease(channel, releaseMs);
	}

	public void setMatrixRoute(DspChannel output, DspChannel input) throws IOException {
		matrixCommands.setRoute(output, input);
	}

	public void setMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) throws IOException {
		matrixCommands.setRoutes(output, inputs);
	}

	public void setMatrixCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
		matrixCommands.setCrosspointGain(output, input, db);
	}

	public void setCrossoverHighPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		crossoverCommands.setHighPass(channel, frequencyHz, slope, bypass);
	}

	public void setCrossoverHighPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		crossoverCommands.setHighPassFrequency(channel, frequencyHz);
	}

	public void setCrossoverHighPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		crossoverCommands.setHighPassSlope(channel, slope);
	}

	public void setCrossoverHighPassBypass(DspChannel channel, boolean bypass) throws IOException {
		crossoverCommands.setHighPassBypass(channel, bypass);
	}

	public void setCrossoverLowPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		crossoverCommands.setLowPass(channel, frequencyHz, slope, bypass);
	}

	public void setCrossoverLowPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		crossoverCommands.setLowPassFrequency(channel, frequencyHz);
	}

	public void setCrossoverLowPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		crossoverCommands.setLowPassSlope(channel, slope);
	}

	public void setCrossoverLowPassBypass(DspChannel channel, boolean bypass) throws IOException {
		crossoverCommands.setLowPassBypass(channel, bypass);
	}

	public void setCompressor(DspChannel input, double thresholdDb, String ratioLabel, double attackMs,
			double releaseMs, double kneeDb) throws IOException {
		dynamicsCommands.setCompressor(input, thresholdDb, ratioLabel, attackMs, releaseMs, kneeDb);
	}

	public void setLimiter(DspChannel output, double thresholdDb, double attackMs, double releaseMs)
			throws IOException {
		dynamicsCommands.setLimiter(output, thresholdDb, attackMs, releaseMs);
	}

	public void setLimiter(DspChannel output,
			int attackRaw,
			int releaseRaw,
			int kneeRaw,
			int thresholdRaw) throws IOException {
		dynamicsCommands.setLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
	}

	public void enableTestTone(double frequencyHz) throws IOException {
		int sourceRaw = 0x03;
		int sineRaw = DspProtocol.findNearestTestToneSineSelector(frequencyHz);

		requireClient().setTestToneSource(sourceRaw);
		requireClient().setTestToneSineFrequencyRaw(sineRaw);

		state.markTestToneSource(sourceRaw, DspProtocol.testToneSourceLabel(sourceRaw), "tx");
		state.markTestToneSineFrequency(sineRaw, DspProtocol.testToneSineFrequencyHz(sineRaw), "tx");
	}

	public void enableTestTone(DspChannel output, double frequencyHz, double levelDb) throws IOException {
		requireOutputChannel(output);
		enableTestTone(frequencyHz);
	}

	public void disableTestTone() throws IOException {
		requireClient().setTestToneOff();
		state.markTestToneSource(0x00, DspProtocol.testToneSourceLabel(0x00), "tx");
	}

	public void scanParameterBlocks() throws IOException {
		for (int i = 0x00; i <= library.maxParameterBlockIndex(); i++) {
			readParameterBlockPaced(i);
		}
	}

	public void scanParameterBlocks(Iterable<Integer> blockIndices) throws IOException {
		if (blockIndices == null) {
			return;
		}
		for (Integer blockIndex : blockIndices) {
			if (blockIndex == null
					|| blockIndex < 0x00
					|| blockIndex > library.maxParameterBlockIndex()) {
				continue;
			}
			readParameterBlockPaced(blockIndex);
		}
	}

	private void readParameterBlockPaced(int blockIndex) throws IOException {
		readParameterBlock(blockIndex);
	}

	private DspClient requireClient() {
        DspClient current = client;
        if (!startupReady || current == null || !current.isConnected()) {
            throw new IllegalStateException("Not connected. Run 'connect' first.");
        }
        return current;
	}

	private void requireVerifiedWrite(Operation operation, DspChannel channel, String label) {
		if (!library.writeCapabilities().supports(operation, channel)) {
			throw new UnsupportedDspOperationException(
					label + " write is not verified for " + channel.displayName() + " in "
							+ library.libraryType()
							+ ". Use rawhex only for an explicit unsafe protocol test."
			);
		}
	}

	private void updateDeviceVersion(String version) {
		this.deviceVersion = version;
		selectLibraryForDeviceVersion(version);
	}

	private void updateSystemInfo(byte[] payload) {
		this.lastSystemInfoPayload = payload == null ? null : Arrays.copyOf(payload, payload.length);
	}

	private DspPayloadRouter createRouter(Consumer<String> log) {
		return new DspPayloadRouter(state, log, this::updateDeviceVersion, this::updateSystemInfo, library);
	}

	private void routePayload(byte[] payload) {
		if (payload != null
				&& payload.length == 46
				&& DspProtocol.isRuntimeMeterResponse(payload)
				&& !"fir408".equals(library.libraryType())) {
			if (deviceVersion == null || deviceVersion.isBlank()) {
				deviceVersion = "FIR408 (inferred from 46-byte cmd40)";
			}
			selectLibraryForDeviceVersion("FIR408");
		}

		DspPayloadRouter currentRouter = router;
		if (currentRouter != null) {
			currentRouter.onPayload(payload);
		}
	}

	private void selectLibraryForDeviceVersion(String version) {
		if (!LIBRARY_AUTO.equals(preferredLibraryType)) {
			return;
		}
		DspLibrary selected = DspLibraryLoader.loadForDeviceVersion(version);
		if (selected.libraryType().equals(library.libraryType())) {
			return;
		}

		library = selected;
		Consumer<String> log = activeLog;
		router = createRouter(log);
		if (log != null) {
			log.accept("DSP library selected: " + library.libraryType()
					+ " blocks=00.." + String.format("%02X", library.maxParameterBlockIndex()));
		}
	}

	private DspLibrary loadPreferredLibrary() {
		return LIBRARY_FIR408.equals(preferredLibraryType)
				? DspLibraryLoader.loadFir408()
				: DspLibraryLoader.loadDefault();
	}

	private static void requireOutputChannel(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

}

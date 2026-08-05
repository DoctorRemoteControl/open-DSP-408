package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.decode.DspPayloadRouter;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.library.OutputPeqBandDefaults;
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
import de.drremote.dsp408controller.core.state.CrossoverFilterState;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.InputGateState;
import de.drremote.dsp408controller.core.state.PeqBandState;

public final class DspController {
	private static final String OUTPUT_PEQ_INCOMPLETE_MESSAGE = "Band state incomplete. Run scanblocks first so output gain/frequency/Q/type/bypass are known. "
			+ "The filter type may use an explicit library default; bypass never falls back.";
	private static final String INPUT_PEQ_INCOMPLETE_MESSAGE = "Band state incomplete. Run scanblocks first so input gain/frequency/Q/type/bypass are known.";
	private static final String INPUT_GEQ_RANGE_MESSAGE = "Input GEQ band index must be in range 1..31";
	private static final String INPUT_GATE_INCOMPLETE_MESSAGE = "Gate state incomplete. Run scanblocks or use gateset first.";
	private static final String HIGH_PASS_INCOMPLETE_MESSAGE = "High-pass state incomplete. Run scanblocks or use xhpset first.";
	private static final String LOW_PASS_INCOMPLETE_MESSAGE = "Low-pass state incomplete. Output low-pass is readable, but input low-pass slope is not read back yet. Use xlpset first.";
	private static final int FIR_LIMITER_WRITE_ATTEMPTS = 3;
	private static final long FIR_LIMITER_WRITE_RETRY_DELAY_MS = 750L;
	private static final long PARAMETER_BLOCK_SCAN_INTERVAL_MS = 350L;
	private static final String LIBRARY_AUTO = "auto";
	private static final String LIBRARY_DSP408 = "dsp408";
	private static final String LIBRARY_FIR408 = "fir408";

	private final DspState state = new DspState();
	private DspLibrary library;
	private volatile String preferredLibraryType = LIBRARY_AUTO;

	private DspClient client;
	private DspPayloadRouter router;
	private Consumer<String> activeLog;
	private String deviceVersion;
	private byte[] lastSystemInfoPayload;

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
		return client != null && client.isConnected();
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
		preferredLibraryType = normalized;
		if (!isConnected()) {
			library = loadPreferredLibrary();
		}
	}

	public void connect(DspConnectionConfig config, Consumer<String> log) throws IOException {
		close();
		state.reset();
		deviceVersion = null;
		lastSystemInfoPayload = null;
		activeLog = log;
		library = loadPreferredLibrary();

		client = new DspClient(config, log);
		router = createRouter(log);
		client.setPayloadListener(this::routePayload);
		client.connect();
		sleepSilently(250);
	}

	public void close() {
		if (client != null) {
			client.close();
			client = null;
		}
		router = null;
		activeLog = null;
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
		if (client == null) {
			return new DspTrafficSnapshot(
					0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
					null, 0L, false, null
			);
		}
		return client.trafficSnapshot();
	}

	public void login(String pin) throws IOException {
		requireClient().login(pin);
	}

	public void setGain(DspChannel channel, double db) throws IOException {
		double clamped = DspProtocol.clampDb(db);
		requireClient().setGain(channel, clamped);
		state.markGain(channel, clamped, "tx");
	}

	public void mute(DspChannel channel) throws IOException {
		requireClient().mute(channel);
		state.markMuted(channel, true, "tx");
	}

	public void unmute(DspChannel channel) throws IOException {
		requireClient().unmute(channel);
		state.markMuted(channel, false, "tx");
	}

	public void setPhase(DspChannel channel, boolean inverted) throws IOException {
		requireVerifiedWrite(Operation.PHASE, channel, "Phase");
		requireClient().setPhase(channel, inverted);
		state.markPhase(channel, inverted, "tx");
	}

	public void setDelay(DspChannel channel, double ms) throws IOException {
		double clamped = DspCodecRegistry.clampDelayMs(ms);
		requireClient().setDelay(channel, clamped);
		state.markDelay(channel, clamped, "tx");
	}

	public void sendRaw(byte[] payload) throws IOException {
		requireClient().sendPayload(payload);
	}

	public void readParameterBlock(int blockIndex) throws IOException {
		requireClient().readParameterBlock(blockIndex);
	}

	public void readPresetName(int presetIndex) throws IOException {
		requireClient().readPresetName(presetIndex);
	}

	public void loadPreset(int presetIndex) throws IOException {
		requireClient().loadPreset(presetIndex);
	}

	public void loadPreset(String slotLabel) throws IOException {
		requireClient().loadPreset(slotLabel);
	}

	public void setPresetName(String name) throws IOException {
		requireClient().setPresetName(name);
	}

	public void storePreset(int presetIndex) throws IOException {
		requireClient().storePreset(presetIndex);
	}

	public void storePreset(String slotLabel) throws IOException {
		requireClient().storePreset(slotLabel);
	}

	public void setDelayUnit(int unitRaw) throws IOException {
		requireClient().setDelayUnit(unitRaw);
	}

	public void setDelayUnit(String unit) throws IOException {
		requireClient().setDelayUnit(unit);
	}

	public void setChannelName(DspChannel channel, String name) throws IOException {
		requireAnyChannel(channel);
		requireClient().setChannelName(channel, name);
	}

	public void setMute(DspChannel channel, boolean muted) throws IOException {
		requireAnyChannel(channel);
		requireClient().setMute(channel, muted);
		state.markMuted(channel, muted, "tx");
	}

	public void setOutputPeqFrequency(DspChannel channel, int peqIndex, double hz) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(hz));

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), normalizedFrequencyHz,
				resolved.q(), resolved.filterType(), resolved.bypass());

		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), normalizedFrequencyHz, resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setOutputPeqQRaw(DspChannel channel, int peqIndex, int qRaw) throws IOException {
		requireOutput(channel);
		setOutputPeqQ(channel, peqIndex, DspCodecRegistry.peqQ().rawToDouble(qRaw));
	}

	public void setOutputPeqGainCode(DspChannel channel, int peqIndex, int gainCode) throws IOException {
		requireOutput(channel);
		setOutputPeqGain(channel, peqIndex, DspCodecRegistry.peqGain().rawToDouble(gainCode));
	}

	public void setOutputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				normalizedQ, resolved.filterType(), resolved.bypass());

		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), normalizedQ,
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setOutputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedGain = DspCodecRegistry.peqGain().rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, normalizedGain, resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), resolved.bypass());

		state.markOutputPeqBand(channel, peqIndex, normalizedGain, resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setOutputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), filterType, resolved.bypass());

		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(), filterType,
				resolved.bypass(), "tx");
	}

	public void setOutputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		boolean bypass = requireKnownOutputPeqBypass(channel, peqIndex);
		setOutputPeq(channel, peqIndex, filterType, frequencyHz, q, gainDb, bypass);
	}

	public void setOutputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb, boolean bypass) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}

		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));
		double normalizedGain = DspCodecRegistry.peqGain().rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, normalizedGain, normalizedFrequencyHz,
				normalizedQ, filterType, bypass);

		state.markOutputPeqBand(channel, peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ, filterType,
				bypass, "tx");
	}

	public void setOutputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		requireOutput(channel);
		requireVerifiedWrite(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);

		requireClient().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), bypass);

		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), bypass, "tx");
	}

	public void setInputPeq(DspChannel channel, int peqIndex, PeqFilterType filterType, double frequencyHz, double q,
			double gainDb, boolean bypass) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);

		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));
		double normalizedGain = DspCodecRegistry.peqGain().rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		requireClient().setInputPeq(inputNumber(channel), peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ,
				filterType, bypass);

		state.markInputPeqBand(channel, peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ, filterType,
				bypass, "tx");
	}

	public void setInputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedGain = DspCodecRegistry.peqGain().rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		requireClient().setInputPeq(inputNumber(channel), peqIndex, normalizedGain, resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), resolved.bypass());

		state.markInputPeqBand(channel, peqIndex, normalizedGain, resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setInputPeqFrequency(DspChannel channel, int peqIndex, double frequencyHz) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));

		requireClient().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), normalizedFrequencyHz,
				resolved.q(), resolved.filterType(), resolved.bypass());

		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), normalizedFrequencyHz, resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setInputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));

		requireClient().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				normalizedQ, resolved.filterType(), resolved.bypass());

		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), normalizedQ,
				resolved.filterType(), resolved.bypass(), "tx");
	}

	public void setInputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);

		requireClient().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), filterType, resolved.bypass());

		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(), filterType,
				resolved.bypass(), "tx");
	}

	public void setInputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);

		requireClient().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), bypass);

		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), bypass, "tx");
	}

	/**
	 * Sets all 104 FIR408 PEQ bypass flags after validating the complete command state up front.
	 * No write is sent when any band is missing gain, frequency, Q, type or bypass readback.
	 */
	public void setAllPeqBypass(boolean bypass) throws IOException {
		List<DspChannel> inputs = List.of(
				DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D);
		List<DspChannel> outputs = List.of(
				DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
				DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8);

		for (DspChannel input : inputs) {
			requireVerifiedWrite(Operation.INPUT_PEQ, input, "Input PEQ");
			for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
				requireKnownInputPeqBand(input, peqIndex);
			}
		}
		for (DspChannel output : outputs) {
			requireVerifiedWrite(Operation.OUTPUT_PEQ, output, "Output PEQ");
			for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
				requireKnownOutputPeqBand(output, peqIndex);
			}
		}

		for (DspChannel input : inputs) {
			for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
				setInputPeqBypass(input, peqIndex, bypass);
			}
		}
		for (DspChannel output : outputs) {
			for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
				setOutputPeqBypass(output, peqIndex, bypass);
			}
		}
	}

	public void setInputGeq(DspChannel channel, int bandIndex, double gainDb) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GEQ, channel, "Input GEQ");
		checkInputGeqBandIndex(bandIndex);

		double normalizedGain = DspCodecRegistry.peqGain().rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));
		double frequencyHz = requireKnownInputGeqFrequency(bandIndex);

		requireClient().setInputGeq(inputNumber(channel), bandIndex, normalizedGain);
		state.markInputGeqBand(channel, bandIndex, normalizedGain, frequencyHz, "tx");
	}

	public void setFirProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
		requireFirLibrary();
		requireOutputChannel(output);
		requireVerifiedWrite(Operation.FIR_PROCESSING_MODE, output, "FIR processing mode");
		requireClient().setFirProcessingMode(output, mode);
	}

	public void setFirGenerator(DspChannel output, FirFilterType type, FirWindowFunction window,
			double highPassFrequencyHz, double lowPassFrequencyHz, int taps) throws IOException {
		requireFirLibrary();
		requireOutputChannel(output);
		requireVerifiedWrite(Operation.FIR_GENERATOR, output, "FIR generator");

		double normalizedHighPass = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(highPassFrequencyHz));
		double normalizedLowPass = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(lowPassFrequencyHz));
		int normalizedTaps = DspProtocol.firTapRawToCount(DspProtocol.firTapCountToRaw(taps));

		requireClient().setFirGenerator(output, type, window, normalizedHighPass, normalizedLowPass, normalizedTaps);
	}

	public List<byte[]> uploadExternalFir(DspChannel channel, String name, double[] coefficients) throws IOException {
		requireFirLibrary();
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.EXTERNAL_FIR_UPLOAD, channel, "External FIR upload");

		boolean includeBeginCommand = channel.index() < 4;
		List<byte[]> payloads = DspProtocol.buildExternalFirUpload(channel, name, coefficients, includeBeginCommand);
		DspClient activeClient = requireClient();
		for (byte[] payload : payloads) {
			activeClient.sendPayload(payload);
		}
		return payloads;
	}

	public void setInputGate(DspChannel channel, double thresholdDb, double holdMs, double attackMs, double releaseMs)
			throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GATE, channel, "Input gate");

		double normalizedThresholdDb = DspCodecRegistry
				.rawToDynamicsThresholdDb(DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 0.0));
		double normalizedHoldMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(holdMs, 10.0, 999.0));
		double normalizedAttackMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));

		requireClient().setInputGate(inputNumber(channel), normalizedThresholdDb, normalizedHoldMs, normalizedAttackMs,
				normalizedReleaseMs);

		state.markInputGate(channel, normalizedThresholdDb, normalizedHoldMs, normalizedAttackMs, normalizedReleaseMs,
				"tx");
	}

	public void setInputGateThreshold(DspChannel channel, double thresholdDb) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, thresholdDb, resolved.holdMs(), resolved.attackMs(), resolved.releaseMs());
	}

	public void setInputGateHold(DspChannel channel, double holdMs) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), holdMs, resolved.attackMs(), resolved.releaseMs());
	}

	public void setInputGateAttack(DspChannel channel, double attackMs) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), resolved.holdMs(), attackMs, resolved.releaseMs());
	}

	public void setInputGateRelease(DspChannel channel, double releaseMs) throws IOException {
		requireInput(channel);
		requireVerifiedWrite(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), resolved.holdMs(), resolved.attackMs(), releaseMs);
	}

	public void setMatrixRoute(DspChannel output, DspChannel input) throws IOException {
		setMatrixRoutes(output, java.util.List.of(input));
	}

	public void setMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) throws IOException {
		requireOutputChannel(output);
		if (inputs == null) {
			throw new IllegalArgumentException("Matrix inputs are required");
		}
		for (DspChannel input : inputs) {
			requireInput(input);
		}
		requireClient().setMatrixRoutes(output, inputs);
		state.markMatrixRoutes(output, inputs);
	}

	public void setMatrixCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
		requireOutputChannel(output);
		requireInput(input);

		double clamped = Math.max(-60.0, Math.min(0.0, db));
		requireClient().setMatrixCrosspointGain(output, input, clamped);
		state.markMatrixCrosspointGain(output, input, clamped);
	}

	public void setCrossoverHighPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		requireClient().setCrossoverHighPass(channel, normalizedFrequencyHz, slope, bypass);
		state.markCrossoverHighPass(channel, normalizedFrequencyHz, slope, bypass, "tx");
	}

	public void setCrossoverHighPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		requireClient().setCrossoverHighPass(channel, normalizedFrequencyHz, resolvedSlopeForWrite(resolved),
				resolved.bypass());
		state.markCrossoverHighPass(channel, normalizedFrequencyHz, resolved.slope(), resolved.bypass(), "tx");
	}

	public void setCrossoverHighPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		requireClient().setCrossoverHighPass(channel, resolved.frequencyHz(), slope, resolved.bypass());
		state.markCrossoverHighPass(channel, resolved.frequencyHz(), slope, resolved.bypass(), "tx");
	}

	public void setCrossoverHighPassBypass(DspChannel channel, boolean bypass) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		requireClient().setCrossoverHighPass(channel, resolved.frequencyHz(), resolvedSlopeForWrite(resolved), bypass);
		state.markCrossoverHighPass(channel, resolved.frequencyHz(), resolved.slope(), bypass, "tx");
	}

	public void setCrossoverLowPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		requireClient().setCrossoverLowPass(channel, normalizedFrequencyHz, slope, bypass);
		state.markCrossoverLowPass(channel, normalizedFrequencyHz, slope, bypass, "tx");
	}

	public void setCrossoverLowPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		requireClient().setCrossoverLowPass(channel, normalizedFrequencyHz, resolvedSlopeForWrite(resolved),
				resolved.bypass());
		state.markCrossoverLowPass(channel, normalizedFrequencyHz, resolved.slope(), resolved.bypass(), "tx");
	}

	public void setCrossoverLowPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		requireClient().setCrossoverLowPass(channel, resolved.frequencyHz(), slope, resolved.bypass());
		state.markCrossoverLowPass(channel, resolved.frequencyHz(), slope, resolved.bypass(), "tx");
	}

	public void setCrossoverLowPassBypass(DspChannel channel, boolean bypass) throws IOException {
		requireAnyChannel(channel);
		requireVerifiedWrite(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		requireClient().setCrossoverLowPass(channel, resolved.frequencyHz(), resolvedSlopeForWrite(resolved), bypass);
		state.markCrossoverLowPass(channel, resolved.frequencyHz(), resolved.slope(), bypass, "tx");
	}

	public void setCompressor(DspChannel input, double thresholdDb, String ratioLabel, double attackMs,
			double releaseMs, double kneeDb) throws IOException {
		requireInput(input);
		requireVerifiedWrite(Operation.INPUT_COMPRESSOR, input, "Input compressor");
		CompressorState existing = requireKnownInputCompressor(input);

		int ratioRaw = DspProtocol.compressorRatioLabelToRaw(ratioLabel);
		String normalizedRatioLabel = library.compressorRatioName(ratioRaw);
		if (normalizedRatioLabel == null || normalizedRatioLabel.isBlank()) {
			normalizedRatioLabel = "raw=" + ratioRaw;
		}

		double normalizedAttackMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));
		double normalizedKneeDb = Math.max(0.0, Math.min(12.0, Math.round(kneeDb)));
		double normalizedThresholdDb = DspCodecRegistry
				.rawToDynamicsThresholdDb(DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0));

		requireClient().setInputCompressor(
				input,
				ratioRaw,
				DspCodecRegistry.dynamicsTimeMsToRaw(normalizedAttackMs, 1.0, 999.0),
				DspCodecRegistry.dynamicsTimeMsToRaw(normalizedReleaseMs, 10.0, 3000.0),
				(int) Math.round(normalizedKneeDb),
				DspCodecRegistry.dynamicsThresholdDbToRaw(normalizedThresholdDb, -90.0, 20.0),
				DspCodecRegistry.peqFrequency().doubleToRaw(existing.frequencyHz()),
				existing.typeContextRaw(),
				existing.typeRaw()
		);

		state.markCompressor(input, ratioRaw, normalizedRatioLabel, normalizedAttackMs, normalizedReleaseMs,
				normalizedKneeDb, normalizedThresholdDb, existing.frequencyHz(), existing.typeContextRaw(),
				existing.typeRaw(), existing.typeLabel(), "tx");
	}

	public void setLimiter(DspChannel output, double thresholdDb, double attackMs, double releaseMs)
			throws IOException {
		requireOutputChannel(output);

		int kneeRaw = resolveLimiterKneeRaw(output);
		double normalizedAttackMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry
				.rawToDynamicsTimeMs(DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));
		double normalizedThresholdDb = DspCodecRegistry
				.rawToDynamicsThresholdDb(DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0));

		DspClient activeClient = requireClient();
		int attempts = library.hasFirFeatures() ? FIR_LIMITER_WRITE_ATTEMPTS : 1;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			activeClient.setLimiter(output, normalizedAttackMs, normalizedReleaseMs, kneeRaw, normalizedThresholdDb);
			if (attempt < attempts) {
				sleepSilently(FIR_LIMITER_WRITE_RETRY_DELAY_MS);
			}
		}

		state.markLimiter(output, normalizedAttackMs, normalizedReleaseMs, kneeRaw, normalizedThresholdDb, "tx");
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
		/*
		 * FIR408 suppresses replies when block requests arrive at the generic
		 * 150 ms command-queue cadence. 350 ms preserves responses during a
		 * physical dump.
		 */
		sleepSilently(PARAMETER_BLOCK_SCAN_INTERVAL_MS);
	}

	private DspClient requireClient() {
		if (client == null || !client.isConnected()) {
			throw new IllegalStateException("Not connected. Run 'connect' first.");
		}
		return client;
	}

	private void requireFirLibrary() {
		if (!library.hasFirFeatures()) {
			throw new UnsupportedDspOperationException("FIR features are not available in " + library.libraryType());
		}
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
		if (client != null) {
			client.setPayloadListener(this::routePayload);
		}
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

	private static void sleepSilently(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void requireOutput(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("PEQ write is only supported for output channels Out1..Out8");
		}
	}

	private static void requireOutputChannel(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

	private static void requireInput(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 3) {
			throw new IllegalArgumentException("Input channel required: InA..InD");
		}
	}

	private static void requireAnyChannel(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 11) {
			throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
		}
	}

	private static int outputNumber(DspChannel channel) {
		return channel.index() - 3;
	}

	private static int inputNumber(DspChannel channel) {
		return channel.index();
	}

	private PeqBandState requireKnownOutputPeqBand(DspChannel channel, int peqIndex) {
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}

		PeqBandState existing = state.outputPeq(channel).band(peqIndex);
		if (existing == null || existing.gainDb() == null || existing.frequencyHz() == null || existing.q() == null
				|| existing.bypass() == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}

		PeqFilterType resolvedFilterType = existing.filterType();
		boolean typeConfirmed = existing.typeConfirmedFromDevice();
		String typeSource = existing.lastTypeUpdateSource();

		if (resolvedFilterType == null) {
			OutputPeqBandDefaults defaults = library.outputPeqDefault(peqIndex);
			if (defaults != null) {
				resolvedFilterType = defaults.filterType();
				typeConfirmed = false;
				typeSource = "library-default";
			}
		}

		if (resolvedFilterType == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}

		PeqBandState resolved = new PeqBandState(peqIndex);
		resolved.updateGain(existing.gainDb(), existing.lastGainUpdateSource(), existing.gainConfirmedFromDevice());
		resolved.updateFrequency(existing.frequencyHz(), existing.lastFrequencyUpdateSource(),
				existing.frequencyConfirmedFromDevice());
		resolved.updateQ(existing.q(), existing.lastQUpdateSource(), existing.qConfirmedFromDevice());
		resolved.updateFilterType(resolvedFilterType, typeSource, typeConfirmed);
		resolved.updateBypass(existing.bypass(), existing.lastBypassUpdateSource(),
				existing.bypassConfirmedFromDevice());

		return resolved;
	}

	private boolean requireKnownOutputPeqBypass(DspChannel channel, int peqIndex) {
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}
		PeqBandState existing = state.outputPeq(channel).band(peqIndex);
		if (existing == null || existing.bypass() == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}
		return existing.bypass();
	}

	private PeqBandState requireKnownInputPeqBand(DspChannel channel, int peqIndex) {
		checkInputPeqIndex(peqIndex);

		PeqBandState existing = state.inputPeq(channel).band(peqIndex);
		if (existing == null || existing.gainDb() == null || existing.frequencyHz() == null || existing.q() == null
				|| existing.filterType() == null || existing.bypass() == null) {
			throw new IllegalStateException(INPUT_PEQ_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CrossoverFilterState requireKnownHighPass(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).highPass();
		if (existing.frequencyHz() == null || existing.bypass() == null
				|| (!existing.bypass() && existing.slope() == null)) {
			throw new IllegalStateException(HIGH_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CrossoverFilterState requireKnownHighPassFrequency(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).highPass();
		if (existing.frequencyHz() == null) {
			throw new IllegalStateException(HIGH_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CrossoverFilterState requireKnownLowPass(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).lowPass();
		if (existing.frequencyHz() == null || existing.bypass() == null
				|| (!existing.bypass() && existing.slope() == null)) {
			throw new IllegalStateException(LOW_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CrossoverFilterState requireKnownLowPassFrequency(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).lowPass();
		if (existing.frequencyHz() == null) {
			throw new IllegalStateException(LOW_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private static CrossoverSlope resolvedSlopeForWrite(CrossoverFilterState state) {
		if (Boolean.TRUE.equals(state.bypass())) {
			return state.slope() != null ? state.slope() : CrossoverSlope.LR_48;
		}
		if (state.slope() == null) {
			throw new IllegalStateException("Crossover slope is required when bypass is off");
		}
		return state.slope();
	}

	private static void checkInputPeqIndex(int peqIndex) {
		if (peqIndex < 1 || peqIndex > 8) {
			throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
		}
	}

	private static void checkInputGeqBandIndex(int bandIndex) {
		if (bandIndex < 1 || bandIndex > 31) {
			throw new IllegalArgumentException(INPUT_GEQ_RANGE_MESSAGE);
		}
	}

	private InputGateState requireKnownInputGate(DspChannel channel) {
		InputGateState existing = state.inputGate(channel);
		if (existing == null || existing.thresholdDb() == null || existing.holdMs() == null
				|| existing.attackMs() == null || existing.releaseMs() == null) {
			throw new IllegalStateException(INPUT_GATE_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CompressorState requireKnownInputCompressor(DspChannel channel) {
		CompressorState existing = state.compressor(channel);
		if (existing == null || existing.frequencyHz() == null || existing.typeContextRaw() == null
				|| existing.typeRaw() == null || existing.typeLabel() == null) {
			throw new IllegalStateException(
					"Input compressor state incomplete. Run scanblocks first so frequency and type are preserved."
			);
		}
		return existing;
	}

	private double requireKnownInputGeqFrequency(int bandIndex) {
		Double frequencyHz = library.inputGeqBandFrequency(bandIndex);
		if (frequencyHz == null) {
			throw new IllegalStateException("Input GEQ band frequency is unknown for band " + bandIndex);
		}
		return frequencyHz;
	}
	
	private int resolveLimiterKneeRaw(DspChannel output) {
		Double known = state.limiter(output) == null ? null : state.limiter(output).kneeDb();
		return known == null || known < 0 || known > 12 ? 0 : (int) Math.round(known);
	}

}

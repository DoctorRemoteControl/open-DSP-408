package de.drremote.dsp408controller.core.net;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

public final class DspClient implements Closeable {
    private final DspConnectionConfig config;
    private final Consumer<String> log;
    private volatile long connectionGeneration;
    private volatile boolean handshakeReady;
    private boolean connecting;

	private final DspSocketSession socketSession;
	private final DspBackgroundTasks backgroundTasks;
	private final DspCommandDispatcher commandDispatcher;
	private final DspSessionCommands sessionCommands;
	private final DspLevelRoutingPayloadCommands levelRoutingCommands;
	private final DspFilterPayloadCommands filterCommands;
	private final DspDynamicsPayloadCommands dynamicsCommands;

    public DspClient(DspConnectionConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log;
		this.socketSession = new DspSocketSession(config, log);
		this.commandDispatcher = new DspCommandDispatcher(
				socketSession::isRunning,
				socketSession::isConnected,
				socketSession::writeFrame,
				stillCurrent -> {
                    synchronized (this) {
                        if (stillCurrent.getAsBoolean()) {
                            internalClose(true);
                        }
                    }
                },
				this::log
		);
		this.sessionCommands = new DspSessionCommands(commandDispatcher, log);
		this.levelRoutingCommands = new DspLevelRoutingPayloadCommands(commandDispatcher);
		this.filterCommands = new DspFilterPayloadCommands(commandDispatcher);
		this.dynamicsCommands = new DspDynamicsPayloadCommands(commandDispatcher);
		this.backgroundTasks = new DspBackgroundTasks(
				socketSession::isRunning,
				this::sendKeepAlive,
				this::requestRuntimeMeters,
				this::log
		);
		socketSession.setResponseListener((token, payload) -> {
            synchronized (this) {
                if (socketSession.owns(token)) {
                    commandDispatcher.signalResponse(payload);
                }
            }
        });
		socketSession.setDisconnectHandler(token -> {
            synchronized (this) {
                if (socketSession.owns(token)) {
                    internalClose(true);
                }
            }
        });
    }

    public boolean isConnected() {
		return handshakeReady && socketSession.isConnected();
    }

    public void connect() throws IOException {
        connect(false);
    }

    public void connect(boolean preferFirSessionReady) throws IOException {
        connect(preferFirSessionReady, () -> true);
    }

    /** Connects only while the caller still owns this connection attempt. */
    public void connect(boolean preferFirSessionReady, BooleanSupplier attemptActive) throws IOException {
        if (Thread.currentThread().isInterrupted() || !attemptActive.getAsBoolean()) {
            throw new IOException("DSP connection canceled");
        }
        final long attempt;
        synchronized (this) {
            if (connecting || socketSession.isConnected()) {
                throw new IllegalStateException("Already connected or connecting");
            }
            if (!attemptActive.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("DSP connection canceled");
            }
            internalClose(false);
            attempt = ++connectionGeneration;
            connecting = true;
        }
        try {
            socketSession.open(() -> connectionGeneration == attempt && attemptActive.getAsBoolean());
            synchronized (this) {
                ensureCurrentAttempt(attempt);
                commandDispatcher.open();
                socketSession.startReader();
                commandDispatcher.start(socketSession.frameWriter());
            }
            log("Connected to " + config.ip() + ":" + config.port());
            boolean sessionReadyReceived = socketSession.awaitSessionReadyMarker();
            if (preferFirSessionReady && !sessionReadyReceived) {
                log("FIR408 did not emit 0xBE on this TCP session; "
                        + "continuing with the observed warm-reconnect handshake.");
            }
            ensureCurrentAttempt(attempt);
            sendHandshakeSequence();
            synchronized (this) {
                ensureCurrentAttempt(attempt);
                if (!socketSession.isConnected() || !attemptActive.getAsBoolean()) {
                    throw new IOException("DSP connection closed during handshake");
                }
                startKeepAlive();
                handshakeReady = true;
            }
        } catch (IOException | RuntimeException error) {
            synchronized (this) {
                if (connectionGeneration == attempt) {
                    internalClose(false);
                }
            }
            throw error;
        } finally {
            synchronized (this) {
                connecting = false;
            }
        }
    }

    private void ensureCurrentAttempt(long attempt) throws IOException {
        if (connectionGeneration != attempt || Thread.currentThread().isInterrupted()) {
            throw new IOException("DSP connection canceled");
        }
    }

    public void sendHandshakeSequence() throws IOException {
		sessionCommands.sendHandshakeSequence();
    }

    public void sendObservedStartupSequence(boolean firDevice) throws IOException {
		sessionCommands.sendObservedStartupSequence(firDevice);
    }

    public void sendKeepAlive() throws IOException {
		sessionCommands.sendKeepAlive();
    }

    public void requestSystemInfo() throws IOException {
		sessionCommands.requestSystemInfo();
    }

    public boolean requestRuntimeMeters() throws IOException {
		return sessionCommands.requestRuntimeMeters();
    }

    public void readParameterBlock(int blockIndex) throws IOException {
		sessionCommands.readParameterBlock(blockIndex);
    }

    public void readPresetName(int presetIndex) throws IOException {
		sessionCommands.readPresetName(presetIndex);
    }

    public void loadPreset(int presetIndex) throws IOException {
		sessionCommands.loadPreset(presetIndex);
    }

    public void loadPreset(String slotLabel) throws IOException {
		sessionCommands.loadPreset(slotLabel);
    }

    public void setPresetName(String name) throws IOException {
		sessionCommands.setPresetName(name);
    }

    public void storePreset(int presetIndex) throws IOException {
		sessionCommands.storePreset(presetIndex);
    }

    public void storePreset(String slotLabel) throws IOException {
		sessionCommands.storePreset(slotLabel);
    }

    public synchronized void startMeterPolling(long intervalMs) {
		backgroundTasks.startMeterPolling(intervalMs);
    }

    public synchronized void stopMeterPolling() {
		backgroundTasks.stopMeterPolling();
        commandDispatcher.cancelPendingMeters();
    }

    public void login(String pin) throws IOException {
		sessionCommands.login(pin);
    }

    public void setDelayUnit(int unitRaw) throws IOException {
		levelRoutingCommands.setDelayUnit(unitRaw);
    }

    public void setDelayUnit(String unit) throws IOException {
		levelRoutingCommands.setDelayUnit(unit);
    }

    public void setChannelName(DspChannel channel, String name) throws IOException {
		levelRoutingCommands.setChannelName(channel, name);
    }

    public void setGain(int channelIndex, double db) throws IOException {
		levelRoutingCommands.setGain(channelIndex, db);
    }

    public void setGain(DspChannel channel, double db) throws IOException {
        setGain(channel.index(), db);
    }

    public void setMute(int selector, boolean muted) throws IOException {
		levelRoutingCommands.setMute(selector, muted);
    }

    public void setMute(DspChannel channel, boolean muted) throws IOException {
        setMute(channel.index(), muted);
    }

    public void mute(DspChannel channel) throws IOException {
        setMute(channel.index(), true);
    }

    public void unmute(DspChannel channel) throws IOException {
        setMute(channel.index(), false);
    }

    public void setPhase(int channelIndex, boolean inverted) throws IOException {
		levelRoutingCommands.setPhase(channelIndex, inverted);
    }

    public void setPhase(DspChannel channel, boolean inverted) throws IOException {
        setPhase(channel.index(), inverted);
    }

    public void setDelay(int channelIndex, double ms) throws IOException {
		levelRoutingCommands.setDelay(channelIndex, ms);
    }

    public void setDelay(DspChannel channel, double ms) throws IOException {
        setDelay(channel.index(), ms);
    }

    public void setMatrixRoute(DspChannel output, DspChannel input) throws IOException {
		levelRoutingCommands.setMatrixRoute(output, input);
    }

    public void setMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) throws IOException {
		levelRoutingCommands.setMatrixRoutes(output, inputs);
    }

    public void setMatrixCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
		levelRoutingCommands.setMatrixCrosspointGain(output, input, db);
    }

    public void setCrossoverHighPass(DspChannel channel,
                                     double frequencyHz,
                                     CrossoverSlope slope,
                                     boolean bypass) throws IOException {
		filterCommands.setCrossoverHighPass(channel, frequencyHz, slope, bypass);
    }

    public void setCrossoverLowPass(DspChannel channel,
                                    double frequencyHz,
                                    CrossoverSlope slope,
                                    boolean bypass) throws IOException {
		filterCommands.setCrossoverLowPass(channel, frequencyHz, slope, bypass);
    }

    public void setOutputPeqFrequency(int outputNumber, int peqIndex, double hz) throws IOException {
		filterCommands.setOutputPeqFrequency(outputNumber, peqIndex, hz);
    }

    public void setOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) throws IOException {
		filterCommands.setOutputPeqQRaw(outputNumber, peqIndex, qRaw);
    }

    public void setOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) throws IOException {
		filterCommands.setOutputPeqGainCode(outputNumber, peqIndex, gainCode);
    }

    public void setOutputPeq(int outputNumber,
                             int peqIndex,
                             double gainDb,
                             double frequencyHz,
                             double q,
                             PeqFilterType filterType) throws IOException {
		filterCommands.setOutputPeq(
				outputNumber,
				peqIndex,
				gainDb,
				frequencyHz,
				q,
				filterType,
				false
		);
    }

    public void setOutputPeq(int outputNumber,
                             int peqIndex,
                             double gainDb,
                             double frequencyHz,
                             double q,
                             PeqFilterType filterType,
                             boolean bypass) throws IOException {
		filterCommands.setOutputPeq(
                outputNumber,
                peqIndex,
                gainDb,
                frequencyHz,
                q,
                filterType,
                bypass
        );
    }

    public void setInputPeq(int inputChannelIndex,
                            int peqIndex,
                            double gainDb,
                            double frequencyHz,
                            double q,
                            PeqFilterType filterType,
                            boolean bypass) throws IOException {
		filterCommands.setInputPeq(
                inputChannelIndex,
                peqIndex,
                gainDb,
                frequencyHz,
                q,
                filterType,
                bypass
        );
    }

    public void setInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) throws IOException {
		filterCommands.setInputPeqGain(inputChannelIndex, peqIndex, gainDb);
    }

    public void setInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) throws IOException {
		filterCommands.setInputPeqFrequency(inputChannelIndex, peqIndex, frequencyHz);
    }

    public void setInputPeqQ(int inputChannelIndex, int peqIndex, double q) throws IOException {
		filterCommands.setInputPeqQ(inputChannelIndex, peqIndex, q);
    }

    public void setInputPeqType(int inputChannelIndex, int peqIndex, PeqFilterType filterType) throws IOException {
		filterCommands.setInputPeqType(inputChannelIndex, peqIndex, filterType);
    }

    public void setInputPeqBypass(int inputChannelIndex, int peqIndex, boolean bypass) throws IOException {
		filterCommands.setInputPeqBypass(inputChannelIndex, peqIndex, bypass);
    }

    public void setInputGeq(int inputChannelIndex, int bandIndex, double gainDb) throws IOException {
		filterCommands.setInputGeq(inputChannelIndex, bandIndex, gainDb);
    }

    public void setFirProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
		filterCommands.setFirProcessingMode(output, mode);
    }

    public void setFirGenerator(DspChannel output,
                                FirFilterType type,
                                FirWindowFunction window,
                                double highPassFrequencyHz,
                                double lowPassFrequencyHz,
                                int taps) throws IOException {
		filterCommands.setFirGenerator(
                output,
                type,
                window,
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        );
    }

    public void uploadExternalFir(DspChannel channel,
                                  String name,
                                  double[] coefficients,
                                  boolean includeBeginCommand) throws IOException {
		filterCommands.uploadExternalFir(channel, name, coefficients, includeBeginCommand);
    }

    public void setInputGate(int inputChannelIndex,
                             double thresholdDb,
                             double holdMs,
                             double attackMs,
                             double releaseMs) throws IOException {
		dynamicsCommands.setInputGate(inputChannelIndex, thresholdDb, holdMs, attackMs, releaseMs);
    }

    public void setCompressor(DspChannel output,
                              String ratioLabel,
                              double attackMs,
                              double releaseMs,
                              double kneeDb,
                              double thresholdDb) throws IOException {
		dynamicsCommands.setCompressor(
                output,
                ratioLabel,
                attackMs,
                releaseMs,
                kneeDb,
                thresholdDb
        );
    }

    public void setInputCompressor(DspChannel input,
                                   int ratioRaw,
                                   int attackRaw,
                                   int releaseRaw,
                                   int kneeRaw,
                                   int thresholdRaw,
                                   int frequencyRaw,
                                   int typeContextRaw,
                                   int typeRaw) throws IOException {
		dynamicsCommands.setInputCompressor(
                input,
                ratioRaw,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw,
                frequencyRaw,
                typeContextRaw,
                typeRaw
        );
    }

    public void setCompressor(DspChannel output,
                              int ratioRaw,
                              int attackRaw,
                              int releaseRaw,
                              int kneeRaw,
                              int thresholdRaw) throws IOException {
		dynamicsCommands.setCompressor(
                output,
                ratioRaw,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw
        );
    }

    public void setLimiter(DspChannel output,
                           double attackMs,
                           double releaseMs,
                           int kneeRaw,
                           double thresholdDb) throws IOException {
		dynamicsCommands.setLimiter(
                output,
                attackMs,
                releaseMs,
                kneeRaw,
                thresholdDb
        );
    }

    public void setLimiter(DspChannel output,
                           int attackRaw,
                           int releaseRaw,
                           int kneeRaw,
                           int thresholdRaw) throws IOException {
		dynamicsCommands.setLimiter(
                output,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw
        );
    }

    public void setTestToneSource(int sourceIndex) throws IOException {
		dynamicsCommands.setTestToneSource(sourceIndex);
    }

    public void setTestToneSource(String source) throws IOException {
		dynamicsCommands.setTestToneSource(source);
    }

    public void setTestToneSineFrequencyRaw(int selectorRaw) throws IOException {
		dynamicsCommands.setTestToneSineFrequencyRaw(selectorRaw);
    }

    public void setTestToneSineFrequencyHz(double hz) throws IOException {
		dynamicsCommands.setTestToneSineFrequencyHz(hz);
    }

    public void setTestToneOff() throws IOException {
		dynamicsCommands.setTestToneOff();
    }

    public void setPayloadListener(Consumer<byte[]> listener) {
		socketSession.setPayloadListener(listener);
    }

    public boolean sendPayload(byte[] payload) throws IOException {
		return commandDispatcher.send(payload);
    }

    /** Reserves the next exchange or rejects definitely before transmission. */
    public boolean sendPayloadInteractive(byte[] payload) throws IOException {
        return commandDispatcher.sendInteractive(payload);
    }

    public DspTrafficSnapshot trafficSnapshot() {
		return commandDispatcher.trafficSnapshot(
				backgroundTasks.isMeterPolling(),
				backgroundTasks.meterPollIntervalMs()
        );
    }

    private synchronized void startKeepAlive() {
		backgroundTasks.startKeepAlive(
				config.initialKeepAliveDelayMs(),
				config.keepAliveIntervalMs()
		);
    }

    @Override
    public synchronized void close() {
        internalClose(true);
    }

    private synchronized void internalClose(boolean logClose) {
        connectionGeneration++;
        handshakeReady = false;
		DspSocketSession.CloseState socketState = socketSession.beginClose();
		boolean hadResources = socketState.hadResources()
				|| backgroundTasks.hasResources()
                || commandDispatcher.hasResources();

		commandDispatcher.close(new IOException("DSP connection closed"));

		backgroundTasks.close();

		socketSession.finishClose(socketState);

		if (logClose && (socketState.wasRunning() || hadResources)) {
            log("Connection closed");
        }
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}

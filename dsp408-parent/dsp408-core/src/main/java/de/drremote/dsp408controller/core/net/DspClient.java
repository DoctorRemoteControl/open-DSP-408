package de.drremote.dsp408controller.core.net;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.util.Hex;

public final class DspClient implements Closeable {
    private static final long MIN_TRANSMIT_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    private final DspConnectionConfig config;
    private final Consumer<String> log;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Socket socket;
    private PushbackInputStream in;
    private OutputStream out;
    private Thread readerThread;
    private ScheduledExecutorService keepAliveExecutor;
    private ScheduledExecutorService meterPollExecutor;
    private volatile Consumer<byte[]> payloadListener;
    private long lastTransmitNanos;

    public DspClient(DspConnectionConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    public boolean isConnected() {
        return running.get()
                && socket != null
                && socket.isConnected()
                && !socket.isClosed();
    }

    public synchronized void connect() throws IOException {
        if (isConnected()) {
            throw new IllegalStateException("Already connected");
        }

        internalClose(false);

        try {
            Socket newSocket = new Socket();
            newSocket.connect(
                    new InetSocketAddress(config.ip(), config.port()),
                    config.connectTimeoutMs()
            );
            newSocket.setSoTimeout(config.readTimeoutMs());

            PushbackInputStream newIn = new PushbackInputStream(newSocket.getInputStream(), 1);
            OutputStream newOut = newSocket.getOutputStream();

            socket = newSocket;
            in = newIn;
            out = newOut;
            lastTransmitNanos = 0L;
            running.set(true);

            startReader();
            log("Connected to " + config.ip() + ":" + config.port());

            sendHandshakeSequence();
            startKeepAlive();
        } catch (IOException | RuntimeException e) {
            internalClose(false);
            throw e;
        }
    }

    public void sendHandshakeSequence() throws IOException {
        sendPayload(DspProtocol.handshakeInit());
        sleepSilently(80);
        sendPayload(DspProtocol.handshakeAck());
        sleepSilently(80);
        sendPayload(DspProtocol.systemInfoRequest());
    }

    public void sendKeepAlive() throws IOException {
        sendPayload(DspProtocol.keepAlive());
    }

    public void requestSystemInfo() throws IOException {
        sendPayload(DspProtocol.systemInfoRequest());
    }

    public void requestRuntimeMeters() throws IOException {
        sendPayload(DspProtocol.runtimeMeterRequest());
    }

    public void readParameterBlock(int blockIndex) throws IOException {
        sendPayload(DspProtocol.readParameterBlock(blockIndex));
    }

    public void readPresetName(int presetIndex) throws IOException {
        sendPayload(DspProtocol.readPresetName(presetIndex));
    }

    public void loadPreset(int presetIndex) throws IOException {
        sendPayload(DspProtocol.loadPreset(presetIndex));
    }

    public void loadPreset(String slotLabel) throws IOException {
        sendPayload(DspProtocol.loadPreset(slotLabel));
    }

    public synchronized void startMeterPolling(long intervalMs) {
        if (intervalMs < 50) {
            throw new IllegalArgumentException("Meter poll interval must be >= 50 ms");
        }

        stopMeterPolling();

        meterPollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsp408-meterpoll");
            t.setDaemon(true);
            return t;
        });

        meterPollExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            try {
                requestRuntimeMeters();
            } catch (Exception e) {
                log("Meter poll error: " + e.getMessage());
            }
        }, 0L, intervalMs, TimeUnit.MILLISECONDS);

        log("Meter polling started (" + intervalMs + " ms)");
    }

    public synchronized void stopMeterPolling() {
        ScheduledExecutorService executor = meterPollExecutor;
        meterPollExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
            log("Meter polling stopped");
        }
    }

    public void login(String pin) throws IOException {
        sendPayload(DspProtocol.login(pin));
        log("Login PIN sent");
    }

    public void setDelayUnit(int unitRaw) throws IOException {
        sendPayload(DspProtocol.buildDelayUnit(unitRaw));
    }

    public void setDelayUnit(String unit) throws IOException {
        sendPayload(DspProtocol.buildDelayUnit(unit));
    }

    public void setChannelName(DspChannel channel, String name) throws IOException {
        sendPayload(DspProtocol.buildChannelName(channel, name));
    }

    public void setGain(int channelIndex, double db) throws IOException {
        sendPayload(DspProtocol.buildGain(channelIndex, db));
    }

    public void setGain(DspChannel channel, double db) throws IOException {
        setGain(channel.index(), db);
    }

    public void setMute(int selector, boolean muted) throws IOException {
        sendPayload(DspProtocol.buildMute(selector, muted));
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
        sendPayload(DspProtocol.buildPhase(channelIndex, inverted));
    }

    public void setPhase(DspChannel channel, boolean inverted) throws IOException {
        setPhase(channel.index(), inverted);
    }

    public void setDelay(int channelIndex, double ms) throws IOException {
        sendPayload(DspProtocol.buildDelay(channelIndex, ms));
    }

    public void setDelay(DspChannel channel, double ms) throws IOException {
        setDelay(channel.index(), ms);
    }

    public void setMatrixRoute(DspChannel output, DspChannel input) throws IOException {
        sendPayload(DspProtocol.buildMatrixRoute(output, input));
    }

    public void setMatrixRoutes(DspChannel output, java.util.Collection<DspChannel> inputs) throws IOException {
        sendPayload(DspProtocol.buildMatrixRoutes(output, inputs));
    }

    public void setMatrixCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
        sendPayload(DspProtocol.buildMatrixCrosspointGain(output, input, db));
    }

    public void setCrossoverHighPass(DspChannel channel,
                                     double frequencyHz,
                                     CrossoverSlope slope,
                                     boolean bypass) throws IOException {
        sendPayload(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, slope, bypass));
    }

    public void setCrossoverLowPass(DspChannel channel,
                                    double frequencyHz,
                                    CrossoverSlope slope,
                                    boolean bypass) throws IOException {
        sendPayload(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, slope, bypass));
    }

    public void setOutputPeqFrequency(int outputNumber, int peqIndex, double hz) throws IOException {
        sendPayload(DspProtocol.buildOutputPeqFrequency(outputNumber, peqIndex, hz));
    }

    public void setOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) throws IOException {
        sendPayload(DspProtocol.buildOutputPeqQRaw(outputNumber, peqIndex, qRaw));
    }

    public void setOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) throws IOException {
        sendPayload(DspProtocol.buildOutputPeqGainCode(outputNumber, peqIndex, gainCode));
    }

    public void setOutputPeq(int outputNumber,
                             int peqIndex,
                             double gainDb,
                             double frequencyHz,
                             double q,
                             PeqFilterType filterType) throws IOException {
        sendPayload(DspProtocol.buildOutputPeq(outputNumber, peqIndex, gainDb, frequencyHz, q, filterType));
    }

    public void setInputPeq(int inputChannelIndex,
                            int peqIndex,
                            double gainDb,
                            double frequencyHz,
                            double q,
                            PeqFilterType filterType,
                            boolean bypass) throws IOException {
        sendPayload(DspProtocol.buildInputPeq(
                inputChannelIndex,
                peqIndex,
                gainDb,
                frequencyHz,
                q,
                filterType,
                bypass
        ));
    }

    public void setInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) throws IOException {
        sendPayload(DspProtocol.buildInputPeqGain(inputChannelIndex, peqIndex, gainDb));
    }

    public void setInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) throws IOException {
        sendPayload(DspProtocol.buildInputPeqFrequency(inputChannelIndex, peqIndex, frequencyHz));
    }

    public void setInputPeqQ(int inputChannelIndex, int peqIndex, double q) throws IOException {
        sendPayload(DspProtocol.buildInputPeqQ(inputChannelIndex, peqIndex, q));
    }

    public void setInputPeqType(int inputChannelIndex, int peqIndex, PeqFilterType filterType) throws IOException {
        sendPayload(DspProtocol.buildInputPeqType(inputChannelIndex, peqIndex, filterType));
    }

    public void setInputPeqBypass(int inputChannelIndex, int peqIndex, boolean bypass) throws IOException {
        sendPayload(DspProtocol.buildInputPeqBypass(inputChannelIndex, peqIndex, bypass));
    }

    public void setInputGeq(int inputChannelIndex, int bandIndex, double gainDb) throws IOException {
        sendPayload(DspProtocol.buildInputGeq(inputChannelIndex, bandIndex, gainDb));
    }

    public void setFirProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
        sendPayload(DspProtocol.buildFirProcessingMode(output, mode));
    }

    public void setFirGenerator(DspChannel output,
                                FirFilterType type,
                                FirWindowFunction window,
                                double highPassFrequencyHz,
                                double lowPassFrequencyHz,
                                int taps) throws IOException {
        sendPayload(DspProtocol.buildFirGenerator(
                output,
                type,
                window,
                highPassFrequencyHz,
                lowPassFrequencyHz,
                taps
        ));
    }

    public void uploadExternalFir(DspChannel channel,
                                  String name,
                                  double[] coefficients,
                                  boolean includeBeginCommand) throws IOException {
        List<byte[]> payloads = DspProtocol.buildExternalFirUpload(channel, name, coefficients, includeBeginCommand);
        for (byte[] payload : payloads) {
            sendPayload(payload);
        }
    }

    public void setInputGate(int inputChannelIndex,
                             double thresholdDb,
                             double holdMs,
                             double attackMs,
                             double releaseMs) throws IOException {
        sendPayload(DspProtocol.buildInputGate(inputChannelIndex, thresholdDb, holdMs, attackMs, releaseMs));
    }

    public void setCompressor(DspChannel output,
                              String ratioLabel,
                              double attackMs,
                              double releaseMs,
                              double kneeDb,
                              double thresholdDb) throws IOException {
        sendPayload(DspProtocol.buildCompressor(
                output,
                ratioLabel,
                attackMs,
                releaseMs,
                kneeDb,
                thresholdDb
        ));
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
        sendPayload(DspProtocol.buildInputCompressor(
                input,
                ratioRaw,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw,
                frequencyRaw,
                typeContextRaw,
                typeRaw
        ));
    }

    public void setCompressor(DspChannel output,
                              int ratioRaw,
                              int attackRaw,
                              int releaseRaw,
                              int kneeRaw,
                              int thresholdRaw) throws IOException {
        sendPayload(DspProtocol.buildCompressor(
                output,
                ratioRaw,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw
        ));
    }

    public void setLimiter(DspChannel output,
                           double attackMs,
                           double releaseMs,
                           int kneeRaw,
                           double thresholdDb) throws IOException {
        sendPayload(DspProtocol.buildLimiter(
                output,
                attackMs,
                releaseMs,
                kneeRaw,
                thresholdDb
        ));
    }

    public void setLimiter(DspChannel output,
                           int attackRaw,
                           int releaseRaw,
                           int kneeRaw,
                           int thresholdRaw) throws IOException {
        sendPayload(DspProtocol.buildLimiter(
                output,
                attackRaw,
                releaseRaw,
                kneeRaw,
                thresholdRaw
        ));
    }

    public void setTestToneSource(int sourceIndex) throws IOException {
        sendPayload(DspProtocol.buildTestToneSource(sourceIndex));
    }

    public void setTestToneSource(String source) throws IOException {
        sendPayload(DspProtocol.buildTestToneSource(source));
    }

    public void setTestToneSineFrequencyRaw(int selectorRaw) throws IOException {
        sendPayload(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
    }

    public void setTestToneSineFrequencyHz(double hz) throws IOException {
        sendPayload(DspProtocol.buildTestToneSineFrequencyHz(hz));
    }

    public void setTestToneOff() throws IOException {
        sendPayload(DspProtocol.buildTestToneOff());
    }

    public void setPayloadListener(Consumer<byte[]> listener) {
        this.payloadListener = listener;
    }

    public synchronized void sendPayload(byte[] payload) throws IOException {
        ensureConnected();
        awaitTransmitSlot();

        byte[] frame = DspFrameCodec.encode(payload);
        out.write(frame);
        out.flush();
        lastTransmitNanos = System.nanoTime();

        log("--> TX payload=" + Hex.format(payload) + " | frame=" + Hex.format(frame));
    }

    private void awaitTransmitSlot() throws IOException {
        if (lastTransmitNanos == 0L) {
            return;
        }

        long remainingNanos = MIN_TRANSMIT_GAP_NANOS - (System.nanoTime() - lastTransmitNanos);
        if (remainingNanos <= 0L) {
            return;
        }

        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while pacing DSP transmissions", e);
        }
    }

    private synchronized void startReader() {
        readerThread = new Thread(this::readLoop, "dsp408-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private synchronized void startKeepAlive() {
        ScheduledExecutorService old = keepAliveExecutor;
        keepAliveExecutor = null;
        if (old != null) {
            old.shutdownNow();
        }

        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsp408-keepalive");
            t.setDaemon(true);
            return t;
        });

        keepAliveExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }

            try {
                sendKeepAlive();
            } catch (Exception e) {
                log("KeepAlive error: " + e.getMessage());
            }
        }, config.initialKeepAliveDelayMs(), config.keepAliveIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        try {
            while (running.get()) {
                try {
                    DspFrameCodec.DecodedFrame frame = DspFrameCodec.readFrame(in);
                    if (frame == null) {
                        continue;
                    }

                    String suffix = frame.checksumValid()
                            ? ""
                            : " | CHECKSUM ERROR calc=" + Hex.byteToHex(frame.calculatedChecksum())
                            + " rx=" + Hex.byteToHex(frame.receivedChecksum());

                    String ack = DspProtocol.isAck(frame.payload()) ? " | ACK" : "";

                    log("<-- RX payload=" + Hex.format(frame.payload())
                            + " | ascii=" + Hex.ascii(frame.payload())
                            + ack + suffix);

                    notifyPayloadListener(frame.payload());
                } catch (SocketTimeoutException ignored) {
                    // idle timeout
                } catch (EOFException e) {
                    if (running.get()) {
                        log("Remote peer closed the connection");
                    }
                    break;
                } catch (IOException e) {
                    if (running.get()) {
                        log("Read error: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        log("Unexpected read error: " + e.getMessage());
                    }
                    break;
                }
            }
        } finally {
            internalClose(true);
        }
    }

    private void notifyPayloadListener(byte[] payload) {
        Consumer<byte[]> listener = payloadListener;
        if (listener != null) {
            try {
                listener.accept(Arrays.copyOf(payload, payload.length));
            } catch (Exception e) {
                log("Payload listener error: " + e.getMessage());
            }
        }
    }

    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected");
        }
    }

    @Override
    public synchronized void close() {
        internalClose(true);
    }

    private synchronized void internalClose(boolean logClose) {
        boolean wasRunning = running.getAndSet(false);
        boolean hadResources = socket != null
                || in != null
                || out != null
                || keepAliveExecutor != null
                || meterPollExecutor != null
                || readerThread != null;

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

        closeQuietly(in);
        closeQuietly(out);
        closeQuietly(socket);

        in = null;
        out = null;
        socket = null;
        readerThread = null;
        lastTransmitNanos = 0L;
        // payloadListener absichtlich NICHT nullen:
        // sonst verliert der Client nach Reconnect still seinen Decoder/State-Listener.

        if (logClose && (wasRunning || hadResources)) {
            log("Connection closed");
        }
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static void sleepSilently(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Object obj) {
        try {
            if (obj instanceof Closeable c) {
                c.close();
            } else if (obj instanceof Socket s) {
                s.close();
            }
        } catch (Exception ignored) {
        }
    }
}

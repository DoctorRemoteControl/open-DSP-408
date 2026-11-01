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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.util.Hex;

/** Owns the TCP socket, frame reader and connection-local session-ready marker. */
final class DspSocketSession {
    private static final long SESSION_READY_WAIT_MS = 500L;

    record CloseState(boolean wasRunning, boolean hadResources, Connection connection) { }

    private static final class Connection {
        final Socket socket;
        final CountDownLatch sessionReady = new CountDownLatch(1);
        volatile PushbackInputStream input;
        volatile OutputStream output;
        volatile boolean running;
        volatile boolean readyMarkerReceived;
        Thread reader;

        Connection(Socket socket) {
            this.socket = socket;
        }
    }

    private final DspConnectionConfig config;
    private final Consumer<String> log;
    private final Supplier<Socket> socketFactory;
    private volatile Connection current;
    private volatile Consumer<byte[]> payloadListener;
    private volatile BiConsumer<Object, byte[]> responseListener;
    private volatile Consumer<Object> disconnectHandler;

    DspSocketSession(DspConnectionConfig config, Consumer<String> log) {
        this(config, log, Socket::new);
    }

    DspSocketSession(DspConnectionConfig config, Consumer<String> log, Supplier<Socket> socketFactory) {
        this.config = config;
        this.log = log;
        this.socketFactory = socketFactory;
    }

    boolean isRunning() {
        Connection connection = current;
        return connection != null && connection.running;
    }

    boolean isConnected() {
        Connection connection = current;
        return isActive(connection) && connection.socket.isConnected() && !connection.socket.isClosed();
    }

    boolean owns(Object token) {
        return token != null && current == token;
    }

    void open() throws IOException {
        open(() -> true);
    }

    void open(BooleanSupplier attemptActive) throws IOException {
        if (Thread.currentThread().isInterrupted() || !attemptActive.getAsBoolean()) {
            throw new IOException("DSP connection canceled");
        }
        Connection connection = new Connection(socketFactory.get());
        synchronized (this) {
            if (!attemptActive.getAsBoolean()) {
                closeQuietly(connection.socket);
                throw new IOException("DSP connection canceled");
            }
            if (current != null) {
                closeQuietly(connection.socket);
                throw new IllegalStateException("DSP socket session already open");
            }
            // Publish before connect so close can cancel the operating system connect call.
            current = connection;
        }
        try {
            connection.socket.connect(new InetSocketAddress(config.ip(), config.port()), config.connectTimeoutMs());
            connection.socket.setTcpNoDelay(true);
            connection.socket.setKeepAlive(true);
            connection.socket.setSoTimeout(config.readTimeoutMs());
            connection.input = new PushbackInputStream(connection.socket.getInputStream(), 1);
            connection.output = connection.socket.getOutputStream();
            if (!owns(connection) || Thread.currentThread().isInterrupted()) {
                throw new IOException("DSP connection canceled");
            }
        } catch (IOException | RuntimeException error) {
            synchronized (this) {
                if (current == connection) {
                    current = null;
                }
            }
            closeConnection(connection);
            throw error;
        }
    }

    synchronized void startReader() {
        Connection connection = current;
        if (connection == null || connection.input == null || connection.socket.isClosed()) {
            throw new IllegalStateException("DSP socket session closed");
        }
        connection.running = true;
        connection.reader = new Thread(() -> readLoop(connection), "dsp408-reader");
        connection.reader.setDaemon(true);
        connection.reader.start();
    }

    DspCommandDispatcher.FrameWriter frameWriter() {
        Connection connection = current;
        return payload -> writeFrame(connection, payload);
    }

    byte[] writeFrame(byte[] payload) throws IOException {
        return writeFrame(current, payload);
    }

    private byte[] writeFrame(Connection connection, byte[] payload) throws IOException {
        if (!isActive(connection) || connection.output == null) {
            throw new IOException("DSP connection closed");
        }
        byte[] frame = DspFrameCodec.encode(payload);
        connection.output.write(frame);
        connection.output.flush();
        return frame;
    }

    boolean awaitSessionReadyMarker() throws IOException {
        Connection connection = current;
        if (!isActive(connection)) {
            throw new IOException("DSP connection closed");
        }
        try {
            connection.sessionReady.await(SESSION_READY_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!isActive(connection)) {
                throw new IOException("DSP connection closed while waiting for session-ready marker");
            }
            if (connection.readyMarkerReceived) {
                log("DSP session-ready marker received (0xBE)");
                return true;
            }
            log("No DSP session-ready marker within " + SESSION_READY_WAIT_MS
                    + " ms; continuing for legacy firmware compatibility");
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for DSP session-ready marker", error);
        }
    }

    void setPayloadListener(Consumer<byte[]> listener) {
        payloadListener = listener;
    }

    void setResponseListener(Consumer<byte[]> listener) {
        responseListener = listener == null ? null : (token, payload) -> listener.accept(payload);
    }

    void setResponseListener(BiConsumer<Object, byte[]> listener) {
        responseListener = listener;
    }

    void setDisconnectHandler(Consumer<Object> handler) {
        disconnectHandler = handler;
    }

    synchronized CloseState beginClose() {
        Connection connection = current;
        current = null;
        boolean wasRunning = connection != null && connection.running;
        if (connection != null) {
            connection.running = false;
            connection.sessionReady.countDown();
        }
        return new CloseState(wasRunning, connection != null, connection);
    }

    void finishClose(CloseState state) {
        closeConnection(state.connection());
        // Listeners intentionally survive reconnects.
    }

    private boolean isActive(Connection connection) {
        return connection != null && current == connection && connection.running;
    }

    private void readLoop(Connection connection) {
        // Preserve partial frames across socket idle timeouts. The shared
        // decoder bounds its pending buffer by the protocol's maximum frame.
        var decoder = new de.drremote.dsp408.protocol.DspFrameDecoder();
        byte[] chunk = new byte[4096];
        try {
            while (isActive(connection)) {
                try {
                    int count = connection.input.read(chunk);
                    if (count < 0) throw new EOFException("EOF reading DSP stream");
                    for (var frame : decoder.accept(Arrays.copyOf(chunk, count))) {
                        if (!isActive(connection)) break;
                        String suffix = frame.checksumValid() ? ""
                                : " | CHECKSUM ERROR calc=" + Hex.byteToHex(frame.calculatedChecksum())
                                + " rx=" + Hex.byteToHex(frame.receivedChecksum());
                        String ack = DspProtocol.isAck(frame.payload()) ? " | ACK" : "";
                        log("<-- RX payload=" + Hex.format(frame.payload())
                                + " | ascii=" + Hex.ascii(frame.payload()) + ack + suffix);
                        if (!frame.checksumValid() || !isActive(connection)) {
                            continue;
                        }
                        notifyPayloadListener(frame.payload());
                        if (!isActive(connection)) {
                            continue;
                        }
                        if (DspCommandPolicy.commandByte(frame.payload()) == 0xBE) {
                            connection.readyMarkerReceived = true;
                            connection.sessionReady.countDown();
                        }
                        BiConsumer<Object, byte[]> listener = responseListener;
                        if (listener != null && isActive(connection)) {
                            listener.accept(connection, frame.payload());
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // Retain decoder state while waiting for the rest of a frame.
                } catch (EOFException error) {
                    if (isActive(connection)) {
                        log("Remote peer closed the connection");
                    }
                    break;
                } catch (Exception error) {
                    if (isActive(connection)) {
                        log("Read error: " + error.getMessage());
                    }
                    break;
                }
            }
        } finally {
            connection.running = false;
            connection.sessionReady.countDown();
            Consumer<Object> handler = disconnectHandler;
            if (owns(connection) && handler != null) {
                handler.accept(connection);
            }
            closeConnection(connection);
        }
    }

    private void notifyPayloadListener(byte[] payload) {
        Consumer<byte[]> listener = payloadListener;
        if (listener != null) {
            try {
                listener.accept(Arrays.copyOf(payload, payload.length));
            } catch (Exception error) {
                log("Payload listener error: " + error.getMessage());
            }
        }
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    private static void closeConnection(Connection connection) {
        if (connection != null) {
            // Socket close releases blocked reads before closing their stream wrappers.
            closeQuietly(connection.socket);
            closeQuietly(connection.input);
            closeQuietly(connection.output);
        }
    }

    private static void closeQuietly(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) { }
        }
    }
}

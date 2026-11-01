import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408controller.core.net.*;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

/** Repeated full Controller bootstrap/fault validation against owned loopback peers. */
public class ConnectionStress {
    enum Mode { NORMAL, FRAGMENTED, CORRUPT_THEN_VALID, SILENT_ACK, SILENT_READ, DROP }
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int cycles = Integer.parseInt(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        long started = System.nanoTime();
        List<Map<String, Object>> scenarios = new ArrayList<>();
        long requests = 0;
        for (Mode mode : Mode.values()) {
            int iterations = mode == Mode.NORMAL ? cycles : mode == Mode.FRAGMENTED ? 3 : 5;
            int peakThreads = 0;
            long modeStarted = System.nanoTime();
            for (int iteration = 0; iteration < iterations; iteration++) {
                try (FakeDevice peer = new FakeDevice(mode)) {
                    DspController controller = new DspController();
                    try {
                        controller.setPreferredLibraryType("fir408");
                        controller.connect(new DspConnectionConfig("127.0.0.1", peer.port(),
                                1_000, 100, 60_000, 60_000), ignored -> { });
                        if (!controller.isConnected()) throw new AssertionError("Bootstrap false-ready");
                        peakThreads = Math.max(peakThreads, dspThreads());
                        if (peakThreads > 3) throw new AssertionError("Unexpected live DSP thread count: " + peakThreads);
                        if (mode == Mode.SILENT_ACK) {
                            controller.state().markMuted(DspChannel.OUT_8, false, "validation");
                            expectIo(() -> controller.setMute(DspChannel.OUT_8, true));
                            if (controller.state().channel(DspChannel.OUT_8).muted())
                                throw new AssertionError("Unacknowledged write changed cached state");
                            if (peer.writes.get() != 1) throw new AssertionError("Write was repeated");
                        } else if (mode == Mode.SILENT_READ || mode == Mode.DROP) {
                            expectIo(() -> controller.readParameterBlock(0));
                            if (peer.reads.get() != 1) throw new AssertionError("Read was repeated");
                        } else {
                            long before = controller.state().blockRevision(0);
                            controller.readParameterBlock(0);
                            if (!Arrays.equals(new byte[]{0x33, 0x44}, controller.state().getBlock(0))
                                    || controller.state().blockRevision(0) <= before)
                                throw new AssertionError("Missing/mismatched fresh read");
                        }
                        if ((mode == Mode.SILENT_ACK || mode == Mode.SILENT_READ || mode == Mode.DROP)
                                && controller.isConnected()) throw new AssertionError("Failed transport still ready");
                    } finally { controller.close(); }
                    peer.finish();
                    requests += peer.requests.get();
                }
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (dspThreads() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
                if (dspThreads() != 0) throw new AssertionError("DSP threads leaked after cycle " + iteration);
            }
            Map<String, Object> sample = Map.of("mode", mode.name(), "cycles", iterations,
                    "seconds", (System.nanoTime()-modeStarted)/1_000_000_000.0,
                    "peak_dsp_threads", peakThreads, "remaining_dsp_threads", dspThreads());
            scenarios.add(sample);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("progress.json").toFile(), scenarios);
            System.out.println(JSON.writeValueAsString(sample));
        }
        Map<String, Object> result = Map.of("success", true, "finished_utc", Instant.now().toString(),
                "scenarios", scenarios, "requests", requests,
                "elapsed_seconds", (System.nanoTime()-started)/1_000_000_000.0,
                "heap_used", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), result);
    }

    @FunctionalInterface interface IoAction { void run() throws Exception; }
    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("A missing response/drop must fail the exchange");
    }
    private static int dspThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("dsp408-")).count();
    }

    private static class FakeDevice implements AutoCloseable {
        final Mode mode;
        final ServerSocket server = new ServerSocket();
        final AtomicInteger requests = new AtomicInteger(), reads = new AtomicInteger(), writes = new AtomicInteger();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        volatile Socket socket;
        final Thread thread;
        FakeDevice(Mode mode) throws IOException {
            this.mode = mode;
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            thread = new Thread(this::serve, "validation-peer");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        void serve() {
            try (Socket peer = server.accept()) {
                socket = peer;
                peer.setSoTimeout(5_000);
                var input = new PushbackInputStream(peer.getInputStream(), 1);
                OutputStream output = peer.getOutputStream();
                send(output, new byte[]{1, 0, 1, (byte)0xBE}, false);
                while (true) {
                    byte[] request;
                    try { request = DspFrameCodec.readFrame(input).payload(); }
                    catch (EOFException end) { break; }
                    requests.incrementAndGet();
                    int command = request[3] & 0xff;
                    byte[] response;
                    if (command == 0x27) {
                        reads.incrementAndGet();
                        if (mode == Mode.DROP) break;
                        if (mode == Mode.SILENT_READ) continue;
                        response = new byte[]{1, 0, 4, 0x24, request[4], 0x33, 0x44};
                        if (mode == Mode.CORRUPT_THEN_VALID) {
                            byte[] corrupt = DspFrameCodec.encode(response);
                            corrupt[corrupt.length-1] ^= 1;
                            output.write(corrupt); output.flush(); Thread.sleep(120);
                        }
                    } else if (command == 0x35) {
                        writes.incrementAndGet();
                        if (mode == Mode.SILENT_ACK) continue;
                        response = new byte[]{1, 0, 1, 1};
                    } else if (command == 0x29) {
                        response = new byte[]{1, 0, 2, 0x29, request[4]};
                    } else if (command == 0x2C) {
                        byte[] identity = "FIR408 V0113P".getBytes(StandardCharsets.US_ASCII);
                        response = new byte[identity.length+4];
                        response[0]=1; response[2]=(byte)(identity.length+1); response[3]=0x2C;
                        System.arraycopy(identity, 0, response, 4, identity.length);
                    } else if (Set.of(0x10, 0x13, 0x22, 0x14, 0x56).contains(command)) {
                        response = new byte[]{1, 0, 1, (byte)command};
                    } else throw new AssertionError("Unexpected test request: " + command);
                    send(output, response, mode == Mode.FRAGMENTED);
                }
                finished.complete(null);
            } catch (Throwable error) { finished.completeExceptionally(error); }
        }
        void send(OutputStream output, byte[] payload, boolean fragment) throws Exception {
            byte[] frame = DspFrameCodec.encode(payload);
            if (fragment) {
                output.write(frame,0,6); output.flush();
                Thread.sleep(140);
                output.write(frame,6,frame.length-6);
            } else output.write(frame);
            output.flush();
        }
        void finish() throws Exception { finished.get(6, TimeUnit.SECONDS); }
        public void close() throws Exception {
            server.close();
            if (socket != null) socket.close();
            thread.join(2_000);
            if (thread.isAlive()) throw new AssertionError("Fake peer leaked");
        }
    }
}

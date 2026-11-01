package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspParameterSessionTest {
    @Test
    void predictedDsp408InputDelayReadbackIsRejectedBeforeNetworkRequests() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertArrayEquals(new byte[]{0, 1, 2, 0x27, 3}, read(input));
            byte[] data = new byte[50];
            data[2] = (byte) 0x80;
            data[3] = 7;
            write(output, block(3, data));
        })) {
            DspParameterSession session = peer.connect().openParameterSession();
            for (DspChannel channel : new DspChannel[]{DspChannel.IN_B, DspChannel.IN_C}) {
                assertThrows(UnsupportedDspOperationException.class, () -> session.readBack(channel, DspParameter.DELAY));
            }
            assertEquals(20.0, (Double) session.readBack(DspChannel.IN_A, DspParameter.DELAY).value(), 1e-10);
        }
    }

    @Test
    void firReadbackUsesMeasuredFieldOffsetsAndOnlyObservedMuteChannels() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (input, output) -> {
            for (int i = 0; i < 6; i++) {
                assertArrayEquals(new byte[]{0, 1, 2, 0x27, 0x1C}, read(input));
                byte[] data = new byte[50];
                data[24] = 24; // FIR Out8 gain 280 => 0dB.
                data[25] = 1;
                data[26] = (byte) (i == 1 ? 1 : 0); // FIR Out8 phase.
                data[28] = (byte) 0x80; // FIR Out8 delay 1920 => 20ms.
                data[29] = 7;
                data[32] = 8; // Measured FIR InD mute.
                data[34] = (byte) 0x80; // Measured FIR Out8 mute.
                write(output, block(0x1C, data));
            }
        })) {
            DspController controller = peer.connect();
            DspParameterSession session = controller.openParameterSession();
            assertTrue(session.catalog().firmwareRecognized());
            assertThrows(UnsupportedDspOperationException.class,
                    () -> session.readBack(DspChannel.IN_A, DspParameter.MUTE));
            assertEquals(0.0, (Double) session.readBack(DspChannel.OUT_8, DspParameter.GAIN).value(), 1e-10);
            assertEquals(true, session.readBack(DspChannel.OUT_8, DspParameter.PHASE).value());
            assertEquals(false, session.readBack(DspChannel.OUT_8, DspParameter.PHASE).value());
            assertEquals(20.0, (Double) session.readBack(DspChannel.OUT_8, DspParameter.DELAY).value(), 1e-10);
            assertEquals(true, session.readBack(DspChannel.IN_D, DspParameter.MUTE).value());
            assertEquals(true, session.readBack(DspChannel.OUT_8, DspParameter.MUTE).value());
        }
    }

    @Test
    void unknownRawCodesCannotBeClampedIntoApparentlyValidReadback() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            for (int[] item : new int[][]{{2, 48, 401}, {3, 0, 2}, {3, 2, 65535}}) {
                assertEquals(item[0], read(input)[4] & 255);
                byte[] data = new byte[50];
                data[item[1]] = (byte) item[2];
                data[item[1] + 1] = (byte) (item[2] >> 8);
                write(output, block(item[0], data));
            }
        })) {
            DspParameterSession session = peer.connect().openParameterSession();
            for (DspParameter parameter : new DspParameter[]{DspParameter.GAIN, DspParameter.PHASE, DspParameter.DELAY}) {
                assertThrows(IOException.class, () -> session.readBack(DspChannel.IN_A, parameter));
            }
        }
    }

    @Test
    void concurrentLegacyCachePatchCannotChangeFreshDeviceReadback() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertEquals(0x27, read(input)[3] & 255);
            byte[] data = new byte[50];
            data[48] = (byte) 220;
            write(output, block(2, data));
        })) {
            DspController controller = peer.connect();
            AtomicInteger checks = new AtomicInteger();
            DspParameterSession session = new DspParameterSession(client(controller), controller.parameterCatalog(),
                    controller.state(), action -> {
                        if (checks.getAndIncrement() == 1) {
                            assertTrue(controller.state().patchCachedBlockUInt16LE(2, 48, 280));
                        }
                        action.run();
                        return true;
                    });
            assertEquals(-6.0, (Double) session.readBack(DspChannel.IN_A, DspParameter.GAIN).value(), 1e-10);
            assertEquals(24, controller.state().getBlock(2)[48] & 255, "Legacy cache remains optimistic");
        }
    }

    @Test
    void supersededPendingGainIsDefinitelyUnsentAndDoesNotPublish() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Peer peer = new Peer((input, output) -> {
            assertEquals(0x27, read(input)[3] & 255);
            blocked.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            write(output, block(0, new byte[50]));
            assertArrayEquals(new byte[]{0, 1, 4, 0x34, 0, (byte) 250, 0}, read(input));
            write(output, new byte[]{1, 0, 1, 1});
        })) {
            DspController controller = peer.connect();
            CompletableFuture<Void> active = CompletableFuture.runAsync(() -> {
                try { controller.readParameterBlock(0); } catch (IOException error) { throw new RuntimeException(error); }
            });
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> canceled = CompletableFuture.runAsync(() ->
                    assertThrows(CancellationException.class,
                            () -> controller.openParameterSession().write(DspChannel.IN_A, DspParameter.GAIN, -6.0)));
            awaitPending(controller);
            CompletableFuture<Void> replacement = CompletableFuture.runAsync(() -> {
                try { controller.setGain(DspChannel.IN_A, -3); } catch (IOException error) { throw new RuntimeException(error); }
            });
            try {
                canceled.get(1, TimeUnit.SECONDS);
                assertNull(controller.state().channel(DspChannel.IN_A).gainDb());
            } finally {
                release.countDown();
            }
            active.get(1, TimeUnit.SECONDS);
            replacement.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void knownAckSurvivesLostCurrentGuardWithoutPublishingIntoNewState() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertEquals(0x34, read(input)[3] & 255);
            write(output, new byte[]{1, 0, 1, 1});
        })) {
            DspController controller = peer.connect();
            controller.state().markGain(DspChannel.IN_A, 3, "replacement");
            AtomicInteger checks = new AtomicInteger();
            DspParameterSession session = new DspParameterSession(client(controller), controller.parameterCatalog(),
                    controller.state(), action -> {
                        if (checks.getAndIncrement() > 0) return false;
                        action.run();
                        return true;
                    });
            assertDoesNotThrow(() -> session.write(DspChannel.IN_A, DspParameter.GAIN, -6.0));
            assertEquals(3, controller.state().channel(DspChannel.IN_A).gainDb());
            assertFalse(session.isCurrent());
        }
    }

    @Test
    void readsFreshRawMappingRatherThanCachedOrOptimisticGain() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            byte[] request = read(input);
            assertArrayEquals(new byte[]{0, 1, 2, 0x27, 2}, request);
            byte[] data = new byte[50];
            data[48] = (byte) 220; // DSP408 InA gain, -6dB (not FIR offset 46).
            write(output, block(2, data));
        })) {
            DspController controller = peer.connect();
            byte[] cached = new byte[50];
            cached[48] = 24;
            cached[49] = 1;
            controller.state().storeBlock(2, cached);
            long revision = controller.state().blockRevision(2);
            controller.state().markGain(DspChannel.IN_A, 12, "tx");

            DspParameterReading reading = controller.openParameterSession().readBack(DspChannel.IN_A, DspParameter.GAIN);

            assertEquals(-6.0, (Double) reading.value(), 1e-10);
            assertEquals(2, reading.blockIndex());
            assertTrue(reading.receiveRevision() > revision);
            assertTrue(reading.observedAtEpochMs() > 0);
        }
    }

    @Test
    void matchingButShortFreshResponseCannotVerifyTheCachedValue() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertEquals(0x27, read(input)[3] & 255);
            write(output, block(2, new byte[2]));
        })) {
            DspController controller = peer.connect();
            controller.state().storeBlock(2, new byte[50]);
            controller.state().markGainFromDevice(DspChannel.IN_A, -6, "cached");
            assertThrows(IOException.class,
                    () -> controller.openParameterSession().readBack(DspChannel.IN_A, DspParameter.GAIN));
        }
    }

    @Test
    void matchingWireResponseRequiresAFreshReceivedStateRevision() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertEquals(0x27, read(input)[3] & 255);
            write(output, block(2, new byte[50]));
        })) {
            DspController controller = peer.connect();
            controller.state().storeBlock(2, new byte[50]);
            client(controller).setPayloadListener(ignored -> { }); // Simulate an unprocessed response.
            assertThrows(IOException.class,
                    () -> controller.openParameterSession().readBack(DspChannel.IN_A, DspParameter.GAIN));
        }
    }

    @Test
    void closeDoesNotWaitForSessionIoAndOldSessionCannotUseAReplacement() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        try (Peer first = new Peer((input, output) -> {
            assertEquals(0x27, read(input)[3] & 255);
            readStarted.countDown();
            while (input.read() >= 0) { }
        })) {
            DspController controller = first.connect();
            DspParameterSession session = controller.openParameterSession();
            CompletableFuture<Void> read = CompletableFuture.runAsync(() ->
                    assertThrows(IOException.class, () -> session.readBack(DspChannel.IN_A, DspParameter.GAIN)));
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), controller::close);
            read.get(1, TimeUnit.SECONDS);
            assertFalse(session.isCurrent());
            try (Peer replacement = new Peer((input, output) -> {
                assertThrows(java.net.SocketTimeoutException.class, () -> read(input));
            })) {
                replacement.connect(controller);
                assertTrue(controller.isConnected());
                assertFalse(session.isCurrent());
                assertThrows(IOException.class, () -> session.write(DspChannel.IN_A, DspParameter.GAIN, -6.0));
                assertThrows(IOException.class, () -> session.readBack(DspChannel.IN_A, DspParameter.GAIN));
            }
        }
    }

    @Test
    void acknowledgementPublishesNormalizedGainWithoutInventingAReceiveRevision() throws Exception {
        try (Peer peer = new Peer((input, output) -> {
            assertArrayEquals(new byte[]{0, 1, 4, 0x34, 0, 80, 0}, read(input));
            write(output, new byte[]{1, 0, 1, 1});
        })) {
            DspController controller = peer.connect();
            controller.state().storeBlock(2, new byte[50]);
            long revision = controller.state().blockRevision(2);
            controller.openParameterSession().write(DspChannel.IN_A, DspParameter.GAIN, -20.24);
            assertEquals(-20.0, controller.state().channel(DspChannel.IN_A).gainDb());
            assertFalse(controller.state().channel(DspChannel.IN_A).gainConfirmedFromDevice());
            assertEquals(revision, controller.state().blockRevision(2));
        }
    }

    private static DspClient client(DspController controller) throws Exception {
        Field field = DspController.class.getDeclaredField("client");
        field.setAccessible(true);
        return (DspClient) field.get(controller);
    }

    private static void awaitPending(DspController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (controller.trafficSnapshot().pendingCommands() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, controller.trafficSnapshot().pendingCommands());
    }

    private static byte[] block(int index, byte[] data) {
        byte[] payload = new byte[data.length + 5];
        payload[0] = 1;
        payload[2] = (byte) (data.length + 2);
        payload[3] = 0x24;
        payload[4] = (byte) index;
        System.arraycopy(data, 0, payload, 5, data.length);
        return payload;
    }

    private static byte[] read(PushbackInputStream input) throws Exception {
        return DspFrameCodec.readFrame(input).payload();
    }

    private static void write(OutputStream output, byte[] payload) throws IOException {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }

    @FunctionalInterface
    private interface Script { void run(PushbackInputStream input, OutputStream output) throws Exception; }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final CountDownLatch finished = new CountDownLatch(1);
        final CompletableFuture<Void> future;
        DspController controller;

        Peer(Script script) throws IOException {
            this("DSP408 V0104", script);
        }

        Peer(String identity, Script script) throws IOException {
            future = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(600);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    write(output, new byte[]{1, 0, 1, (byte) 0xBE});
                    for (int command : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
                        assertEquals(command, read(input)[3] & 255);
                        byte[] response = new byte[]{1, 0, 1, (byte) command};
                        if (command == 0x13) {
                            byte[] version = identity.getBytes(StandardCharsets.US_ASCII);
                            response = Arrays.copyOf(response, 4 + version.length);
                            response[2] = (byte) (version.length + 1);
                            System.arraycopy(version, 0, response, 4, version.length);
                        }
                        write(output, response);
                    }
                    boolean fir = identity.startsWith("FIR408");
                    for (int preset = 0; preset <= (fir ? 0x12 : 0x13); preset++) {
                        byte[] request = read(input);
                        assertEquals(0x29, request[3] & 255);
                        assertEquals(preset, request[4] & 255);
                        write(output, new byte[]{1, 0, 2, 0x29, (byte) preset});
                    }
                    if (fir) {
                        for (int offset : new int[]{0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181}) {
                            byte[] request = read(input);
                            assertEquals(0x56, request[3] & 255);
                            assertEquals(offset, (request[5] & 255) | ((request[6] & 255) << 8));
                            write(output, new byte[]{1, 0, 1, 0x56});
                        }
                    }
                    script.run(input, output);
                    assertTrue(finished.await(3, TimeUnit.SECONDS));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
        }

        DspController connect() throws IOException {
            DspController result = new DspController();
            connect(result);
            return result;
        }

        void connect(DspController controller) throws IOException {
            this.controller = controller;
            controller.connect(new DspConnectionConfig("127.0.0.1", server.getLocalPort(), 1_000, 100, 60_000, 60_000), ignored -> { });
        }

        @Override
        public void close() throws Exception {
            finished.countDown();
            try {
                future.get(4, TimeUnit.SECONDS);
            } finally {
                if (controller != null) controller.close();
                server.close();
            }
        }
    }
}

package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

/** Real loopback traffic distinguishes a wire ACK from a fresh parameter reply. */
@Timeout(15)
class DspParameterWritesTest {
    @Test
    void disconnectedWriteIsRejectedBeforeEffects() {
        DspController controller = new DspController();
        ParameterWriteResultDto result = writes(controller).write(
                new ParameterWriteRequestDto("Out8", "gain", -12.3));

        assertRejected(result, "dsp_not_connected");
        assertEquals("test-dsp", result.dspId());
        assertEquals("out8", result.channelId());
        assertNotNull(result.requestId());
        assertTrue(result.completedAtEpochMs() > 0);
    }

    @Test
    void unknownFirmwareCannotWriteEvenWithExplicitlyDisabledVerification() throws Exception {
        try (Peer peer = new Peer("FIR408 V9999", DspParameterWritesTest::assertNoRequest)) {
            assertRejected(peer.writer().write(new ParameterWriteRequestDto("Out8", "gain", -12.3, false)),
                    "unsupported_firmware");
            peer.awaitScript();
        }
    }

    @Test
    void defaultVerificationRejectsInferredFirMuteBeforeTransmission() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", DspParameterWritesTest::assertNoRequest)) {
            assertRejected(peer.writer().write(new ParameterWriteRequestDto("Out2", "mute", true)),
                    "readback_unsupported");
            peer.awaitScript();
        }
    }

    @Test
    void invalidRangesAndParameterTypesNeverReachThePeer() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", DspParameterWritesTest::assertNoRequest)) {
            for (ParameterWriteRequestDto request : new ParameterWriteRequestDto[]{
                    new ParameterWriteRequestDto("Out8", "gain", -60.01, false),
                    new ParameterWriteRequestDto("Out8", "gain", 12.01, false),
                    new ParameterWriteRequestDto("Out8", "delay", -0.01, false),
                    new ParameterWriteRequestDto("Out8", "delay", 680.01, false),
                    new ParameterWriteRequestDto("Out8", "gain", true, false),
                    new ParameterWriteRequestDto("Out8", "delay", false, false),
                    new ParameterWriteRequestDto("Out8", "mute", 1, false),
                    new ParameterWriteRequestDto("Out8", "phase", 0, false),
                    new ParameterWriteRequestDto("missing", "gain", 0, false),
                    new ParameterWriteRequestDto("Out8", "peq", 0, false)}) {
                assertRejected(peer.writer().write(request), "invalid_input");
            }
            peer.awaitScript();
        }
    }

    @Test
    void acknowledgedGainReportsRequestedAndNormalizedValuesWithoutReadback() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
            assertNoRequest(socket, input, output);
        })) {
            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.34, false));

            assertEquals(ParameterWriteStatus.ACKNOWLEDGED, result.status());
            assertEquals("out8", result.channelId());
            assertEquals(-12.34, result.requestedValue());
            assertEquals(-12.3, (Double) result.normalizedValue(), 1e-9);
            assertTrue(result.acknowledged());
            assertEquals(Boolean.TRUE, result.transmitted());
            assertNull(result.readback());
            assertNull(result.errorCode());
            peer.awaitScript();
        }
    }

    @Test
    void repeatedMuteStillRequiresItsOwnWireAck() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x35, 11, 0);
            ack(output);
        })) {
            peer.controller.state().markMuted(DspChannel.OUT_8, false, "test");

            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "mute", false, false));

            assertEquals(ParameterWriteStatus.ACKNOWLEDGED, result.status());
            assertTrue(result.acknowledged());
            assertEquals(Boolean.TRUE, result.transmitted());
            peer.awaitScript();
        }
    }

    @Test
    void normalizationUsesPiecewiseGainAndNinetySixthMillisecondDelay() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 79);
            ack(output);
            assertControl(read(input), 0x34, 11, 80);
            ack(output);
            assertControl(read(input), 0x38, 11, 97);
            ack(output);
        })) {
            assertEquals(-20.5, (Double) peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -20.26, false)).normalizedValue(), 1e-9);
            assertEquals(-20.0, (Double) peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -19.96, false)).normalizedValue(), 1e-9);
            assertEquals(97.0 / 96.0, (Double) peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "delay", 1.007, false)).normalizedValue(), 1e-9);
            peer.awaitScript();
        }
    }

    @Test
    void verifiedRequiresFreshRawReadbackInsteadOfOptimisticCachedState() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
            assertBlockRead(read(input), 0x1C);
            block(output, 0x1C, 24, 157);
        })) {
            peer.controller.state().storeBlock(0x1C, new byte[50]);
            long oldRevision = peer.controller.state().blockRevision(0x1C);

            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.34));

            assertEquals(ParameterWriteStatus.VERIFIED, result.status());
            assertTrue(result.acknowledged());
            assertEquals(Boolean.TRUE, result.transmitted());
            assertNotNull(result.readback());
            assertEquals(-12.3, (Double) result.readback().value(), 1e-9);
            assertEquals("1C", result.readback().blockIndex());
            assertTrue(result.readback().receiveRevision() > oldRevision);
            assertTrue(result.readback().observedAtEpochMs() > 0);
            peer.awaitScript();
        }
    }

    @Test
    void differentFreshReadbackIsUncertainAndRetainsTheAck() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
            assertBlockRead(read(input), 0x1C);
            block(output, 0x1C, 24, 180);
        })) {
            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.3));

            assertEquals(ParameterWriteStatus.UNCERTAIN, result.status());
            assertEquals("readback_mismatch", result.errorCode());
            assertTrue(result.acknowledged());
            assertEquals(Boolean.TRUE, result.transmitted());
            assertEquals(-10.0, (Double) result.readback().value(), 1e-9);
            peer.awaitScript();
        }
    }

    @Test
    void missingAckIsUncertainAndNeverReplaysTheWrite() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            assertThrows(EOFException.class, () -> DspFrameCodec.readFrame(input));
        })) {
            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.3, false));

            assertEquals(ParameterWriteStatus.UNCERTAIN, result.status());
            assertEquals("write_failed", result.errorCode());
            assertFalse(result.acknowledged());
            assertNull(result.transmitted());
            assertNull(result.readback());
            peer.awaitScript();
        }
    }

    @Test
    void readbackDisconnectIsUncertainWithKnownAcknowledgement() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
            assertBlockRead(read(input), 0x1C);
            socket.close();
        })) {
            ParameterWriteResultDto result = peer.writer().write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.3));

            assertEquals(ParameterWriteStatus.UNCERTAIN, result.status());
            assertEquals("readback_failed", result.errorCode());
            assertTrue(result.acknowledged());
            assertEquals(Boolean.TRUE, result.transmitted());
            assertNull(result.readback());
            peer.awaitScript();
        }
    }

    @Test
    void metersStayPausedAcrossBothTheWriteAndFreshReadback() throws Exception {
        AtomicReference<DspController> controller = new AtomicReference<>();
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            assertFalse(controller.get().trafficSnapshot().centralMeterPolling());
            ack(output);
            assertBlockRead(read(input), 0x1C);
            assertFalse(controller.get().trafficSnapshot().centralMeterPolling());
            block(output, 0x1C, 24, 157);
        })) {
            controller.set(peer.controller);
            peer.controller.startMeterPolling(60_000);
            DspParameterWrites writer = new DspParameterWrites(peer.controller,
                    new DspTransactionCoordinator(peer.controller, () -> peer.controller.startMeterPolling(60_000)),
                    () -> "test-dsp");

            assertEquals(ParameterWriteStatus.VERIFIED, writer.write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.3)).status());
            assertTrue(peer.controller.trafficSnapshot().centralMeterPolling());
            peer.awaitScript();
        }
    }

    @Test
    void connectionLostAfterKnownAckRetainsAcknowledgementAndCannotVerify() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch sendAck = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            written.countDown();
            assertTrue(sendAck.await(3, TimeUnit.SECONDS));
            ack(output);
            // The next transmitted command proves that the gain ACK was consumed.
            assertEquals(0x3D, command(read(input)));
            socket.close();
            closed.countDown();
        }); ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var result = writers.submit(() -> peer.writer().write(
                        new ParameterWriteRequestDto("Out8", "gain", -12.3)));
                assertTrue(written.await(2, TimeUnit.SECONDS));
                fill(writers, peer.controller, 1);
                await(() -> peer.controller.trafficSnapshot().pendingCommands() == 1);
                synchronized (peer.controller.state()) {
                    sendAck.countDown();
                    assertTrue(closed.await(2, TimeUnit.SECONDS));
                    await(() -> !peer.controller.isConnected());
                }

                ParameterWriteResultDto outcome = result.get(2, TimeUnit.SECONDS);
                assertEquals(ParameterWriteStatus.UNCERTAIN, outcome.status());
                assertTrue(outcome.acknowledged());
                assertEquals(Boolean.TRUE, outcome.transmitted());
                assertNull(outcome.readback());
                peer.awaitScript();
            } finally {
                sendAck.countDown();
                peer.controller.close();
            }
        }
    }

    @Test
    void fullQueueRejectsWriteDefinitelyBeforeTransmission() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertEquals(0x3D, command(read(input)));
            active.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
        }); ExecutorService fillers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                fill(fillers, peer.controller, 1);
                assertTrue(active.await(2, TimeUnit.SECONDS));
                fill(fillers, peer.controller, 64);
                await(() -> peer.controller.trafficSnapshot().pendingCommands() == 64);

                ParameterWriteResultDto result = peer.writer().write(
                        new ParameterWriteRequestDto("Out8", "gain", -12.3, false));

                assertRejected(result, "dsp_overloaded");
                assertTrue(peer.controller.isConnected());
                assertEquals(64, peer.controller.trafficSnapshot().pendingCommands());
            } finally {
                release.countDown();
                peer.controller.close();
            }
        }
    }

    @Test
    void fullQueueAfterAckMakesReadbackUncertainInsteadOfRejectingTheWrite() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch sendAck = new CountDownLatch(1);
        CountDownLatch nextActive = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            written.countDown();
            assertTrue(sendAck.await(3, TimeUnit.SECONDS));
            ack(output);
            assertEquals(0x3D, command(read(input)));
            nextActive.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
        }); ExecutorService fillers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                CompletableFuture<ParameterWriteResultDto> write = CompletableFuture.supplyAsync(() ->
                        peer.writer().write(new ParameterWriteRequestDto("Out8", "gain", -12.3)));
                assertTrue(written.await(2, TimeUnit.SECONDS));
                fill(fillers, peer.controller, 64);
                await(() -> peer.controller.trafficSnapshot().pendingCommands() == 64);

                // Hold state publication in this test after ACK routing, while
                // legacy commands fill the real queue before readback admission.
                synchronized (peer.controller.state()) {
                    sendAck.countDown();
                    assertTrue(nextActive.await(2, TimeUnit.SECONDS));
                    fill(fillers, peer.controller, 1);
                    await(() -> peer.controller.trafficSnapshot().pendingCommands() == 64);
                }

                ParameterWriteResultDto result = write.get(2, TimeUnit.SECONDS);
                assertEquals(ParameterWriteStatus.UNCERTAIN, result.status());
                assertEquals("dsp_overloaded", result.errorCode());
                assertTrue(result.acknowledged());
                assertEquals(Boolean.TRUE, result.transmitted());
                assertNull(result.readback());
                assertTrue(peer.controller.isConnected());
            } finally {
                sendAck.countDown();
                release.countDown();
                peer.controller.close();
            }
        }
    }

    @Test
    void supersededPendingWriteIsDefinitelyRejectedWithoutClaimingAnAck() throws Exception {
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertEquals(0x3D, command(read(input)));
            active.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            ack(output);
            assertControl(read(input), 0x36, 11, 1);
            ack(output);
        }); ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                fill(writers, peer.controller, 1);
                assertTrue(active.await(2, TimeUnit.SECONDS));
                var first = writers.submit(() -> peer.writer().write(
                        new ParameterWriteRequestDto("Out8", "phase", false, false)));
                await(() -> peer.controller.trafficSnapshot().pendingCommands() == 1);
                var second = writers.submit(() -> peer.writer().write(
                        new ParameterWriteRequestDto("Out8", "phase", true, false)));

                ParameterWriteResultDto superseded = assertDoesNotThrow(() -> first.get(2, TimeUnit.SECONDS));
                assertRejected(superseded, "write_superseded");
                assertEquals(1, peer.controller.trafficSnapshot().pendingCommands());
                release.countDown();
                assertEquals(ParameterWriteStatus.ACKNOWLEDGED, second.get(2, TimeUnit.SECONDS).status());
                peer.awaitScript();
            } finally {
                release.countDown();
                peer.controller.close();
            }
        }
    }

    @Test
    void operationAdmissionIsBoundedWhileLegacyTransactionHoldsTheCoordinator() throws Exception {
        CountDownLatch storeActive = new CountDownLatch(1);
        CountDownLatch releaseStore = new CountDownLatch(1);
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            // Store has the existing ten-second response budget, giving the
            // concurrent producers a bounded test window without arbitrary sleeps.
            assertEquals(0x21, command(read(input)));
            storeActive.countDown();
            assertTrue(releaseStore.await(5, TimeUnit.SECONDS));
            ack(output);
            Set<Integer> received = new HashSet<>();
            for (int index = 0; index < 65; index++) {
                DspFrameCodec.DecodedFrame frame = read(input);
                assertEquals(0x34, command(frame));
                assertEquals(11, frame.payload()[4] & 0xFF);
                int raw = (frame.payload()[5] & 0xFF) | ((frame.payload()[6] & 0xFF) << 8);
                assertTrue(raw >= 0 && raw <= 64, "Only admitted request markers may reach the peer");
                assertTrue(received.add(raw), "Each admitted write reaches the peer once");
                ack(output);
            }
            assertEquals(65, received.size());
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
        }); ExecutorService producers = Executors.newFixedThreadPool(2)) {
            DspTransactionCoordinator coordinator = new DspTransactionCoordinator(peer.controller, () -> { });
            DspParameterWrites writer = new DspParameterWrites(peer.controller, coordinator, () -> "test-dsp");
            List<Thread> waiting = new ArrayList<>();
            List<CompletableFuture<ParameterWriteResultDto>> outcomes = new ArrayList<>();
            try {
                var legacyStore = producers.submit(() -> coordinator.withMetersPaused(() -> {
                    peer.controller.storePreset(1);
                    return null;
                }));
                assertTrue(storeActive.await(2, TimeUnit.SECONDS));
                for (int index = 0; index < 65; index++) {
                    double requested = -60.0 + index * 0.5;
                    CompletableFuture<ParameterWriteResultDto> outcome = new CompletableFuture<>();
                    outcomes.add(outcome);
                    // Platform callers avoid Java 21 virtual-thread carrier
                    // starvation while all producers contend on one monitor.
                    Thread thread = Thread.ofPlatform().name("parameter-admission-" + index).unstarted(() -> {
                        try {
                            outcome.complete(writer.write(new ParameterWriteRequestDto("Out8", "gain", requested, false)));
                        } catch (Throwable failure) {
                            outcome.completeExceptionally(failure);
                        }
                    });
                    waiting.add(thread);
                    thread.start();
                }
                // BLOCKED alone can mean UUID/SecureRandom initialization before
                // admission. Require every producer to have acquired its permit
                // and reached the actual shared transaction monitor.
                await(() -> waiting.stream().allMatch(thread -> thread.getState() == Thread.State.BLOCKED
                        && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(DspTransactionCoordinator.class.getName())
                                && frame.getMethodName().equals("withMetersPaused"))));

                var excess = producers.submit(() -> writer.write(
                        new ParameterWriteRequestDto("Out8", "gain", 12.0, false)));
                ParameterWriteResultDto rejected = assertDoesNotThrow(() -> excess.get(1, TimeUnit.SECONDS),
                        "A full operation admission limit must reject without waiting for the legacy transaction");
                assertRejected(rejected, "dsp_overloaded");
                assertEquals("test-dsp", rejected.dspId());
                assertEquals("out8", rejected.channelId());
                assertEquals(0, peer.controller.trafficSnapshot().pendingCommands(),
                        "Waiting runtime operations have not entered the transport queue");

                releaseStore.countDown();
                legacyStore.get(3, TimeUnit.SECONDS);
                Set<String> requestIds = new HashSet<>();
                for (CompletableFuture<ParameterWriteResultDto> outcome : outcomes) {
                    ParameterWriteResultDto result = outcome.get(3, TimeUnit.SECONDS);
                    assertEquals(ParameterWriteStatus.ACKNOWLEDGED, result.status());
                    assertTrue(result.acknowledged());
                    assertEquals(Boolean.TRUE, result.transmitted());
                    assertTrue(requestIds.add(result.requestId()));
                }
                assertEquals(65, requestIds.size());
                assertEquals(ParameterWriteStatus.ACKNOWLEDGED, writer.write(
                        new ParameterWriteRequestDto("Out8", "gain", -12.3, false)).status());
                peer.awaitScript();
            } finally {
                releaseStore.countDown();
                peer.controller.close();
                for (Thread thread : waiting) thread.join(3_000);
            }
        }
    }

    @Test
    void unexpectedProgrammingFailuresReleaseOperationAdmissionAndRemainExceptions() {
        DspController controller = new DspController();
        AtomicInteger calls = new AtomicInteger();
        DspParameterWrites writer = new DspParameterWrites(controller,
                new DspTransactionCoordinator(controller, () -> { }), () -> {
                    if (calls.incrementAndGet() <= 65) throw new IllegalStateException("Test identity supplier failure");
                    return "test-dsp";
                });

        for (int index = 0; index < 65; index++) {
            assertThrows(IllegalStateException.class, () -> writer.write(
                    new ParameterWriteRequestDto("Out8", "gain", -12.3, false)));
        }
        assertRejected(writer.write(new ParameterWriteRequestDto("Out8", "gain", -12.3, false)),
                "dsp_not_connected");
    }

    @Test
    void disconnectDuringMeterRestorationPreservesAckAndVerifiedReadback() throws Exception {
        for (boolean verify : new boolean[]{false, true}) {
            try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
                assertControl(read(input), 0x34, 11, 157);
                ack(output);
                if (verify) {
                    var request = read(input);
                    assertEquals(0x27, command(request));
                    assertEquals(0x1C, request.payload()[4] & 255);
                    block(output, 0x1C, 24, 157);
                }
            })) {
                peer.controller.startMeterPolling(60_000);
                var transactions = new DspTransactionCoordinator(peer.controller, () -> {
                    // Disconnect after the coordinator's connectivity check but
                    // before the real runtime restart obtains its client.
                    peer.controller.close();
                    peer.controller.startMeterPolling(60_000);
                });
                var writer = new DspParameterWrites(peer.controller, transactions, () -> "test-dsp");
                var result = writer.write(new ParameterWriteRequestDto("out8", "gain", -12.3, verify));
                peer.awaitScript();
                assertEquals(verify ? ParameterWriteStatus.VERIFIED : ParameterWriteStatus.ACKNOWLEDGED,
                        result.status());
                assertTrue(result.acknowledged());
                assertEquals(Boolean.TRUE, result.transmitted());
                if (verify) assertEquals(-12.3, result.readback().value());
                assertFalse(peer.controller.isConnected());
            }
        }
    }

    @Test
    void disconnectDuringMeterRestorationPreservesTheOriginalOperationFailure() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> { })) {
            peer.controller.startMeterPolling(60_000);
            var transactions = new DspTransactionCoordinator(peer.controller, () -> {
                peer.controller.close();
                peer.controller.startMeterPolling(60_000);
            });
            IOException expected = new IOException("Original operation failure");
            IOException actual = assertThrows(IOException.class,
                    () -> transactions.withMetersPaused(() -> { throw expected; }));
            org.junit.jupiter.api.Assertions.assertSame(expected, actual);
        }
    }

    @Test
    void meterRestorationStillPropagatesProgrammingErrors() throws Exception {
        try (Peer peer = new Peer("FIR408 V0113P", (socket, input, output) -> {
            assertControl(read(input), 0x34, 11, 157);
            ack(output);
        })) {
            peer.controller.startMeterPolling(60_000);
            var transactions = new DspTransactionCoordinator(peer.controller,
                    () -> { throw new IllegalStateException("Programming failure"); });
            var writer = new DspParameterWrites(peer.controller, transactions, () -> "test-dsp");
            assertEquals("Programming failure", assertThrows(IllegalStateException.class,
                    () -> writer.write(new ParameterWriteRequestDto("out8", "gain", -12.3, false))).getMessage());
            peer.awaitScript();
        }
    }

    private static void fill(ExecutorService executor, DspController controller, int count) {
        for (int index = 0; index < count; index++) {
            executor.submit(() -> {
                try {
                    // Channel-name writes have no coalescing key.
                    controller.setChannelName(DspChannel.OUT_1, "queued");
                } catch (IOException | IllegalStateException expectedOnCleanup) {
                    // Closing the isolated session releases intentionally blocked callers.
                }
            });
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Fake-peer producers did not fill the bounded queue");
    }

    private static void assertRejected(ParameterWriteResultDto result, String errorCode) {
        assertEquals(ParameterWriteStatus.REJECTED, result.status());
        assertEquals(errorCode, result.errorCode());
        assertFalse(result.acknowledged());
        assertEquals(Boolean.FALSE, result.transmitted());
        assertNull(result.readback());
    }

    private static DspParameterWrites writes(DspController controller) {
        return new DspParameterWrites(controller,
                new DspTransactionCoordinator(controller, () -> { }), () -> "test-dsp");
    }

    private static void assertControl(DspFrameCodec.DecodedFrame frame, int command, int channel, int raw) {
        assertEquals(command, command(frame));
        assertEquals(channel, frame.payload()[4] & 0xFF);
        int actual = frame.payload()[5] & 0xFF;
        if (frame.payload().length > 6) actual |= (frame.payload()[6] & 0xFF) << 8;
        assertEquals(raw, actual);
    }

    private static void assertBlockRead(DspFrameCodec.DecodedFrame frame, int index) {
        assertEquals(0x27, command(frame));
        assertEquals(index, frame.payload()[4] & 0xFF);
    }

    private static void assertNoRequest(Socket socket, PushbackInputStream input, OutputStream output)
            throws Exception {
        socket.setSoTimeout(300);
        assertThrows(SocketTimeoutException.class, () -> read(input), "Preflight must have no wire effects");
    }

    private static void block(OutputStream output, int index, int offset, int raw) throws Exception {
        byte[] payload = new byte[55];
        payload[0] = 1;
        payload[2] = 52;
        payload[3] = 0x24;
        payload[4] = (byte) index;
        payload[5 + offset] = (byte) raw;
        payload[6 + offset] = (byte) (raw >> 8);
        send(output, payload);
    }

    private static void ack(OutputStream output) throws Exception {
        send(output, new byte[]{1, 0, 1, 1});
    }

    private static DspFrameCodec.DecodedFrame read(PushbackInputStream input) throws Exception {
        DspFrameCodec.DecodedFrame frame = DspFrameCodec.readFrame(input);
        if (frame == null) throw new EOFException("Fake DSP received no frame");
        return frame;
    }

    private static int command(DspFrameCodec.DecodedFrame frame) {
        return frame.payload()[3] & 0xFF;
    }

    private static void send(OutputStream output, byte[] payload) throws Exception {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }

    @FunctionalInterface
    private interface Script {
        void run(Socket socket, PushbackInputStream input, OutputStream output) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        final DspController controller = new DspController();
        private final ServerSocket server = new ServerSocket(0);
        private final CountDownLatch close = new CountDownLatch(1);
        private final CompletableFuture<Void> scriptDone = new CompletableFuture<>();
        private final CompletableFuture<Void> peer;

        Peer(String identity, Script script) throws Exception {
            peer = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    startup(input, output, identity);
                    script.run(socket, input, output);
                    scriptDone.complete(null);
                    assertTrue(close.await(10, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    scriptDone.completeExceptionally(failure);
                    throw new RuntimeException(failure);
                }
            });
            controller.setPreferredLibraryType("fir408");
            controller.connect(new DspConnectionConfig("127.0.0.1", server.getLocalPort(),
                    1_000, 100, 60_000, 60_000), ignored -> { });
        }

        DspParameterWrites writer() {
            return writes(controller);
        }

        void awaitScript() throws Exception {
            scriptDone.get(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            close.countDown();
            controller.close();
            server.close();
            peer.get(3, TimeUnit.SECONDS);
        }

        private static void startup(PushbackInputStream input, OutputStream output, String identity)
                throws Exception {
            send(output, new byte[]{1, 0, 5, (byte) 0xB8, (byte) 192, (byte) 168, 1, (byte) 166});
            send(output, new byte[]{1, 0, 1, (byte) 0xBE});
            for (int expected : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
                assertEquals(expected, command(read(input)));
                if (expected == 0x13) {
                    byte[] ascii = identity.getBytes(StandardCharsets.US_ASCII);
                    byte[] version = new byte[4 + ascii.length];
                    version[0] = 1;
                    version[2] = (byte) (ascii.length + 1);
                    version[3] = 0x13;
                    System.arraycopy(ascii, 0, version, 4, ascii.length);
                    send(output, version);
                } else {
                    send(output, new byte[]{1, 0, 1, (byte) expected});
                }
            }
            for (int index = 0; index <= 0x12; index++) {
                DspFrameCodec.DecodedFrame frame = read(input);
                assertEquals(0x29, command(frame));
                assertEquals(index, frame.payload()[4] & 0xFF);
                send(output, new byte[]{1, 0, 2, 0x29, (byte) index});
            }
            for (int offset : new int[]{0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181}) {
                DspFrameCodec.DecodedFrame frame = read(input);
                assertEquals(0x56, command(frame));
                assertEquals(offset, (frame.payload()[5] & 0xFF) | ((frame.payload()[6] & 0xFF) << 8));
                send(output, new byte[]{1, 0, 1, 0x56});
            }
        }
    }
}

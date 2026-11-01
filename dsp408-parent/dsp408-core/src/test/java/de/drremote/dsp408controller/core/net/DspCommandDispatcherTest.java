package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.IOException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspCommandDispatcherTest {
    @Test
    void interactiveCommandCanFollowOneStartedKeepaliveWithoutWaitingForOtherWork() throws Exception {
        var active = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reference = new java.util.concurrent.atomic.AtomicReference<DspCommandDispatcher>();
        var commands = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var dispatcher = new DspCommandDispatcher(() -> true, () -> true, payload -> {
            int command = payload[3] & 255;
            commands.add(command);
            if (command == 0x12) {
                active.countDown();
                try {
                    if (!release.await(1, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test release timed out");
                } catch (InterruptedException error) { throw new IOException(error); }
            }
            reference.get().signalResponse(new byte[]{1, 0, 1, 1});
            return DspFrameCodec.encode(payload);
        }, () -> { }, ignored -> { });
        reference.set(dispatcher);
        dispatcher.open();
        dispatcher.start();
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var background = callers.submit(() -> dispatcher.send(DspProtocol.keepAlive()));
            org.junit.jupiter.api.Assertions.assertTrue(active.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var interactive = callers.submit(() -> dispatcher.sendInteractive(DspProtocol.buildGain(0, -6)));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (dispatcher.trafficSnapshot(false, null).pendingCommands() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, dispatcher.trafficSnapshot(false, null).pendingCommands());
            release.countDown();
            org.junit.jupiter.api.Assertions.assertTrue(background.get(1, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(interactive.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(java.util.List.of(0x12, 0x34), commands);
        } finally {
            release.countDown();
            dispatcher.close(null);
        }
    }

    @Test
    void unansweredKeepAliveClosesAnUnverifiedSession() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connected = new AtomicBoolean(true);
        AtomicInteger connectionCloses = new AtomicInteger();
        DspCommandDispatcher dispatcher = new DspCommandDispatcher(
                running::get,
                connected::get,
                payload -> DspFrameCodec.encode(payload),
                () -> { connected.set(false); connectionCloses.incrementAndGet(); },
                ignored -> { }
        );
        dispatcher.open();
        dispatcher.start();

        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertThrows(IOException.class, () -> dispatcher.send(DspProtocol.keepAlive()))
            );

            assertEquals(1, connectionCloses.get());
        } finally {
            running.set(false);
            dispatcher.close(null);
        }
    }
}

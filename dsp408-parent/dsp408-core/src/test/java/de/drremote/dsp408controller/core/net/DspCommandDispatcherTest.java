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

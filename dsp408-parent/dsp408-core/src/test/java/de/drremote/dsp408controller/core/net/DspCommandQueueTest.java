package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DspCommandQueueTest {
    @Test
    void safetyCommandsOvertakePendingMeters() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        queue.submit(new byte[]{0, 1, 1, 0x40}, DspCommandQueue.Priority.METER, "meter", false);
        queue.submit(new byte[]{0, 1, 2, 0x35, 4}, DspCommandQueue.Priority.SAFETY, "mute:4", true);

        assertEquals(DspCommandQueue.Priority.SAFETY, queue.take().priority());
        assertEquals(DspCommandQueue.Priority.METER, queue.take().priority());
    }

    @Test
    void newestPendingValueSupersedesOlderValueForSameKey() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var first = queue.submit(
                new byte[]{0, 1, 4, 0x34, 0, 10, 0},
                DspCommandQueue.Priority.CONTROL,
                "gain:0",
                true
        );
        queue.submit(
                new byte[]{0, 1, 4, 0x34, 0, 20, 0},
                DspCommandQueue.Priority.CONTROL,
                "gain:0",
                true
        );

        assertFalse(first.get());
        assertEquals(20, queue.take().payload()[5]);
        assertEquals(1L, queue.stats().supersededCommands());
    }

    @Test
    void repeatedMeterRequestIsCoalesced() throws Exception {
        DspCommandQueue queue = new DspCommandQueue();
        queue.open();
        var first = queue.submit(
                new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER,
                "meter",
                false
        );
        queue.submit(
                new byte[]{0, 1, 1, 0x40},
                DspCommandQueue.Priority.METER,
                "meter",
                false
        );

        assertFalse(first.get());
        assertEquals(1L, queue.stats().coalescedMeterRequests());
    }
}

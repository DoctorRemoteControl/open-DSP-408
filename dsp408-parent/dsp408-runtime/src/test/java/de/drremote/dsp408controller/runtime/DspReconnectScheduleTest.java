package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DspReconnectScheduleTest {
    @Test
    void waitsForSafeInitialDelayAndBacksOffAfterFailures() {
        DspReconnectSchedule schedule = new DspReconnectSchedule(25_000L, 60_000L);

        schedule.arm(1_000L);
        schedule.arm(5_000L);
        assertEquals(26_000L, schedule.nextAttemptAtEpochMs());
        assertFalse(schedule.isDue(25_999L));
        assertTrue(schedule.isDue(26_000L));

        assertEquals(25_000L, schedule.recordFailure(26_000L));
        assertEquals(51_000L, schedule.nextAttemptAtEpochMs());
        assertEquals(50_000L, schedule.retryDelayMs());

        assertEquals(50_000L, schedule.recordFailure(51_000L));
        assertEquals(101_000L, schedule.nextAttemptAtEpochMs());
        assertEquals(60_000L, schedule.retryDelayMs());

        assertEquals(60_000L, schedule.recordFailure(101_000L));
        assertEquals(161_000L, schedule.nextAttemptAtEpochMs());
        assertEquals(60_000L, schedule.retryDelayMs());
    }

    @Test
    void successAndCancelRestoreTheInitialState() {
        DspReconnectSchedule schedule = new DspReconnectSchedule(25_000L, 60_000L);
        schedule.arm(0L);
        schedule.recordFailure(25_000L);

        schedule.recordSuccess();
        assertFalse(schedule.isArmed());
        assertEquals(25_000L, schedule.retryDelayMs());

        schedule.arm(100L);
        schedule.cancel();
        assertFalse(schedule.isArmed());
        assertEquals(25_000L, schedule.retryDelayMs());
    }
}

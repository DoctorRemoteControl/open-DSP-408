package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoLimitLeaseTest {
    @Test
    void onlyOneClientOwnsAutoLimitAtATime() {
        DspRuntimeComponent runtime = new DspRuntimeComponent();

        var first = runtime.acquireAutoLimitLease("phone-a", 30_000L);
        var second = runtime.acquireAutoLimitLease("phone-b", 30_000L);

        assertTrue(first.active());
        assertTrue(first.acquired());
        assertNotNull(first.token());
        assertFalse(second.acquired());
        assertTrue(runtime.ownsAutoLimitLease(first.token()));
        assertFalse(runtime.ownsAutoLimitLease("wrong-token"));

        var released = runtime.releaseAutoLimitLease(first.token());
        assertTrue(released.acquired());
        assertFalse(released.active());
        assertTrue(runtime.acquireAutoLimitLease("phone-b", 30_000L).acquired());
    }
}

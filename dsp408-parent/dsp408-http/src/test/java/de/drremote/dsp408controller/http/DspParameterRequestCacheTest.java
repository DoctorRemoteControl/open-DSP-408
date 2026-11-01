package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.ParameterWriteRequestDto;
import de.drremote.dsp408.api.ParameterWriteResultDto;
import de.drremote.dsp408.api.ParameterWriteStatus;

class DspParameterRequestCacheTest {
    private static final long TTL = TimeUnit.MINUTES.toNanos(2);
    private static final ParameterWriteRequestDto REQUEST = new ParameterWriteRequestDto("out1", "gain", -12.0);

    @Test
    void completedOutcomeExpiresTwoMinutesAfterCompletionRatherThanAdmission() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        DspParameterRequestCache cache = new DspParameterRequestCache(clock::get);
        AtomicInteger writes = new AtomicInteger();
        DspParameterRequestCache.WriteOperation operation = () -> result(writes.incrementAndGet());
        ParameterWriteResultDto first = cache.execute("key", "main", REQUEST, () -> {
            clock.addAndGet(TimeUnit.MINUTES.toNanos(9));
            return operation.execute();
        });
        clock.addAndGet(TTL - 1);
        assertSame(first, cache.execute("key", "main", REQUEST, operation));
        assertEquals(1, writes.get());

        clock.incrementAndGet();
        ParameterWriteResultDto afterExpiry = cache.execute("key", "main", REQUEST, operation);

        assertEquals("result-2", afterExpiry.requestId());
        assertEquals(2, writes.get());
    }

    @Test
    void completedFailureKeepsItsIdentityAndExpiresAfterTwoMinutes() throws Exception {
        AtomicLong clock = new AtomicLong();
        DspParameterRequestCache cache = new DspParameterRequestCache(clock::get);
        AtomicInteger writes = new AtomicInteger();
        Exception original = new Exception("original synthetic failure");
        DspParameterRequestCache.WriteOperation operation = () -> {
            writes.incrementAndGet();
            throw original;
        };
        assertSame(original, assertThrows(Exception.class,
                () -> cache.execute("key", "main", REQUEST, operation)));
        clock.addAndGet(TTL - 1);
        assertSame(original, assertThrows(Exception.class,
                () -> cache.execute("key", "main", REQUEST, operation)));
        assertEquals(1, writes.get());

        clock.incrementAndGet();
        assertEquals("result-2", cache.execute("key", "main", REQUEST,
                () -> result(writes.incrementAndGet())).requestId());
    }

    @Test
    void expiredCompletedEntriesFreeCapacityForNewRequests() throws Exception {
        AtomicLong clock = new AtomicLong();
        DspParameterRequestCache cache = new DspParameterRequestCache(clock::get);
        for (int i = 0; i < 4096; i++) {
            cache.execute("key-" + i, "main", REQUEST, () -> result(1));
        }
        clock.set(TTL);

        assertEquals("result-2", cache.execute("new-key", "main", REQUEST, () -> result(2)).requestId());
    }

    @Test
    void inFlightEntryNeverExpiresAndDoesNotBlockOtherAdmissionOrIo() throws Exception {
        AtomicLong clock = new AtomicLong();
        DspParameterRequestCache cache = new DspParameterRequestCache(clock::get);
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var original = executor.submit(() -> cache.execute("in-flight", "main", REQUEST, () -> {
                writes.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return result(1);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.set(TTL * 2);

            var unrelated = executor.submit(() -> cache.execute("another", "main", REQUEST, () -> result(2)));
            assertEquals("result-2", unrelated.get(5, TimeUnit.SECONDS).requestId());
            var duplicate = executor.submit(() -> cache.execute("in-flight", "main", REQUEST, () -> {
                writes.incrementAndGet();
                return result(3);
            }));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> duplicate.get(100, TimeUnit.MILLISECONDS));
            release.countDown();

            assertSame(original.get(5, TimeUnit.SECONDS), duplicate.get(5, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void normalLiveInteractionBeyond256ChangesDoesNotExhaustDeliveryProtection() throws Exception {
        DspParameterRequestCache cache = new DspParameterRequestCache(() -> 0L);
        AtomicInteger operations = new AtomicInteger();
        for (int i = 0; i < 512; i++) {
            cache.execute("live-change-" + i, "main", REQUEST, () -> result(operations.incrementAndGet()));
        }
        assertEquals(512, operations.get());
    }

    private static ParameterWriteResultDto result(int count) {
        return new ParameterWriteResultDto("result-" + count, "main", "out1", "gain", -12.0, -12.0,
                ParameterWriteStatus.ACKNOWLEDGED, true, true, null, null, "Synthetic result.", 42);
    }
}

package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class DspBackgroundTasksTest {
	@Test
	void rejectsMeterPollingFasterThanTheSupportedInterval() {
		DspBackgroundTasks tasks = new DspBackgroundTasks(() -> true, () -> { }, () -> { }, null);

		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> tasks.startMeterPolling(49L)
		);

		assertEquals("Meter poll interval must be >= 50 ms", error.getMessage());
		assertFalse(tasks.isMeterPolling());
		assertFalse(tasks.hasResources());
	}

	@Test
	void startsAndStopsMeterPollingWithTheObservedThreadAndLogs() throws Exception {
		CountDownLatch invoked = new CountDownLatch(1);
		AtomicReference<String> threadName = new AtomicReference<>();
		List<String> logs = new CopyOnWriteArrayList<>();
		DspBackgroundTasks tasks = new DspBackgroundTasks(
				() -> true,
				() -> { },
				() -> {
					threadName.set(Thread.currentThread().getName());
					invoked.countDown();
				},
				logs::add
		);

		try {
			tasks.startMeterPolling(50L);

			assertTrue(invoked.await(1L, TimeUnit.SECONDS));
			assertTrue(tasks.isMeterPolling());
			assertEquals(50L, tasks.meterPollIntervalMs());
			assertEquals("dsp408-meterpoll", threadName.get());
			assertTrue(logs.contains("Meter polling started (50 ms)"));

			tasks.stopMeterPolling();

			assertFalse(tasks.isMeterPolling());
			assertNull(tasks.meterPollIntervalMs());
			assertFalse(tasks.hasResources());
			assertTrue(logs.contains("Meter polling stopped"));
		} finally {
			tasks.close();
		}
	}

	@Test
	void waitsOneIntervalBeforeFirstMeterRequest() throws Exception {
		CountDownLatch invoked = new CountDownLatch(1);
		DspBackgroundTasks tasks = new DspBackgroundTasks(
				() -> true,
				() -> { },
				invoked::countDown,
				null
		);

		try {
			tasks.startMeterPolling(250L);

			assertFalse(invoked.await(100L, TimeUnit.MILLISECONDS));
			assertTrue(invoked.await(500L, TimeUnit.MILLISECONDS));
		} finally {
			tasks.close();
		}
	}

	@Test
	void startsKeepAliveAndReleasesItsSchedulerOnClose() throws Exception {
		CountDownLatch invoked = new CountDownLatch(1);
		AtomicReference<String> threadName = new AtomicReference<>();
		DspBackgroundTasks tasks = new DspBackgroundTasks(
				() -> true,
				() -> {
					threadName.set(Thread.currentThread().getName());
					invoked.countDown();
				},
				() -> { },
				null
		);

		try {
			tasks.startKeepAlive(0L, 1_000L);

			assertTrue(invoked.await(1L, TimeUnit.SECONDS));
			assertTrue(tasks.hasResources());
			assertEquals("dsp408-keepalive", threadName.get());
		} finally {
			tasks.close();
		}

		assertFalse(tasks.hasResources());
	}
}

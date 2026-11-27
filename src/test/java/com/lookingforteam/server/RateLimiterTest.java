package com.lookingforteam.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class RateLimiterTest
{
	@Test
	public void allowsBurstThenBlocksThenRefills()
	{
		AtomicLong now = new AtomicLong();
		RateLimiter limiter = new RateLimiter(3, 60, now::get);

		assertTrue(limiter.tryAcquire("a"));
		assertTrue(limiter.tryAcquire("a"));
		assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"));

		// 60/min = one token per second.
		now.addAndGet(TimeUnit.SECONDS.toNanos(1));
		assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"));
	}

	@Test
	public void clientsAreIndependent()
	{
		RateLimiter limiter = new RateLimiter(1, 60, () -> 0L);
		assertTrue(limiter.tryAcquire("a"));
		assertFalse(limiter.tryAcquire("a"));
		assertTrue(limiter.tryAcquire("b"));
	}

	@Test
	public void purgesIdleClients()
	{
		AtomicLong now = new AtomicLong();
		RateLimiter limiter = new RateLimiter(2, 60, now::get);
		limiter.tryAcquire("a");
		now.addAndGet(TimeUnit.MINUTES.toNanos(1));
		limiter.purgeIdle();
		assertEquals(0, limiter.trackedClients());
	}
}

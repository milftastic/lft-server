package com.lookingforteam.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-client token bucket. A plugin that is looking makes ~8 requests a minute (plus one
 * per checkbox click), so the defaults leave plenty of headroom while stopping
 * a script from hammering the server.
 */
public class RateLimiter
{
	private static final class Bucket
	{
		double tokens;
		long lastRefill;

		Bucket(double tokens, long now)
		{
			this.tokens = tokens;
			this.lastRefill = now;
		}
	}

	private final int burst;
	private final double tokensPerNano;
	private final LongSupplier nanoTime;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(int burst, int perMinute, LongSupplier nanoTime)
	{
		this.burst = burst;
		this.tokensPerNano = perMinute / (double) TimeUnit.MINUTES.toNanos(1);
		this.nanoTime = nanoTime;
	}

	public boolean tryAcquire(String client)
	{
		long now = nanoTime.getAsLong();
		Bucket bucket = buckets.computeIfAbsent(client, c -> new Bucket(burst, now));
		synchronized (bucket)
		{
			bucket.tokens = Math.min(burst, bucket.tokens + (now - bucket.lastRefill) * tokensPerNano);
			bucket.lastRefill = now;
			if (bucket.tokens >= 1)
			{
				bucket.tokens -= 1;
				return true;
			}
			return false;
		}
	}

	/** Drops buckets that have refilled completely, so idle clients don't use memory. */
	public void purgeIdle()
	{
		long now = nanoTime.getAsLong();
		buckets.entrySet().removeIf(e ->
		{
			synchronized (e.getValue())
			{
				return e.getValue().tokens + (now - e.getValue().lastRefill) * tokensPerNano >= burst;
			}
		});
	}

	int trackedClients()
	{
		return buckets.size();
	}
}

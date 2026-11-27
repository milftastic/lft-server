package com.lookingforteam.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.Test;

public class StatusStoreTest
{
	private static final String ALICE = LftServer.ownerId("Alice");
	private static final String BOB = LftServer.ownerId("Bob");

	private static StatusStore store(Clock clock)
	{
		return new StatusStore(clock, Duration.ofMinutes(5), 10);
	}

	@Test
	public void visibleOnlyToChosenViewers()
	{
		StatusStore store = store(Clock.systemUTC());
		// Bob is LFT and has Alice as a friend, so he publishes a key for Alice only.
		store.put(BOB, List.of(LftServer.visibilityKey("Bob", "Alice")), List.of("TOB"));

		assertEquals(1, store.lookup(List.of(LftServer.visibilityKey("Bob", "Alice"))).size());
		assertTrue(store.lookup(List.of(LftServer.visibilityKey("Bob", "Carol"))).isEmpty());
	}

	@Test
	public void oneWayFriendshipHidesBothWays()
	{
		StatusStore store = store(Clock.systemUTC());
		// Alice has Bob as a friend; Bob doesn't have Alice. Both LFT ToB.
		store.put(ALICE, List.of(LftServer.visibilityKey("Alice", "Bob")), List.of("TOB"));
		store.put(BOB, List.of(LftServer.visibilityKey("Bob", "Someone else")), List.of("TOB"));

		// Alice looks up Bob (her friend): Bob never allowed her.
		assertTrue(store.lookup(List.of(LftServer.visibilityKey("Bob", "Alice"))).isEmpty());
		// Bob never looks up Alice (not his friend); his lookups don't include her.
	}

	@Test
	public void republishReplacesOldKeys()
	{
		StatusStore store = store(Clock.systemUTC());
		String aliceKey = LftServer.visibilityKey("Bob", "Alice");
		store.put(BOB, List.of(aliceKey), List.of("TOB"));
		// Alice went offline: Bob's next refresh no longer includes her key.
		store.put(BOB, List.of(LftServer.visibilityKey("Bob", "Carol")), List.of("TOB"));

		assertTrue(store.lookup(List.of(aliceKey)).isEmpty());
	}

	@Test
	public void emptyActivitiesClearsStatus()
	{
		StatusStore store = store(Clock.systemUTC());
		String key = LftServer.visibilityKey("Bob", "Alice");
		store.put(BOB, List.of(key), List.of("TOB"));
		store.put(BOB, List.of(), List.of());

		assertTrue(store.lookup(List.of(key)).isEmpty());
		assertEquals(0, store.size());
	}

	@Test
	public void statusExpiresAfterTtl()
	{
		MutableClock clock = new MutableClock();
		StatusStore store = store(clock);
		String key = LftServer.visibilityKey("Bob", "Alice");
		store.put(BOB, List.of(key), List.of("COX"));

		clock.now = clock.now.plus(Duration.ofMinutes(6));
		assertTrue(store.lookup(List.of(key)).isEmpty());
		store.purgeExpired();
		assertEquals(0, store.size());
	}

	@Test
	public void refusesNewKeysWhenFullButAllowsRefreshAndClear()
	{
		StatusStore store = new StatusStore(Clock.systemUTC(), Duration.ofMinutes(5), 2);
		List<String> bobKeys = List.of(LftServer.visibilityKey("Bob", "A"), LftServer.visibilityKey("Bob", "B"));
		assertTrue(store.put(BOB, bobKeys, List.of("TOB")));

		// Full: someone new is refused.
		assertFalse(store.put(ALICE, List.of(LftServer.visibilityKey("Alice", "C")), List.of("COX")));
		// Bob refreshing the same number of keys still works, and clearing always works.
		assertTrue(store.put(BOB, bobKeys, List.of("TOB", "TFA")));
		assertTrue(store.put(BOB, List.of(), List.of()));
		assertTrue(store.put(ALICE, List.of(LftServer.visibilityKey("Alice", "C")), List.of("COX")));
	}

	@Test
	public void hashesMatchPlugin()
	{
		// Same values are pinned in the plugin's NameHasherTest; change both together.
		assertEquals("ec3a40aeee6872f925767fd5379a49841348e7a91868b06d8a7bd8e37a17a47e", LftServer.ownerId("Iron Man"));
		assertEquals(LftServer.ownerId("Iron Man"), LftServer.ownerId("iron_man"));
		assertEquals(LftServer.ownerId("Iron Man"), LftServer.ownerId("Iron Man"));
		assertEquals(VISIBILITY_GOLDEN, LftServer.visibilityKey("Iron Man", "Zezima"));
	}

	static final String VISIBILITY_GOLDEN = "baae242592d0ad671242c62e309152e8243395e4b5d3fe5a2a140f40eb2a172e";

	private static final class MutableClock extends Clock
	{
		Instant now = Instant.parse("2026-01-01T00:00:00Z");

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return now;
		}
	}
}

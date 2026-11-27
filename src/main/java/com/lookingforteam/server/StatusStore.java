package com.lookingforteam.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory LFT statuses. Each owner (a hashed player id) publishes their
 * activities plus a set of visibility keys, one per person allowed to see them.
 * Lookups are by visibility key, so a status is only visible to people its owner
 * chose (their friends and clanmates). The server can't tell who a key belongs to.
 * <p>
 * Statuses expire if the plugin stops refreshing them (crash, lost connection),
 * so nothing here needs to survive a restart.
 */
public class StatusStore
{
	/** What a lookup returns for one matching key. */
	public static final class Match
	{
		public final String key;
		public final List<String> activities;

		Match(String key, List<String> activities)
		{
			this.key = key;
			this.activities = activities;
		}
	}

	private static final class Owner
	{
		final Set<String> keys;
		final List<String> activities;
		final Instant expiresAt;

		Owner(Set<String> keys, List<String> activities, Instant expiresAt)
		{
			this.keys = keys;
			this.activities = activities;
			this.expiresAt = expiresAt;
		}
	}

	private final Map<String, Owner> owners = new HashMap<>();
	private final Map<String, String> keyToOwner = new HashMap<>();
	private final Clock clock;
	private final Duration ttl;
	private final int maxKeys;

	/** @param maxKeys total visibility keys held at once; bounds memory (~300 bytes per key) */
	public StatusStore(Clock clock, Duration ttl, int maxKeys)
	{
		this.clock = clock;
		this.ttl = ttl;
		this.maxKeys = maxKeys;
	}

	public boolean put(String ownerId, Collection<String> keys, List<String> activities)
	{
		return put(ownerId, keys, activities, ttl);
	}

	/**
	 * Replaces the owner's previous status and keys. Empty activities clears it.
	 *
	 * @return false if the store is full (the previous status is left as it was)
	 */
	public synchronized boolean put(String ownerId, Collection<String> keys, List<String> activities, Duration ttl)
	{
		boolean clearing = activities.isEmpty() || keys.isEmpty();
		Owner existing = owners.get(ownerId);
		int existingKeys = existing == null ? 0 : existing.keys.size();
		if (!clearing && keyToOwner.size() - existingKeys + keys.size() > maxKeys)
		{
			return false;
		}

		remove(ownerId);
		if (clearing)
		{
			return true;
		}

		Owner owner = new Owner(new HashSet<>(keys), List.copyOf(activities), clock.instant().plus(ttl));
		owners.put(ownerId, owner);
		for (String key : owner.keys)
		{
			// A visibility key encodes (owner, viewer), so another owner can only collide by
			// computing this owner's keys. Last write wins; the displaced owner loses that one key.
			String previous = keyToOwner.put(key, ownerId);
			if (previous != null && !previous.equals(ownerId))
			{
				Owner other = owners.get(previous);
				if (other != null)
				{
					other.keys.remove(key);
				}
			}
		}
		return true;
	}

	public synchronized List<Match> lookup(Collection<String> keys)
	{
		Instant now = clock.instant();
		List<Match> found = new ArrayList<>();
		for (String key : keys)
		{
			String ownerId = keyToOwner.get(key);
			Owner owner = ownerId == null ? null : owners.get(ownerId);
			if (owner != null && owner.expiresAt.isAfter(now))
			{
				found.add(new Match(key, owner.activities));
			}
		}
		return found;
	}

	public synchronized int size()
	{
		return owners.size();
	}

	public synchronized int keyCount()
	{
		return keyToOwner.size();
	}

	public synchronized void purgeExpired()
	{
		Instant now = clock.instant();
		Iterator<Map.Entry<String, Owner>> it = owners.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, Owner> entry = it.next();
			if (!entry.getValue().expiresAt.isAfter(now))
			{
				removeKeys(entry.getKey(), entry.getValue());
				it.remove();
			}
		}
	}

	private void remove(String ownerId)
	{
		Owner old = owners.remove(ownerId);
		if (old != null)
		{
			removeKeys(ownerId, old);
		}
	}

	private void removeKeys(String ownerId, Owner owner)
	{
		for (String key : owner.keys)
		{
			keyToOwner.remove(key, ownerId);
		}
	}
}

package com.lookingforteam.server;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * LFT server, API v1 (see the plugin's LftApiClient for the contract).
 * <p>
 * Env vars:
 * <ul>
 * <li>PORT (default 8080)</li>
 * <li>MAX_STORED_KEYS (default 600000): total visibility keys held, ~300 bytes each</li>
 * <li>RATE_PER_MINUTE / RATE_BURST (default 60 / 30): per-client request limit</li>
 * <li>LFT_DEV=true enables the /dev endpoints for local testing. Never set it on a public server.</li>
 * </ul>
 */
public class LftServer
{
	static final Duration TTL = Duration.ofMinutes(5);
	static final int MAX_KEYS = 1000;
	static final int MAX_ACTIVITIES = 50;

	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern ACTIVITY = Pattern.compile("[A-Z0-9_]{1,32}");
	private static final Gson GSON = new Gson();

	public static void main(String[] args)
	{
		int port = envInt("PORT", 8080);
		boolean dev = "true".equalsIgnoreCase(System.getenv("LFT_DEV"));
		// On Fly.io the real client IP arrives in a header set by Fly's proxy.
		boolean behindFlyProxy = System.getenv("FLY_APP_NAME") != null;

		StatusStore store = new StatusStore(Clock.systemUTC(), TTL, envInt("MAX_STORED_KEYS", 600_000));
		RateLimiter limiter = new RateLimiter(envInt("RATE_BURST", 30), envInt("RATE_PER_MINUTE", 60), System::nanoTime);

		ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
		janitor.scheduleWithFixedDelay(() ->
		{
			store.purgeExpired();
			limiter.purgeIdle();
		}, 1, 1, TimeUnit.MINUTES);

		create(store, limiter, behindFlyProxy, dev).start(port);
		if (dev)
		{
			System.out.println("LFT_DEV is on: /dev endpoints are enabled. Don't do this on a public server.");
		}
	}

	static Javalin create(StatusStore store, RateLimiter limiter, boolean behindFlyProxy, boolean dev)
	{
		return Javalin.create(config ->
		{
			config.http.maxRequestSize = 256_000L;
			addRoutes(config.routes, store, limiter, behindFlyProxy, dev);
		});
	}

	private static void addRoutes(RoutesConfig app, StatusStore store, RateLimiter limiter, boolean behindFlyProxy, boolean dev)
	{
		app.get("/health", ctx -> ctx.result("ok"));

		app.before("/v1/*", ctx ->
		{
			String flyIp = behindFlyProxy ? ctx.header("Fly-Client-IP") : null;
			if (!limiter.tryAcquire(flyIp != null ? flyIp : ctx.ip()))
			{
				throw new HttpResponseException(429, "Too many requests");
			}
		});

		app.put("/v1/status", ctx ->
		{
			PublishRequest req = body(ctx, PublishRequest.class);
			requireHash(req.id);
			List<String> keys = validKeys(req.keys == null ? List.of() : req.keys);
			if (!store.put(req.id, keys, validActivities(req.activities)))
			{
				throw new HttpResponseException(503, "Server is full, try again later");
			}
			ctx.status(204);
		});

		app.post("/v1/status/lookup", ctx ->
		{
			LookupRequest req = body(ctx, LookupRequest.class);
			if (req.keys == null)
			{
				throw new BadRequestResponse("keys is required");
			}
			List<String> keys = validKeys(req.keys);
			ctx.contentType("application/json").result(GSON.toJson(Map.of("statuses", store.lookup(keys))));
		});

		if (dev)
		{
			// Mark a player as LFT, visible to the listed viewers, to test without a second account.
			app.post("/dev/seed", ctx ->
			{
				SeedRequest req = body(ctx, SeedRequest.class);
				if (req.name == null || req.name.isBlank())
				{
					throw new BadRequestResponse("name is required");
				}
				List<String> keys = new ArrayList<>();
				if (req.visibleTo != null)
				{
					req.visibleTo.forEach(viewer -> keys.add(visibilityKey(req.name, viewer)));
				}
				// Seeds last longer than real statuses since no client refreshes them.
				store.put(ownerId(req.name), keys, validActivities(req.activities), Duration.ofHours(1));
				ctx.contentType("application/json").result(GSON.toJson(Map.of("visibleTo", keys.size())));
			});

			app.get("/dev/statuses", ctx -> ctx.result("owners=" + store.size() + " keys=" + store.keyCount()));
		}
	}

	private static int envInt(String name, int fallback)
	{
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
	}

	// ---- Must match NameHasher in the plugin (pinned by tests on both sides) ----

	static String normalize(String name)
	{
		return name.replace((char) 0xA0, ' ')
			.toLowerCase(Locale.ROOT)
			.replace('_', ' ')
			.trim();
	}

	static String ownerId(String name)
	{
		return sha256("lft-v1:" + normalize(name));
	}

	static String visibilityKey(String owner, String viewer)
	{
		return sha256("lft-vis-v1:" + normalize(owner) + "\n" + normalize(viewer));
	}

	private static String sha256(String input)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : digest)
			{
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	// ---- Validation ----

	private static <T> T body(Context ctx, Class<T> type)
	{
		try
		{
			T parsed = GSON.fromJson(ctx.body(), type);
			if (parsed == null)
			{
				throw new BadRequestResponse("empty body");
			}
			return parsed;
		}
		catch (JsonParseException e)
		{
			throw new BadRequestResponse("invalid JSON");
		}
	}

	private static void requireHash(String value)
	{
		if (value == null || !HASH.matcher(value).matches())
		{
			throw new BadRequestResponse("invalid id");
		}
	}

	private static List<String> validKeys(List<String> keys)
	{
		if (keys.size() > MAX_KEYS)
		{
			throw new BadRequestResponse("at most " + MAX_KEYS + " keys");
		}
		keys.forEach(LftServer::requireHash);
		return keys;
	}

	private static List<String> validActivities(List<String> activities)
	{
		if (activities == null)
		{
			return List.of();
		}
		if (activities.size() > MAX_ACTIVITIES || !activities.stream().allMatch(a -> a != null && ACTIVITY.matcher(a).matches()))
		{
			throw new BadRequestResponse("invalid activities");
		}
		return activities;
	}

	static final class PublishRequest
	{
		String id;
		List<String> keys;
		List<String> activities;
	}

	static final class LookupRequest
	{
		List<String> keys;
	}

	static final class SeedRequest
	{
		String name;
		List<String> visibleTo;
		List<String> activities;
	}
}

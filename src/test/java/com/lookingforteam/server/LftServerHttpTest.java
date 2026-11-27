package com.lookingforteam.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;

/** Starts the real server on a random port and talks to it over HTTP, like the plugin does. */
public class LftServerHttpTest
{
	private static final String BOB = LftServer.ownerId("Bob");
	private static final String BOB_TO_ALICE = LftServer.visibilityKey("Bob", "Alice");

	private final HttpClient http = HttpClient.newHttpClient();
	private Javalin app;

	private String start(int maxKeys, int burst, boolean dev)
	{
		StatusStore store = new StatusStore(Clock.systemUTC(), Duration.ofMinutes(5), maxKeys);
		RateLimiter limiter = new RateLimiter(burst, 60, System::nanoTime);
		app = LftServer.create(store, limiter, false, dev).start(0);
		return "http://localhost:" + app.port();
	}

	@After
	public void stop()
	{
		if (app != null)
		{
			app.stop();
		}
	}

	private HttpResponse<String> send(String method, String url, String body) throws Exception
	{
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url));
		req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String publish(String id, String key, String activity)
	{
		return "{\"id\":\"" + id + "\",\"keys\":[\"" + key + "\"],\"activities\":[\"" + activity + "\"]}";
	}

	private static String lookup(String key)
	{
		return "{\"keys\":[\"" + key + "\"]}";
	}

	@Test
	public void publishThenLookupThenClear() throws Exception
	{
		String base = start(1000, 100, false);

		assertEquals(204, send("PUT", base + "/v1/status", publish(BOB, BOB_TO_ALICE, "TOB")).statusCode());

		HttpResponse<String> found = send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE));
		assertEquals(200, found.statusCode());
		assertTrue(found.body().contains("\"TOB\""));

		String clear = "{\"id\":\"" + BOB + "\",\"keys\":[],\"activities\":[]}";
		assertEquals(204, send("PUT", base + "/v1/status", clear).statusCode());
		assertEquals("{\"statuses\":[]}", send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE)).body());
	}

	@Test
	public void responseNeverContainsOwnerIds() throws Exception
	{
		String base = start(1000, 100, false);
		send("PUT", base + "/v1/status", publish(BOB, BOB_TO_ALICE, "TOB"));
		assertFalse(send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE)).body().contains(BOB));
	}

	@Test
	public void rejectsBadInput() throws Exception
	{
		String base = start(1000, 100, false);
		assertEquals(400, send("PUT", base + "/v1/status", "not json").statusCode());
		assertEquals(400, send("PUT", base + "/v1/status", publish("nope", BOB_TO_ALICE, "TOB")).statusCode());
		assertEquals(400, send("PUT", base + "/v1/status", publish(BOB, "nope", "TOB")).statusCode());
		assertEquals(400, send("PUT", base + "/v1/status", publish(BOB, BOB_TO_ALICE, "lower case")).statusCode());
		assertEquals(400, send("POST", base + "/v1/status/lookup", "{}").statusCode());

		String tooMany = "{\"keys\":[\"" + String.join("\",\"", Collections.nCopies(LftServer.MAX_KEYS + 1, BOB_TO_ALICE)) + "\"]}";
		assertEquals(400, send("POST", base + "/v1/status/lookup", tooMany).statusCode());
	}

	@Test
	public void rateLimitsApiButNotHealth() throws Exception
	{
		String base = start(1000, 2, false);
		assertEquals(200, send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE)).statusCode());
		assertEquals(200, send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE)).statusCode());
		assertEquals(429, send("POST", base + "/v1/status/lookup", lookup(BOB_TO_ALICE)).statusCode());

		for (int i = 0; i < 5; i++)
		{
			assertEquals(200, send("GET", base + "/health", null).statusCode());
		}
	}

	@Test
	public void returns503WhenFull() throws Exception
	{
		String base = start(1, 100, false);
		assertEquals(204, send("PUT", base + "/v1/status", publish(BOB, BOB_TO_ALICE, "TOB")).statusCode());
		String alice = LftServer.ownerId("Alice");
		assertEquals(503, send("PUT", base + "/v1/status",
			publish(alice, LftServer.visibilityKey("Alice", "Bob"), "TOB")).statusCode());
	}

	@Test
	public void devEndpointsOnlyInDevMode() throws Exception
	{
		String prod = start(1000, 100, false);
		assertEquals(404, send("POST", prod + "/dev/seed", "{\"name\":\"Bob\"}").statusCode());
		stop();

		String dev = start(1000, 100, true);
		String seed = "{\"name\":\"Bob\",\"visibleTo\":[\"Alice\"],\"activities\":[\"TOB\"]}";
		assertEquals(200, send("POST", dev + "/dev/seed", seed).statusCode());
		assertTrue(send("POST", dev + "/v1/status/lookup", lookup(BOB_TO_ALICE)).body().contains("\"TOB\""));
	}
}

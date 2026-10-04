package dev.maxwellyoung.t3craft;

import com.sun.net.httpserver.Headers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Security boundary and recovery checks against isolated fixtures, without agent commands. */
public final class ReliabilityChecks {
	public static void main(String[] args) throws Exception {
		T3State unpaired = new T3State(event -> {});
		check(!unpaired.online(null) && unpaired.connectionError(null) == null, "unpaired panel has no thread owner");
		for (String host : new String[] {"127.0.0.1:25590", "localhost:25590", "[::1]:25590"}) {
			Headers headers = new Headers(); headers.set("Host", host);
			check(McpRequestPolicy.allows(headers, 25590), "native loopback host");
			for (String origin : new String[] {"http://localhost:3000", "http://localhost.attacker.example", "http://127.0.0.1.attacker.example", "null", ""}) {
				headers.set("Origin", origin);
				check(!McpRequestPolicy.allows(headers, 25590), "all browser origins refused");
			}
		}
		for (String host : new String[] {"localhost.attacker.example:25590", "127.0.0.1.attacker.example:25590", "localhost:25591", "localhost", "localhost:25590/path", "user@localhost:25590", "localhost:25590#fragment", "localhost:25590?query", "localhost.:25590", "127.1:25590"}) {
			Headers headers = new Headers(); headers.set("Host", host);
			check(!McpRequestPolicy.allows(headers, 25590), "noncanonical Host refused");
		}
		Headers missing = new Headers(); check(!McpRequestPolicy.allows(missing, 25590), "missing Host refused");
		missing.add("Host", "localhost:25590"); missing.add("Host", "attacker.example:25590");
		check(!McpRequestPolicy.allows(missing, 25590), "duplicate Host refused");
		String base = "http://127.0.0.1:" + args[0];
		get(base + "/_qa/reset");
		try {
			T3Api api = new T3Api(base, "fixture-only");
			api.thread("A-done-0", 4); // First request must negotiate before choosing the thread endpoint.
			get(base + "/_qa/auth-failed");
			try { api.shell(); throw new IllegalStateException("expected auth failure"); }
			catch (T3Api.AuthException e) { check(e.getMessage().contains("Settings > Connections"), "auth recovery instruction"); }
			get(base + "/_qa/reset"); get(base + "/_qa/protocol3");
			api.forgetProtocol();
			try { api.shell(); throw new IllegalStateException("expected unsupported protocol"); }
			catch (T3Api.UnsupportedVersionException e) { check(e.getMessage().contains("Update T3 Craft"), "future-protocol recovery instruction"); }
		} finally { get(base + "/_qa/reset"); }
		System.out.println("PASS MCP native-client boundary, exact Host validation, auth recovery and unsupported protocol rejection");
	}
	private static void get(String url) throws Exception {
		HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
	}
	private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}

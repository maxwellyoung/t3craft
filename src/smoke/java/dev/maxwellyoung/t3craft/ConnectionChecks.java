package dev.maxwellyoung.t3craft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** Exercises credential handling and real socket mirrors using only synthetic loopback tokens. */
public final class ConnectionChecks {
	public static void main(String[] args) throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("t3craft-connection-check-");
		try {
			T3Config config = new T3Config(); config.upsert(new T3Config.Environment("Synthetic", "http://localhost:1234", "fixture-only"));
			var file = dir.resolve("private.json"); check(config.save(file), "pairing persisted");
			check(java.nio.file.Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "replacement file stays owner-only");
			check(config.save(file) && T3Config.load(file).paired(), "atomic replacement reloads");
			check(!config.save(file.resolve("unwritable.json")) && T3Config.load(file).paired(), "failed replacement reports failure and leaves the existing pairing intact");
			try (var files = java.nio.file.Files.list(dir)) { check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temporary credential copies remain"); }
			java.nio.file.Files.delete(file);
		} finally { java.nio.file.Files.delete(dir); }

		String a = "http://127.0.0.1:" + args[0], b = "http://127.0.0.1:" + (Integer.parseInt(args[0]) + 1);
		get(a + "/_qa/reset"); get(b + "/_qa/reset");
		for (String bad : new String[] {"file:///fixture#token=fixture-secret", "http://user:fixture-secret@localhost/pair#token=fixture-secret", "http://localhost:0/pair#token=fixture-secret", "http://localhost:99999/pair#token=fixture-secret", "not a url fixture-secret", "http://localhost/pair#token=%zzfixture-secret", "http://localhost/pair?host=https%3A%2F%2Flocalhost%2Fpath#token=fixture-secret"}) {
			try { T3Api.pairingAddress(bad); throw new IllegalStateException("Invalid pairing accepted"); }
			catch (java.io.IOException e) { check(!e.getMessage().contains("fixture-secret"), "invalid link cannot leak through error text"); }
		}
		check(T3Api.pairingAddress(a + "/pair#token=fixture-pairing").equals(a), "fragment link");
		check(T3Api.pairingAddress(a + "/pair?token=fixture-pairing").equals(a), "query link");
		check(T3Api.pairingAddress("https://t3.codes/pair?host=" + java.net.URLEncoder.encode(a, java.nio.charset.StandardCharsets.UTF_8) + "#token=fixture-pairing").equals(a), "host wrapper link");
		try { T3Api.pair(a + "/pair#token=expired-fixture", "Minecraft"); throw new IllegalStateException("Expired pairing accepted"); }
		catch (java.io.IOException e) { check(e.getMessage().contains("fresh link") && !e.getMessage().contains("expired-fixture"), "server's echoed secret is never displayed"); }
		get(a + "/_qa/protocol3");
		try { T3Api.pair(a + "/pair#token=fixture-pairing", "Minecraft"); throw new IllegalStateException("Future pairing accepted"); }
		catch (T3Api.UnsupportedVersionException e) { check(e.getMessage().contains("Update T3 Craft"), "protocol validated before exchange"); }
		get(a + "/_qa/reset");
		var pairing = T3Api.pair(a + "/pair#token=fixture-pairing", "Minecraft");
		check(pairing.baseUrl().equals(a) && pairing.accessToken().equals("fixture-only"), "real form token exchange against fixture");
		T3Api apiA = new T3Api(a, "fixture-only"), apiB = new T3Api(b, "fixture-only");
		T3State state = new T3State(event -> {}); state.setPanelOpen(true);
		state.connect(List.of(new T3State.Connection(apiA, "Studio"), new T3State.Connection(apiB, "Studio")), "B-done-0");
		waitFor(() -> state.live() && state.snapshot().focus() != null && state.machines().size() == 2);
		var focus = state.snapshot().focus();
		state.reconfigure(List.of(new T3State.Connection(new T3Api(b, "fixture-only"), "Renamed")), "B-done-0");
		waitFor(() -> state.machines().size() == 1);
		check(state.apiFor("B-done-0") == apiB && state.online("B-done-0") && state.live(), "removing A retains B's actual API and live socket");
		check(state.snapshot().focus() != null && state.snapshot().focus().threadId().equals(focus.threadId()), "peer focused conversation retained");
		check(state.apiFor("A-done-0") == null && state.decisions().stream().noneMatch(e -> e.thread().ownerKey().equals(a)), "removed machine cannot route or retain decisions");
		state.reconfigure(List.of(new T3State.Connection(apiA, "Studio"), new T3State.Connection(new T3Api(b, "fixture-only"), "Renamed")), "B-done-0");
		waitFor(() -> state.machines().size() == 2 && state.online("A-done-0"));
		check(state.apiFor("B-done-0") == apiB, "adding A preserves B API");
		get(a + "/_qa/auth-failed"); waitFor(() -> state.machines().stream().anyMatch(m -> m.owner().equals(a) && m.error() != null && m.error().contains("Settings > Connections")));
		check(state.machines().stream().anyMatch(m -> m.owner().equals(a) && m.error() != null && m.error().contains("Settings > Connections")), "health belongs to failed machine");
		get(a + "/_qa/reset"); state.retry(a); waitFor(() -> state.online("A-done-0"));
		check(state.apiFor("B-done-0") == apiB && state.online("B-done-0"), "retry A leaves B untouched");
		state.reconfigure(List.of(new T3State.Connection(new T3Api(a, "rotated-fixture"), "Studio"), new T3State.Connection(new T3Api(b, "fixture-only"), "Renamed")), "B-done-0");
		waitFor(() -> state.apiFor("A-done-0") != apiA && state.online("A-done-0"));
		check(state.apiFor("B-done-0") == apiB, "credential rotation replaces only A");
		get(a + "/_qa/empty"); waitFor(() -> state.machines().stream().anyMatch(m -> m.owner().equals(a) && m.threads() == 0));
		check(state.machines().stream().anyMatch(m -> m.owner().equals(a) && m.online()), "healthy empty machine remains available");
		state.reconfigure(List.of(), null); waitFor(() -> state.machines().isEmpty());
		check(!state.snapshot().connected() && state.snapshot().threads().isEmpty(), "last removal gives clean unpaired state");
		get(a + "/_qa/reset"); get(b + "/_qa/reset");
		System.out.println("PASS private pairing errors, protocol gate, form exchange, owner health, empty machine, targeted retry/remove/rotation and retained peer socket/context");
	}
	private static void get(String url) throws Exception { HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()); }
	private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
		long deadline = System.currentTimeMillis() + 18000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(100);
		check(condition.getAsBoolean(), "state convergence timed out");
	}
	private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}

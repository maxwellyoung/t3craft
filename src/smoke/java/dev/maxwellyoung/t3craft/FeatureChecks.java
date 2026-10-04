package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Regression checks against two isolated fixtures, never real approvals or agent sessions. */
public final class FeatureChecks {
	public static void main(String[] args) throws Exception {
		String a = "http://127.0.0.1:" + args[0], b = "http://127.0.0.1:" + (Integer.parseInt(args[0]) + 1);
		get(a + "/_qa/reset"); get(b + "/_qa/reset");
		T3Api apiA = new T3Api(a, "fixture-only"), apiB = new T3Api(b, "fixture-only");
		T3State state = new T3State(event -> {});
		state.connect(List.of(new T3State.Connection(apiA, "Fixture A"), new T3State.Connection(apiB, "Fixture B")), "A-done-0");
		state.setPanelOpen(true);
		waitFor(() -> state.decisions().stream().filter(e -> e.requestId() != null).count() == 2 && state.live());
		check(state.snapshot().threads().size() == 26, "all threads published");
		check(state.decisions().getFirst().thread().id().equals("A-waiting"), "old waiting thread remains reachable beyond eight residents");
		check(state.decisions().stream().map(T3Decisions.Entry::key).distinct().count() == 2, "same request id on two threads remains distinct");
		check(state.apiFor("unknown-thread") == null, "unknown thread cannot fall back to another machine");
		var review = apiA.review("A-done-0");
		var files = T3Diff.files(review.diff());
		check(review.reply().startsWith("Updated the greeting"), "reply belongs to checkpoint, not a later unrelated assistant message");
		check(review.turnCount() == 1 && files.size() == 2, "actual fixture RPC yields a completed checkpoint and two files");
		check(files.get(1).path().equals("obsolete.txt") && files.get(1).lines().contains("+++ /dev/null"), "deleted file is retained");
		check(T3Diff.files("").isEmpty(), "no-change patch has no files");
		check(T3Diff.files("diff --git a/image.png b/image.png\nBinary files a/image.png and b/image.png differ\n").getFirst().path().equals("image.png"), "binary file is retained");
		check(T3Diff.files("diff --git a/old b/new\nsimilarity index 100%\nrename from old\nrename to new\n").getFirst().path().equals("new"), "pure rename is retained");
		check(T3Diff.files("diff --git a/old b/new\n--- a/old\n+++ b/new\n@@ -1 +1 @@\n-a\n+b\n").getFirst().path().equals("new"), "new path wins over old header");
		state.focus("B-waiting");
		check(respond(state, "A-waiting", null) == null, "approval stays bound to A after focus changes to B");
		var dispatchedA = JsonParser.parseString(get(a + "/_qa/dispatches")).getAsJsonArray();
		check(dispatchedA.size() == 1 && dispatchedA.get(0).getAsJsonObject().get("threadId").getAsString().equals("A-waiting"), "only A receives the approval");
		check(JsonParser.parseString(get(b + "/_qa/dispatches")).getAsJsonArray().isEmpty(), "B receives no accidental approval");
		get(b + "/_qa/resolve");
		JsonObject answers = new JsonObject(); answers.addProperty("format", "JSON");
		check(respond(state, "B-waiting", answers) != null, "already-resolved request is refused despite stale shell status");
		check(JsonParser.parseString(get(b + "/_qa/dispatches")).getAsJsonArray().isEmpty(), "resolved question is not dispatched");
		get(a + "/_qa/offline");
		waitFor(() -> !state.online("A-waiting"));
		check(respond(state, "A-waiting", null) != null, "offline machine cannot receive a response");
		check(state.online("B-waiting"), "healthy machine remains available when A disconnects");
		get(a + "/_qa/reset");
		waitFor(() -> state.online("A-waiting") && state.decisions().stream().anyMatch(e -> e.thread().id().equals("A-waiting") && e.requestId() != null));
		get(b + "/_qa/collision");
		waitFor(() -> state.apiFor("A-done-0") == null && !state.online("A-done-0") && state.connectionError("A-done-0") != null);
		check(state.connectionError("A-done-0").contains("multiple machines"), "cloned thread identity refuses ambiguous routing with guidance");
		get(a + "/_qa/reset"); get(b + "/_qa/reset");
		System.out.println("PASS decision reachability, exact request routing, stale/offline rejection, reconnect recovery, ambiguous-owner refusal, checkpoint RPC, deletion/binary/rename/no-change patches");
		System.exit(0);
	}
	private static Exception respond(T3State state, String thread, JsonObject answers) throws Exception {
		AtomicReference<Exception> error = new AtomicReference<>();
		CountDownLatch complete = new CountDownLatch(1);
		state.respond(thread, "shared-request", "accept", answers, error::set);
		// The executor's next action runs after the response and its refresh.
		state.run(complete::countDown, error::set);
		if (!complete.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("response timeout");
		return error.get();
	}
	private static String get(String url) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
	}
	private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
		for (int i = 0; i < 200; i++) { if (condition.getAsBoolean()) return; Thread.sleep(100); }
		throw new IllegalStateException("fixture state timeout");
	}
	private static void check(boolean result, String message) { if (!result) throw new IllegalStateException(message); }
}

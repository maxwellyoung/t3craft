package dev.maxwellyoung.t3craft;

import java.nio.file.Path;

/** Read-only compatibility probe using an existing local pairing file; prints no private content. */
public final class ReviewProbe {
	public static void main(String[] args) throws Exception {
		T3Config config = T3Config.load(Path.of(args[0]));
		if (!config.paired()) throw new IllegalStateException("No existing pairing in this file.");
		var environment = config.environments.getFirst();
		T3Api api = new T3Api(environment.baseUrl, environment.accessToken);
		int checked = 0;
		var candidates = new java.util.ArrayList<com.google.gson.JsonObject>();
		for (var value : api.shell().getAsJsonArray("threads")) candidates.add(value.getAsJsonObject());
		candidates.sort(java.util.Comparator.comparing(thread -> !java.util.Objects.equals(config.threadId, thread.get("id").getAsString())));
		for (var value : candidates) {
			if (checked++ >= 8) break;
			var thread = value.getAsJsonObject();
			String id = thread.get("id").getAsString();
			var detail = api.thread(id, 4);
			if (!detail.has("checkpoints") || detail.getAsJsonArray("checkpoints").isEmpty()) continue;
			var review = api.review(id);
			var activity = T3State.focus(detail).activity();
			System.out.println("PASS installed T3 read-only checkpoint RPC: checkpoint " + review.turnCount()
				+ ", " + T3Diff.files(review.diff()).size() + " changed files; checkpoint reply present=" + review.reply().contains("T3CRAFT_E2E_OK")
				+ "; activity items=" + activity.size() + "; command receipts=" + activity.stream().filter(e -> "command_execution".equals(e.kind()) && e.detail() != null && e.detail().contains("Exit code:")).count()
				+ "; no content logged or actions dispatched.");
			return;
		}
		throw new IllegalStateException("No completed checkpoint found in the sampled threads.");
	}
}

package dev.maxwellyoung.t3craft;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-agent check. The project must be an explicitly supplied disposable Git fixture. */
public final class LiveChecks {
	public static void main(String[] args) throws Exception {
		var config = T3Config.load(Path.of(args[0]));
		var project = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
		Path root = Path.of(project.get("workspaceRoot").getAsString());
		if (!Files.readString(root.resolve("greeting.txt")).equals("hello\n") || !Files.exists(root.resolve("obsolete.txt")))
			throw new IllegalStateException("Expected the explicit disposable Git fixture.");
		var environment = config.environments.getFirst();
		T3Api api = new T3Api(environment.baseUrl, environment.accessToken);
		api.shell();
		T3Socket socket = api.openSocket(reason -> {});
		List<T3Api.Provider> providers;
		try { providers = api.providers(socket); } finally { socket.close(); }
		var provider = providers.stream().filter(p -> p.name().toLowerCase().contains("codex")).findFirst().orElseGet(providers::getFirst);
		JsonObject template = new JsonObject();
		template.add("projectId", project.get("projectId")); template.add("branch", JsonNull.INSTANCE); template.add("worktreePath", JsonNull.INSTANCE);
		template.add("modelSelection", T3Api.modelSelection(provider.instanceId(), provider.models().getFirst().slug()));
		template.addProperty("runtimeMode", "approval-required"); template.addProperty("interactionMode", "default");
		String prompt = "T3 Craft release QA in this disposable repository only. Change greeting.txt to exactly hello, Minecraft followed by a newline. Delete obsolete.txt. Do not edit any other file, commit, push, or access other directories. Finish with T3CRAFT_E2E_OK.";
		String id = api.startThread(template, prompt, null);
		Files.writeString(Path.of(args[1]).resolveSibling("t3craft-qa-thread.txt"), id);
		T3State state = new T3State(event -> {});
		state.connect(List.of(new T3State.Connection(api, "Release QA")), id);
		state.setPanelOpen(true);
		int answered = 0; boolean complete = false;
		for (int i = 0; i < 300; i++) {
			var snapshot = state.snapshot(); var focus = snapshot.focus(); var row = snapshot.focusedRow();
			if (focus != null && id.equals(focus.threadId())) {
				for (var approval : focus.approvals()) {
					CountDownLatch done = new CountDownLatch(1); AtomicReference<Exception> error = new AtomicReference<>();
					state.respond(id, approval.requestId(), "accept", null, error::set);
					state.run(done::countDown, error::set);
					if (!done.await(30, TimeUnit.SECONDS) || error.get() != null) throw new IllegalStateException("QA approval failed", error.get());
					answered++;
				}
				if (row != null && row.status() == T3State.Status.ERROR) throw new IllegalStateException("QA agent run failed");
				if (row != null && row.status() == T3State.Status.DONE && focus.messages().stream().anyMatch(m -> "assistant".equals(m.role()) && m.text().contains("T3CRAFT_E2E_OK"))) { complete = true; break; }
			}
			Thread.sleep(1000);
		}
		if (!complete) throw new IllegalStateException("QA run timed out");
		if (!state.live()) throw new IllegalStateException("Live subscriptions were not established");
		if (!Files.readString(root.resolve("greeting.txt")).equals("hello, Minecraft\n") || Files.exists(root.resolve("obsolete.txt"))) throw new IllegalStateException("QA file result mismatch");
		var review = api.review(id);
		if (!review.diff().contains("+hello, Minecraft") || T3Diff.files(review.diff()).stream().noneMatch(f -> f.path().equals("obsolete.txt"))) throw new IllegalStateException("Real checkpoint diff mismatch");
		System.out.println("PASS installed T3 protocol: live streams, new thread, real agent edit/deletion, " + answered + " QA approvals, completed checkpoint " + review.turnCount() + ", " + T3Diff.files(review.diff()).size() + " files");
		System.exit(0);
	}
}

package dev.maxwellyoung.t3craft;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** End-to-end check of pairing, prompting, and polling against a live T3 server, without Minecraft. */
public final class Smoke {
	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args[0].isBlank()) throw new IllegalArgumentException("usage: smoke <pairing url> [prompt]");
		String prompt = args.length > 1 && !args[1].isBlank() ? args[1] : "Reply with exactly: pong";

		T3Api.Pairing pairing = T3Api.pair(args[0], "T3 Craft smoke");
		System.out.println("paired: " + pairing.baseUrl() + " (token expires in " + pairing.expiresInSeconds() / 86400 + "d)");

		CountDownLatch settled = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
		T3Api api = new T3Api(pairing.baseUrl(), pairing.accessToken());
		T3State[] holder = new T3State[1];
		T3State state = new T3State(event -> {
			if (!armed.get()) return;
			System.out.println("event: " + event.thread().title() + " -> " + event.status());
			if (event.status() != T3State.Status.NEEDS_YOU) {
				settled.countDown();
				return;
			}
			// Answer the way the in-game panel does: accept the oldest pending approval.
			var focus = holder[0].snapshot().focus();
			if (focus == null || focus.approvals().isEmpty()) return;
			var approval = focus.approvals().getFirst();
			System.out.println("approval: " + approval.kind() + " " + approval.detail());
			holder[0].run(() -> api.respondToApproval(event.thread().id(), approval.requestId(), "accept"),
				e -> System.out.println("approve failed: " + e.getMessage()));
		});
		holder[0] = state;
		state.connect(api, null);
		waitFor(() -> state.snapshot().connected() && !state.snapshot().threads().isEmpty(), 10);
		System.out.println("live stream: " + state.live());
		T3State.Snapshot snapshot = state.snapshot();
		snapshot.threads().forEach(row -> System.out.println("thread: " + row.projectTitle() + " / " + row.title() + " [" + row.status() + "]"));
		if (snapshot.threads().isEmpty()) throw new IllegalStateException("no threads to prompt");
		// Prefer a settled thread; one stuck on an approval would keep asking after each decline.
		T3State.ThreadRow target = snapshot.threads().stream()
			.filter(row -> row.status() == T3State.Status.DONE || row.status() == T3State.Status.IDLE)
			.findFirst().orElse(snapshot.threads().getFirst());
		state.focus(target.id());
		waitFor(() -> state.snapshot().focusedRow() != null && state.snapshot().focus() != null, 10);

		// Leftovers from an earlier run would hide the transition this test waits for.
		for (var stale : state.snapshot().focus().approvals()) api.respondToApproval(target.id(), stale.requestId(), "decline");
		waitFor(() -> {
			T3State.ThreadRow row = state.snapshot().focusedRow();
			return row != null && (row.status() == T3State.Status.DONE || row.status() == T3State.Status.IDLE || row.status() == T3State.Status.ERROR);
		}, 120);
		target = state.snapshot().focusedRow();
		armed.set(true);
		if (Boolean.getBoolean("t3.newThread")) {
			// Fresh thread → fresh provider session, so newly added MCP config is picked up.
			String id = api.startThread(target.raw(), prompt, null);
			state.focus(id);
			waitFor(() -> state.snapshot().focusedRow() != null, 20);
		} else {
			api.sendPrompt(target.raw(), prompt, null);
		}
		System.out.println("sent to: " + target.title());
		for (int i = 0; i < 180 && !settled.await(2, TimeUnit.SECONDS); i++) {
			T3State.ThreadRow row = state.snapshot().focusedRow();
			System.out.println("  … " + (row == null ? "?" : row.status()) + (state.snapshot().error() == null ? "" : " error=" + state.snapshot().error()));
		}
		if (settled.getCount() > 0) throw new IllegalStateException("turn did not settle in 180s");

		waitFor(() -> state.snapshot().focusedRow().status() != T3State.Status.WORKING, 10);
		var messages = state.snapshot().focus().messages();
		System.out.println("last message (" + messages.getLast().role() + "): " + messages.getLast().text());
		System.exit(0);
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, int seconds) throws InterruptedException {
		for (int i = 0; i < seconds * 10; i++) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(100);
		}
		throw new IllegalStateException("timed out");
	}
}

package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Follows one or more T3 environments (this Mac, a home server, …) and reduces them to what the game shows: a short thread list,
 * the focused thread's recent conversation, and pending approvals. Transitions on
 * watched threads (the focused one plus any prompted from Minecraft) become events,
 * which is what lets the player walk away and get pinged.
 *
 * Plain Java on purpose so the smoke test can run it without Minecraft.
 */
public final class T3State {
	public enum Status { IDLE, WORKING, NEEDS_YOU, DONE, ERROR }

	public record ThreadRow(String id, String title, String projectTitle, String environment, String ownerKey, Status status,
		Instant workingSince, String step, JsonObject raw) {}

	public record Message(String role, String text, boolean streaming) {}

	public record Approval(String requestId, String kind, String detail) {}

	public record Option(String label, String description, String value) {}

	public record Question(String id, String header, String question, List<Option> options,
		boolean multiSelect, boolean allowCustomAnswer) {}

	/** A pending question set from the agent (Claude's AskUserQuestion and friends). */
	public record UserInput(String requestId, List<Question> questions) {}

	public record Focus(String threadId, List<Message> messages, List<Approval> approvals,
		List<UserInput> userInputs, String lastError, List<T3Activity.Entry> activity) {
		public boolean needsInput() {
			return !userInputs.isEmpty();
		}
	}

	public record Snapshot(boolean connected, String error, List<ThreadRow> threads, String focusedId, Focus focus) {
		public ThreadRow focusedRow() {
			if (focusedId == null) return null;
			for (ThreadRow row : threads) if (row.id().equals(focusedId)) return row;
			return null;
		}
	}

	public record Event(ThreadRow thread, Status status) {}


	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "t3craft-poller");
		thread.setDaemon(true);
		return thread;
	});
	private final Set<String> watched = ConcurrentHashMap.newKeySet();
	private final Map<String, Status> lastStatus = new HashMap<>();
	private final Map<String, String> lastTurn = new HashMap<>();
	// Approval/question ids already announced, per thread. The shell only says "has approvals", so a
	// second request while the thread is still waiting would otherwise never ping.
	private final Map<String, Set<String>> seenApprovals = new HashMap<>();
	private final Consumer<Event> onEvent;
	// What waiting threads are asking (approval command or question), shown on the waiting villager.
	private final Map<String, String> waitingDetails = new ConcurrentHashMap<>();
	private final Map<String, JsonObject> waitingThreads = new HashMap<>();
	private final Map<String, Long> waitingFetchedAt = new HashMap<>();
	private volatile List<T3Decisions.Entry> decisions = List.of();
	private volatile Map<String, Boolean> onlineThreads = Map.of();
	private volatile Map<String, String> connectionErrors = Map.of();
	private volatile List<String> offlineMachines = List.of();
	public record MachineHealth(String owner, String label, boolean online, boolean live, int protocol, int threads, String error) {}
	private volatile List<MachineHealth> machines = List.of();
	List<MachineHealth> machines() { return machines; }

	List<T3Decisions.Entry> decisions() { return decisions; }
	public boolean online(String threadId) { return threadId != null && onlineThreads.getOrDefault(threadId, false); }
	String connectionError(String threadId) { return threadId == null ? null : connectionErrors.get(threadId); }
	List<String> offlineMachines() { return offlineMachines; }

	/** One paired environment: its API, socket, and shell mirror. Owned by the executor thread. */
	private static final class Env {
		final T3Api api;
		String label;
		final Map<String, String> projectTitles = new HashMap<>();
		final Map<String, JsonObject> threads = new ConcurrentHashMap<>();
		T3Socket socket;
		boolean shellLive;
		boolean shellLoaded;
		String threadRequest;
		String threadRequestFor;
		long nextSocketAttempt;
		int socketFailures;
		String error;
		volatile List<T3Api.Provider> providers = List.of();
		long providersLoadedAt;

		Env(T3Api api, String label) {
			this.api = api;
			this.label = label;
		}
	}

	public record Connection(T3Api api, String label) {}

	private volatile List<Env> envs = List.of();
	private Focus focusDetail;

	private volatile String focusedThreadId;
	private volatile Snapshot snapshot = new Snapshot(false, null, List.of(), null, null);
	private volatile boolean panelOpen;
	private volatile boolean refreshSoon;
	private long tick;
	private boolean focusFetchScheduled;

	public T3State(Consumer<Event> onEvent) {
		this.onEvent = onEvent;
		executor.scheduleWithFixedDelay(this::maintainSafely, 0, 1, TimeUnit.SECONDS);
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** API of the environment that owns the focused thread (or the first one). */
	public T3Api api() {
		Env env = envFor(focusedThreadId);
		return env == null ? null : env.api;
	}

	/** API of the environment that owns {@code threadId}; falls back to the first environment. */
	public T3Api apiFor(String threadId) {
		Env env = envFor(threadId);
		return env == null ? null : env.api;
	}

	private Env envFor(String threadId) {
		List<Env> current = envs;
		if (threadId != null) {
			Env owner = null;
			for (Env env : current) if (env.threads.containsKey(threadId)) {
				if (owner != null) return null; // A copied environment must never route a shared id to the first machine.
				owner = env;
			}
			return owner;
		}
		return threadId != null || current.isEmpty() ? null : current.getFirst();
	}

	/** True while every environment streams over its socket rather than being polled. */
	public boolean live() {
		List<Env> current = envs;
		return !current.isEmpty() && current.stream().allMatch(env -> env.shellLive);
	}

	/** Models offered by the focused thread's environment; empty until loaded. */
	public List<T3Api.Provider> providers() {
		Env env = envFor(focusedThreadId);
		return env == null ? List.of() : env.providers;
	}

	/** The config payload is large (~700 KB), so reload at most every five minutes. */
	private void loadProvidersIfStale(Env env) {
		if (env.socket == null || !env.socket.isOpen() || System.currentTimeMillis() - env.providersLoadedAt < 5 * 60_000) return;
		env.providersLoadedAt = System.currentTimeMillis();
		try {
			env.providers = env.api.providers(env.socket);
		} catch (Exception e) {
			env.providersLoadedAt = 0;
			T3Log.LOGGER.warn("Could not load models from {}", env.label, e);
		}
	}

	public void connect(T3Api api, String focusedThreadId) {
		connect(api == null ? List.of() : List.of(new Connection(api, "T3")), focusedThreadId);
	}

	public void connect(List<Connection> connections, String focusedThreadId) {
		focus(focusedThreadId);
		// Reset on the executor so no update can mix old and new baselines.
		executor.execute(() -> {
			for (Env env : envs) closeSocket(env);
			lastStatus.clear();
			lastTurn.clear();
			seenApprovals.clear();
			waitingDetails.clear();
			waitingThreads.clear();
			waitingFetchedAt.clear();
			decisions = List.of();
			onlineThreads = Map.of();
			connectionErrors = Map.of(); machines = List.of(); offlineMachines = List.of();
			focusDetail = null;
			List<Env> next = new ArrayList<>();
			for (Connection connection : connections) next.add(new Env(connection.api(), connection.label()));
			envs = List.copyOf(next);
			snapshot = new Snapshot(false, null, List.of(), focusedThreadId, null);
			maintainSafely();
		});
	}

	/** Change only affected owners; healthy sockets and cached conversations stay resident. */
	void reconfigure(List<Connection> connections, String focusedId) {
		focusedThreadId = focusedId;
		executor.execute(() -> {
			List<Env> next = new ArrayList<>();
			for (Connection connection : connections) {
				Env retained = envs.stream().filter(e -> e.api.sameCredentials(connection.api())).findFirst().orElse(null);
				if (retained == null) {
					retained = new Env(connection.api(), connection.label());
					Env previous = envs.stream().filter(e -> e.api.ownerKey().equals(connection.api().ownerKey())).findFirst().orElse(null);
					if (previous != null) {
						retained.threads.putAll(previous.threads); retained.projectTitles.putAll(previous.projectTitles);
						retained.error = "Reconnecting…";
					}
				} else retained.label = connection.label();
				next.add(retained);
			}
			for (Env previous : envs) if (!next.contains(previous)) closeSocket(previous);
			Env previousOwner = envFor(focusedId);
			if (previousOwner == null || next.stream().noneMatch(e -> e.api.ownerKey().equals(previousOwner.api.ownerKey()))) focusDetail = null;
			envs = List.copyOf(next);
			Set<String> retainedIds = new HashSet<>();
			for (Env env : next) retainedIds.addAll(env.threads.keySet());
			lastStatus.keySet().retainAll(retainedIds); lastTurn.keySet().retainAll(retainedIds);
			seenApprovals.keySet().retainAll(retainedIds); waitingThreads.keySet().retainAll(retainedIds);
			waitingDetails.keySet().retainAll(retainedIds); waitingFetchedAt.keySet().retainAll(retainedIds);
			publish(List.of()); refreshSoon = true; maintainSafely();
		});
	}

	void retry(String owner) {
		executor.execute(() -> {
			for (Env env : envs) if (env.api.ownerKey().equals(owner)) {
				closeSocket(env); env.api.forgetProtocol(); env.nextSocketAttempt = 0; env.socketFailures = 0;
				env.error = "Reconnecting…";
			}
			publish(List.of()); refreshSoon = true; maintainSafely();
		});
	}

	public void focus(String threadId) {
		focusedThreadId = threadId;
		if (threadId != null) watched.add(threadId);
		refreshSoon = true;
		executor.execute(() -> {
			focusDetail = null;
			for (Env env : envs) syncThreadSubscription(env);
			fetchFocus();
			publish(List.of());
		});
	}

	public String focusedThreadId() {
		return focusedThreadId;
	}

	/** The command or question a waiting thread is asking, once fetched; null until then. */
	public String waitingDetail(String threadId) {
		return waitingDetails.get(threadId);
	}

	private void fetchWaitingDetail(String threadId) {
		Env env = envFor(threadId);
		if (env == null) return;
		try {
			JsonObject raw = env.api.thread(threadId, 4);
			Focus detail = focus(raw);
			waitingThreads.put(threadId, raw);
			waitingFetchedAt.put(threadId, System.currentTimeMillis());
			String text = !detail.approvals().isEmpty() ? detail.approvals().getFirst().detail()
				: !detail.userInputs().isEmpty() ? detail.userInputs().getFirst().questions().getFirst().question() : "";
			waitingDetails.put(threadId, text == null ? "" : text);
		} catch (Exception e) {
			env.error = "Could not refresh pending requests: " + e.getMessage();
			T3Log.LOGGER.debug("Could not load what {} is waiting on", threadId, e);
		}
	}

	/** Focused, or prompted from the game: the threads that ping. */
	public boolean isWatched(String threadId) {
		return watched.contains(threadId);
	}

	/** Threads prompted from the game stay watched so their completion still pings after switching away. */
	public void watch(String threadId) {
		watched.add(threadId);
		refreshSoon = true;
	}

	public void setPanelOpen(boolean open) {
		panelOpen = open;
		if (open) {
			refreshSoon = true;
			executor.execute(() -> envs.forEach(this::loadProvidersIfStale));
		}
	}

	/** Runs blocking API work off the game thread, then refreshes. */
	public void run(IoAction action, Consumer<Exception> onError) {
		executor.execute(() -> {
			try {
				action.run();
			} catch (Exception e) {
				onError.accept(e);
			}
			refreshSoon = true;
			if (!live()) maintainSafely();
			fetchFocus();
			publish(List.of());
		});
	}

	public void respond(String threadId, String requestId, String decision, JsonObject answers, Consumer<Exception> onError) {
		Env owner = envFor(threadId);
		run(() -> {
			if (owner == null || envFor(threadId) != owner || owner.error != null)
				throw new java.io.IOException("This machine is offline or the thread is no longer paired. Refresh before answering.");
			JsonObject raw = owner.api.thread(threadId, 4);
			Focus current = focus(raw);
			boolean pending = answers == null
				? current.approvals().stream().anyMatch(a -> a.requestId().equals(requestId))
				: current.userInputs().stream().anyMatch(a -> a.requestId().equals(requestId));
			if (!pending) throw new java.io.IOException("That request has already changed or been answered. Open the current request.");
			if (answers == null) owner.api.respondToApproval(threadId, requestId, decision);
			else owner.api.answerQuestions(threadId, requestId, answers);
			fetchWaitingDetail(threadId);
		}, onError);
	}

	public interface IoAction {
		void run() throws Exception;
	}

	/** Once a second: keep sockets up, ping them, and poll over HTTP only where a socket is down. */
	private void maintainSafely() {
		List<Env> current = envs;
		if (current.isEmpty()) return;
		tick++;
		Snapshot previous = snapshot;
		ThreadRow focused = previous.focusedRow();
		boolean busy = focused != null && (focused.status() == Status.WORKING || focused.status() == Status.NEEDS_YOU);
		boolean anyWatchedBusy = previous.threads().stream()
			.anyMatch(row -> watched.contains(row.id()) && row.status() == Status.WORKING);
		int period = refreshSoon || panelOpen || busy ? 1 : anyWatchedBusy ? 2 : 6;
		boolean pollNow = refreshSoon || tick % period == 0;
		boolean changed = false;
		for (Env env : current) {
			try {
				ensureSocket(env);
				if (env.shellLive && env.error == null) {
					if (tick % 15 == 0) env.socket.ping();
					syncThreadSubscription(env);
					continue;
				}
				if (!pollNow) continue;
				applyShellSnapshot(env, env.api.shell());
				env.error = null;
				changed = true;
			} catch (Exception e) {
				env.error = e.getMessage();
				changed = true;
			}
		}
		if (panelOpen && tick % 5 == 0) {
			for (ThreadRow row : allRows()) {
				Env owner = envFor(row.id());
				if (row.status() == Status.NEEDS_YOU && owner != null && owner.error == null
					&& System.currentTimeMillis() - waitingFetchedAt.getOrDefault(row.id(), 0L) >= 4000) {
					fetchWaitingDetail(row.id());
					changed = true;
				}
			}
		}
		if (refreshSoon || changed) {
			refreshSoon = false;
			fetchFocus();
			publishWithEvents();
		}
	}

	private void ensureSocket(Env env) {
		if (env.socket != null && env.socket.isOpen()) return;
		if (System.currentTimeMillis() < env.nextSocketAttempt) return;
		env.shellLive = false;
		env.threadRequest = null;
		env.threadRequestFor = null;
		try {
			T3Socket[] session = new T3Socket[1];
			T3Socket opened = env.api.openSocket(reason -> executor.execute(() -> {
				if (envs.contains(env) && env.socket == session[0]) onSocketClosed(env, reason);
			}));
			session[0] = opened;
			env.socket = opened;
			JsonObject payload = new JsonObject();
			payload.addProperty("requestCompletionMarker", true);
			opened.stream("orchestration.subscribeShell", payload,
				value -> executor.execute(() -> { if (env.socket == opened) onShellItem(env, value); }));
			env.socketFailures = 0;
			loadProvidersIfStale(env);
		} catch (Exception e) {
			env.socketFailures++;
			// Back off to at most a minute; HTTP polling covers the gap.
			env.nextSocketAttempt = System.currentTimeMillis() + Math.min(60_000, 2_000L << Math.min(5, env.socketFailures));
			T3Log.LOGGER.debug("{} socket unavailable; polling", env.label, e);
		}
	}

	private void onSocketClosed(Env env, String reason) {
		T3Log.LOGGER.info("{} socket closed: {}; polling until it reconnects", env.label, reason);
		env.shellLive = false;
		env.socket = null;
		env.threadRequest = null;
		env.threadRequestFor = null;
		env.nextSocketAttempt = System.currentTimeMillis() + 2_000;
		env.api.forgetProtocol();
		env.error = "Reconnecting…";
		publish(List.of());
	}

	private void closeSocket(Env env) {
		if (env.socket != null) env.socket.close();
		env.socket = null;
		env.shellLive = false;
		env.threadRequest = null;
		env.threadRequestFor = null;
	}

	private void onShellItem(Env env, JsonObject item) {
		switch (string(item, "kind")) {
			case "snapshot" -> {
				JsonObject shell = item.getAsJsonObject("snapshot");
				// Repository metadata refreshes carry an empty thread list, not a replacement snapshot.
				if (item.has("resolvedRepositoryIdentityRoots") && array(shell, "threads").isEmpty() && array(shell, "archivedThreads").isEmpty()) {
					for (var value : array(shell, "projects")) {
						JsonObject project = value.getAsJsonObject();
						env.projectTitles.put(string(project, "id"), string(project, "title"));
					}
				} else applyShellSnapshot(env, env.api.adaptShell(shell));
				publish(List.of());
			}
			case "synchronized" -> {
				env.shellLive = true;
				env.error = null;
				syncThreadSubscription(env);
				fetchFocus();
				publishWithEvents();
			}
			case "project-upserted", "project.updated" -> {
				JsonObject project = item.getAsJsonObject("project");
				env.projectTitles.put(string(project, "id"), string(project, "title"));
				publish(List.of());
			}
			case "project-removed", "project.removed" -> {
				env.projectTitles.remove(string(item, "projectId"));
				publish(List.of());
			}
			case "thread-upserted", "thread.updated" -> {
				JsonObject thread = env.api.adaptShellThread(item.getAsJsonObject("thread"));
				if ("archive".equals(string(item, "location"))) {
					env.threads.remove(string(thread, "id"));
					publishWithEvents();
					return;
				}
				env.threads.put(string(thread, "id"), thread);
				// Refetch before announcing, so a "needs you" ping already carries the approval.
				if (string(thread, "id").equals(focusedThreadId)) fetchFocus();
				publishWithEvents();
			}
			case "thread-removed", "thread.removed" -> {
				env.threads.remove(string(item, "threadId"));
				publishWithEvents();
			}
			case null, default -> {
			}
		}
	}

	/** Follows the focused thread's event stream in whichever environment owns it. */
	private void syncThreadSubscription(Env env) {
		if (!env.shellLive || env.socket == null) return;
		String want = focusedThreadId != null && env.threads.containsKey(focusedThreadId) ? focusedThreadId : null;
		if (want == null ? env.threadRequestFor == null : want.equals(env.threadRequestFor)) return;
		env.socket.interrupt(env.threadRequest);
		env.threadRequest = null;
		env.threadRequestFor = want;
		if (want == null) return;
		JsonObject payload = new JsonObject();
		payload.addProperty("threadId", want);
		T3Socket current = env.socket;
		env.threadRequest = current.stream("orchestration.subscribeThread", payload,
			value -> executor.execute(() -> { if (env.socket == current && want.equals(focusedThreadId)) scheduleFocusFetch(); }));
	}

	// Assistant text streams as many small events; coalesce them into ~4 fetches a second.
	private void scheduleFocusFetch() {
		if (focusFetchScheduled) return;
		focusFetchScheduled = true;
		executor.schedule(() -> {
			focusFetchScheduled = false;
			fetchFocus();
			publish(List.of());
		}, 250, TimeUnit.MILLISECONDS);
	}

	private void fetchFocus() {
		String threadId = focusedThreadId;
		Env env = envFor(threadId);
		if (env == null || threadId == null || !env.threads.containsKey(threadId)) {
			focusDetail = null;
			return;
		}
		try {
			JsonObject raw = env.api.thread(threadId, 4);
			focusDetail = focus(raw);
			waitingThreads.put(threadId, raw);
		} catch (Exception e) {
			env.error = e.getMessage();
		}
	}

	private static void applyShellSnapshot(Env env, JsonObject shell) {
		env.shellLoaded = true;
		env.projectTitles.clear();
		for (JsonElement project : shell.getAsJsonArray("projects")) {
			JsonObject p = project.getAsJsonObject();
			env.projectTitles.put(p.get("id").getAsString(), p.get("title").getAsString());
		}
		env.threads.clear();
		for (JsonElement element : shell.getAsJsonArray("threads")) {
			JsonObject thread = element.getAsJsonObject();
			env.threads.put(thread.get("id").getAsString(), thread);
		}
	}

	/** Every non-archived thread across environments; the label is dropped when there is only one. */
	private List<ThreadRow> allRows() {
		List<Env> current = envs;
		List<ThreadRow> rows = new ArrayList<>();
		for (Env env : current) {
			String label = current.size() > 1 ? env.label : null;
			for (JsonObject thread : env.threads.values()) {
				if (isNull(thread, "archivedAt")) rows.add(row(thread, env.projectTitles, label, env.api.ownerKey()));
			}
		}
		return rows;
	}

	private void publishWithEvents() {
		List<Event> events = new ArrayList<>();
		for (ThreadRow row : allRows()) {
			Status before = lastStatus.put(row.id(), row.status());
			if (row.status() == Status.NEEDS_YOU && (before != Status.NEEDS_YOU || !waitingDetails.containsKey(row.id()))) {
				waitingDetails.put(row.id(), ""); // pending; the fetch fills it in
				String id = row.id();
				executor.execute(() -> { fetchWaitingDetail(id); publish(List.of()); });
			} else if (row.status() != Status.NEEDS_YOU) {
				waitingDetails.remove(row.id());
				waitingThreads.remove(row.id());
				waitingFetchedAt.remove(row.id());
			}
			String turn = turnId(row.raw());
			String turnBefore = lastTurn.put(row.id(), turn);
			// A new turn id also counts: a short turn can start and finish between two slow polls.
			boolean changed = before != row.status() || (turn != null && !turn.equals(turnBefore));
			if (before != null && changed && watched.contains(row.id()) && row.status() != Status.WORKING
				&& row.status() != Status.IDLE) {
				events.add(new Event(row, row.status()));
			}
		}
		publish(events);
	}

	private List<Event> withNewApprovals(Focus focus, List<ThreadRow> rows, List<Event> events) {
		Set<String> ids = new HashSet<>();
		for (Approval approval : focus.approvals()) ids.add(approval.requestId());
		for (UserInput input : focus.userInputs()) ids.add(input.requestId());
		Set<String> seen = seenApprovals.put(focus.threadId(), ids);
		if (seen == null || seen.containsAll(ids) || !watched.contains(focus.threadId())) return events;
		ThreadRow row = rows.stream().filter(r -> r.id().equals(focus.threadId())).findFirst().orElse(null);
		if (row == null || events.stream().anyMatch(e -> e.thread().id().equals(row.id()) && e.status() == Status.NEEDS_YOU)) return events;
		List<Event> more = new ArrayList<>(events);
		more.add(new Event(row, Status.NEEDS_YOU));
		return more;
	}

	/** Rebuilds the published snapshot from the mirrors, then fires events against it. */
	private void publish(List<Event> events) {
		List<ThreadRow> rows = allRows();
		rows.sort(Comparator.comparing((ThreadRow row) -> activityAt(row.raw())).reversed());
		// Every active thread from every machine; the panel scrolls and filters.
		Map<String, ThreadRow> visible = new LinkedHashMap<>();
		for (ThreadRow row : rows) visible.put(row.id(), row);
		Focus focus = focusDetail != null && focusDetail.threadId().equals(focusedThreadId) ? focusDetail : null;
		if (focus != null) events = withNewApprovals(focus, rows, events);
		List<Env> current = envs;
		boolean connected = current.stream().anyMatch(env -> env.error == null && (env.shellLive || env.shellLoaded));
		String error = current.stream().filter(env -> env.error != null)
			.map(env -> (current.size() > 1 ? env.label + ": " : "") + env.error).reduce((a, b) -> a + " · " + b).orElse(null);
		Map<String, Boolean> health = new HashMap<>();
		Map<String, String> errors = new HashMap<>();
		for (Env env : current) for (String id : env.threads.keySet()) {
			if (health.containsKey(id)) {
				health.put(id, false);
				errors.put(id, "This thread ID appears on multiple machines. Open it directly in T3 and remove the duplicate pairing before reconnecting.");
			} else {
				health.put(id, env.error == null);
				if (env.error != null) errors.put(id, env.error);
			}
		}
		onlineThreads = Map.copyOf(health);
		connectionErrors = Map.copyOf(errors);
		machines = current.stream().map(env -> new MachineHealth(env.api.ownerKey(), env.label,
			env.error == null && (env.shellLive || env.shellLoaded), env.shellLive, env.api.negotiatedProtocol(), env.threads.size(), env.error)).toList();
		offlineMachines = current.stream().filter(env -> env.error != null || !env.shellLoaded && !env.shellLive)
			.map(env -> env.label).toList();
		decisions = T3Decisions.collect(rows, waitingThreads);
		snapshot = new Snapshot(connected, error, List.copyOf(visible.values()), focusedThreadId, focus);
		events.forEach(onEvent);
	}

	private static ThreadRow row(JsonObject thread, Map<String, String> projectTitles, String environment, String ownerKey) {
		JsonObject latestTurn = object(thread, "latestTurn");
		JsonObject session = object(thread, "session");
		String turnState = latestTurn == null ? null : string(latestTurn, "state");
		String sessionStatus = session == null ? null : string(session, "status");

		Status status;
		if (bool(thread, "hasPendingApprovals") || bool(thread, "hasPendingUserInput")) status = Status.NEEDS_YOU;
		else if ("running".equals(turnState) || "starting".equals(sessionStatus) || "running".equals(sessionStatus)) status = Status.WORKING;
		else if ("error".equals(turnState) || "error".equals(sessionStatus)) status = Status.ERROR;
		else if (latestTurn != null) status = Status.DONE;
		else status = Status.IDLE;

		Instant since = null;
		if (latestTurn != null) {
			String started = string(latestTurn, "startedAt");
			if (started == null) started = string(latestTurn, "requestedAt");
			if (started != null) since = Instant.parse(started);
		}
		JsonObject plan = object(thread, "planProgress");
		String step = plan == null ? null : string(plan, "step");
		return new ThreadRow(thread.get("id").getAsString(), string(thread, "title"),
			projectTitles.getOrDefault(string(thread, "projectId"), "?"), environment, ownerKey, status, since, step, thread);
	}

	static Focus focus(JsonObject thread) {
		List<Message> messages = new ArrayList<>();
		for (JsonElement element : array(thread, "messages")) {
			JsonObject message = element.getAsJsonObject();
			String role = string(message, "role");
			if ("system".equals(role)) continue;
			messages.add(new Message(role, string(message, "text"), bool(message, "streaming")));
		}

		// Same reduction as client-runtime's derivePendingRequests, limited to approvals.
		Map<String, Approval> approvals = new LinkedHashMap<>();
		Set<String> closed = new java.util.HashSet<>();
		Set<String> closedInputs = new java.util.HashSet<>();
		Map<String, UserInput> openInputs = new LinkedHashMap<>();
		for (JsonElement element : array(thread, "activities")) {
			JsonObject activity = element.getAsJsonObject();
			String kind = string(activity, "kind");
			JsonObject payload = object(activity, "payload");
			if (kind == null || payload == null) continue;
			String requestId = string(payload, "requestId");
			if (requestId == null) continue;
			switch (kind) {
				case "approval.requested" -> {
					String type = string(payload, "requestType");
					if (closed.contains(requestId) || "tool_user_input".equals(type) || "auth_tokens_refresh".equals(type)) break;
					String requestKind = string(payload, "requestKind");
					approvals.put(requestId, new Approval(requestId, requestKind == null ? "command" : requestKind,
						string(payload, "detail")));
				}
				case "approval.resolved" -> {
					closed.add(requestId);
					approvals.remove(requestId);
				}
				case "user-input.requested" -> {
					if (closedInputs.contains(requestId)) break;
					List<Question> questions = questions(payload);
					if (!questions.isEmpty()) openInputs.put(requestId, new UserInput(requestId, questions));
				}
				case "user-input.resolved" -> {
					closedInputs.add(requestId);
					openInputs.remove(requestId);
				}
				default -> {
				}
			}
		}
		JsonObject session = object(thread, "session");
		return new Focus(thread.get("id").getAsString(), Collections.unmodifiableList(messages),
			List.copyOf(approvals.values()), List.copyOf(openInputs.values()), session == null ? null : string(session, "lastError"), T3Activity.entries(thread));
	}

	private static List<Question> questions(JsonObject payload) {
		List<Question> questions = new ArrayList<>();
		for (JsonElement element : array(payload, "questions")) {
			if (!element.isJsonObject()) continue;
			JsonObject q = element.getAsJsonObject();
			List<Option> options = new ArrayList<>();
			for (JsonElement o : array(q, "options")) {
				if (!o.isJsonObject()) continue;
				JsonObject option = o.getAsJsonObject();
				String label = string(option, "label");
				if (label == null) continue;
				String value = string(option, "value");
				options.add(new Option(label, string(option, "description"), value == null ? label : value));
			}
			boolean allowCustom = !(q.has("allowCustomAnswer") && !q.get("allowCustomAnswer").isJsonNull() && !q.get("allowCustomAnswer").getAsBoolean());
			if (string(q, "id") == null || (options.isEmpty() && !allowCustom)) continue;
			questions.add(new Question(string(q, "id"), string(q, "header"), string(q, "question"), List.copyOf(options),
				bool(q, "multiSelect"), allowCustom));
		}
		return questions;
	}

	private static String turnId(JsonObject thread) {
		JsonObject latestTurn = object(thread, "latestTurn");
		return latestTurn == null ? null : string(latestTurn, "turnId");
	}

	private static String activityAt(JsonObject thread) {
		String at = string(thread, "latestUserMessageAt");
		String updated = string(thread, "updatedAt");
		if (at == null) return updated == null ? "" : updated;
		return updated != null && updated.compareTo(at) > 0 ? updated : at;
	}

	private static boolean isNull(JsonObject object, String key) {
		return !object.has(key) || object.get(key).isJsonNull();
	}

	private static JsonObject object(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonObject() ? null : object.getAsJsonObject(key);
	}

	private static JsonArray array(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonArray() ? new JsonArray() : object.getAsJsonArray(key);
	}

	private static String string(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonPrimitive() ? null : object.get(key).getAsString();
	}

	private static boolean bool(JsonObject object, String key) {
		return !isNull(object, key) && object.get(key).getAsBoolean();
	}
}

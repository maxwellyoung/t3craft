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
 * Follows one or more T3 environments (this Mac, Klaus, …) and reduces them to what the game shows: a short thread list,
 * the focused thread's recent conversation, and pending approvals. Transitions on
 * watched threads (the focused one plus any prompted from Minecraft) become events,
 * which is what lets the player walk away and get pinged.
 *
 * Plain Java on purpose so the smoke test can run it without Minecraft.
 */
public final class T3State {
	public enum Status { IDLE, WORKING, NEEDS_YOU, DONE, ERROR }

	public record ThreadRow(String id, String title, String projectTitle, String environment, Status status,
		Instant workingSince, String step, JsonObject raw) {}

	public record Message(String role, String text, boolean streaming) {}

	public record Approval(String requestId, String kind, String detail) {}

	public record Option(String label, String description, String value) {}

	public record Question(String id, String header, String question, List<Option> options,
		boolean multiSelect, boolean allowCustomAnswer) {}

	/** A pending question set from the agent (Claude's AskUserQuestion and friends). */
	public record UserInput(String requestId, List<Question> questions) {}

	public record Focus(String threadId, List<Message> messages, List<Approval> approvals,
		List<UserInput> userInputs, String lastError) {
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

	private static final int THREAD_LIST_LIMIT = 14;

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

	/** One paired environment: its API, socket, and shell mirror. Owned by the executor thread. */
	private static final class Env {
		final T3Api api;
		final String label;
		final Map<String, String> projectTitles = new HashMap<>();
		final Map<String, JsonObject> threads = new LinkedHashMap<>();
		T3Socket socket;
		boolean shellLive;
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
			for (Env env : current) if (env.threads.containsKey(threadId)) return env;
		}
		return current.isEmpty() ? null : current.getFirst();
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
			T3CraftClient.LOGGER.warn("Could not load models from {}", env.label, e);
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
			focusDetail = null;
			List<Env> next = new ArrayList<>();
			for (Connection connection : connections) next.add(new Env(connection.api(), connection.label()));
			envs = List.copyOf(next);
			snapshot = new Snapshot(false, null, List.of(), focusedThreadId, null);
			maintainSafely();
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
				if (env.shellLive) {
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
			T3Socket opened = env.api.openSocket(reason -> executor.execute(() -> onSocketClosed(env, reason)));
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
			T3CraftClient.LOGGER.debug("{} socket unavailable; polling", env.label, e);
		}
	}

	private void onSocketClosed(Env env, String reason) {
		T3CraftClient.LOGGER.info("{} socket closed: {}; polling until it reconnects", env.label, reason);
		env.shellLive = false;
		env.socket = null;
		env.threadRequest = null;
		env.threadRequestFor = null;
		env.nextSocketAttempt = System.currentTimeMillis() + 2_000;
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
				applyShellSnapshot(env, item.getAsJsonObject("snapshot"));
				publish(List.of());
			}
			case "synchronized" -> {
				env.shellLive = true;
				env.error = null;
				syncThreadSubscription(env);
				fetchFocus();
				publishWithEvents();
			}
			case "project-upserted" -> {
				JsonObject project = item.getAsJsonObject("project");
				env.projectTitles.put(string(project, "id"), string(project, "title"));
				publish(List.of());
			}
			case "project-removed" -> {
				env.projectTitles.remove(string(item, "projectId"));
				publish(List.of());
			}
			case "thread-upserted" -> {
				JsonObject thread = item.getAsJsonObject("thread");
				env.threads.put(string(thread, "id"), thread);
				// Refetch before announcing, so a "needs you" ping already carries the approval.
				if (string(thread, "id").equals(focusedThreadId)) fetchFocus();
				publishWithEvents();
			}
			case "thread-removed" -> {
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
			focusDetail = focus(env.api.thread(threadId, 4));
		} catch (Exception e) {
			env.error = e.getMessage();
		}
	}

	private static void applyShellSnapshot(Env env, JsonObject shell) {
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
				if (isNull(thread, "archivedAt")) rows.add(row(thread, env.projectTitles, label));
			}
		}
		return rows;
	}

	private void publishWithEvents() {
		List<Event> events = new ArrayList<>();
		for (ThreadRow row : allRows()) {
			Status before = lastStatus.put(row.id(), row.status());
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
		// Keep the focused and watched threads visible even when the list is trimmed.
		Map<String, ThreadRow> visible = new LinkedHashMap<>();
		for (ThreadRow row : rows) {
			if (visible.size() < THREAD_LIST_LIMIT || row.id().equals(focusedThreadId) || watched.contains(row.id())) {
				visible.put(row.id(), row);
			}
		}
		Focus focus = focusDetail != null && focusDetail.threadId().equals(focusedThreadId) ? focusDetail : null;
		if (focus != null) events = withNewApprovals(focus, rows, events);
		List<Env> current = envs;
		boolean connected = current.stream().anyMatch(env -> env.error == null && (env.shellLive || !env.threads.isEmpty()));
		String error = current.stream().filter(env -> env.error != null)
			.map(env -> (current.size() > 1 ? env.label + ": " : "") + env.error).reduce((a, b) -> a + " · " + b).orElse(null);
		snapshot = new Snapshot(connected, error, List.copyOf(visible.values()), focusedThreadId, focus);
		events.forEach(onEvent);
	}

	private static ThreadRow row(JsonObject thread, Map<String, String> projectTitles, String environment) {
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
			projectTitles.getOrDefault(string(thread, "projectId"), "?"), environment, status, since, step, thread);
	}

	private static Focus focus(JsonObject thread) {
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
			List.copyOf(approvals.values()), List.copyOf(openInputs.values()), session == null ? null : string(session, "lastError"));
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

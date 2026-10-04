package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestration protocol 2 (T3 Code 0.0.46 nightlies onward) reshaped into the protocol-1 fields the rest of
 * the mod reads. T3 replaced its turn/activity model with runs, runtime requests, and turn items; this keeps
 * that difference in one place so the panel, HUD, village, and books stay protocol-agnostic.
 *
 * Plain Java with no Minecraft types so it can be unit tested.
 */
final class T3V2 {
	private static final Set<String> ACTIVE_RUN = Set.of("preparing", "queued", "starting", "running", "waiting");
	// Requests the player can't answer from the game; the T3 apps skip them too.
	private static final Set<String> HIDDEN_REQUESTS = Set.of("auth_refresh", "dynamic_tool_call");

	private T3V2() {
	}

	/** True for a protocol-2 thread shell (it carries run fields instead of {@code latestTurn}). */
	static boolean isShellThread(JsonObject thread) {
		return thread.has("pendingRuntimeRequest") || thread.has("latestRunId");
	}

	/**
	 * Adds the protocol-1 fields T3State reads ({@code latestTurn}, {@code session}, {@code hasPendingApprovals},
	 * {@code hasPendingUserInput}) to a protocol-2 thread shell, in place. Protocol-1 threads pass through.
	 */
	static JsonObject normalizeShellThread(JsonObject thread) {
		if (!isShellThread(thread)) return thread;
		String status = string(thread, "status");
		String activity = string(thread, "activityRunStatus");
		boolean active = (status != null && ACTIVE_RUN.contains(status)) || activity != null;

		JsonObject pending = object(thread, "pendingRuntimeRequest");
		String pendingKind = pending == null ? null : string(pending, "kind");
		boolean visiblePending = pendingKind != null && !HIDDEN_REQUESTS.contains(pendingKind);
		thread.addProperty("hasPendingApprovals", visiblePending && !"user_input".equals(pendingKind));
		thread.addProperty("hasPendingUserInput", visiblePending && "user_input".equals(pendingKind));

		String runId = string(thread, "latestRunId");
		if (runId == null) {
			thread.add("latestTurn", JsonNull.INSTANCE);
		} else {
			JsonObject turn = new JsonObject();
			turn.addProperty("turnId", runId);
			turn.addProperty("state", active ? "running" : "failed".equals(status) ? "error"
				: "interrupted".equals(status) || "cancelled".equals(status) ? "interrupted" : "completed");
			String started = string(thread, "activityRunStartedAt");
			if (started == null) started = string(thread, "latestRunStartedAt");
			copy(started, turn, "startedAt");
			copy(string(thread, "latestRunRequestedAt"), turn, "requestedAt");
			copy(active ? null : string(thread, "latestRunCompletedAt"), turn, "completedAt");
			thread.add("latestTurn", turn);
		}

		JsonObject session = new JsonObject();
		session.addProperty("status", active ? "running" : "ready");
		copy(string(thread, "lastError"), session, "lastError");
		thread.add("session", session);
		return thread;
	}

	/**
	 * Turns a bounded thread snapshot ({@code GET /api/orchestration/threads/:id/bounded}) into the protocol-1
	 * thread detail shape: {@code messages}, {@code activities} (open approvals and questions),
	 * {@code checkpoints}, and {@code session.lastError}. Keeps the last {@code turnLimit} user turns.
	 */
	static JsonObject threadDetail(String threadId, JsonObject bounded, int turnLimit) {
		JsonObject projection = object(bounded, "projection");
		if (projection == null) projection = bounded;
		JsonObject detail = new JsonObject();
		detail.addProperty("id", threadId);

		List<JsonObject> items = new ArrayList<>();
		for (JsonElement row : array(projection, "visibleTurnItems")) {
			JsonObject item = row.isJsonObject() ? object(row.getAsJsonObject(), "item") : null;
			if (item != null) items.add(item);
		}

		// Conversation: user and assistant messages from the last turnLimit user turns.
		List<JsonObject> messages = new ArrayList<>();
		String lastError = null;
		for (JsonObject item : items) {
			String type = string(item, "type");
			if ("user_message".equals(type) || "assistant_message".equals(type)) {
				JsonObject message = new JsonObject();
				message.addProperty("role", "user_message".equals(type) ? "user" : "assistant");
				message.addProperty("text", string(item, "text") == null ? "" : string(item, "text"));
				message.addProperty("streaming", bool(item, "streaming"));
				messages.add(message);
				if ("user_message".equals(type)) lastError = null;
			} else if ("error".equals(type)) {
				JsonObject failure = object(item, "failure");
				if (failure != null) lastError = string(failure, "message");
			}
		}
		int start = 0;
		int users = 0;
		for (int i = messages.size() - 1; i >= 0; i--) {
			if ("user".equals(string(messages.get(i), "role")) && ++users == Math.max(1, turnLimit)) {
				start = i;
				break;
			}
		}
		JsonArray messageArray = new JsonArray();
		for (JsonObject message : messages.subList(start, messages.size())) messageArray.add(message);
		detail.add("messages", messageArray);

		// Open approvals and questions: pending runtime requests joined to the items that describe them.
		Map<String, JsonObject> requestItems = new HashMap<>();
		for (JsonElement element : array(projection, "turnItems")) {
			if (element.isJsonObject()) indexRequestItem(requestItems, element.getAsJsonObject());
		}
		for (JsonObject item : items) indexRequestItem(requestItems, item);
		JsonArray activities = new JsonArray();
		for (JsonElement element : array(projection, "runtimeRequests")) {
			if (!element.isJsonObject()) continue;
			JsonObject request = element.getAsJsonObject();
			String id = string(request, "id");
			String kind = string(request, "kind");
			if (id == null || kind == null || !"pending".equals(string(request, "status")) || HIDDEN_REQUESTS.contains(kind)) continue;
			JsonObject item = requestItems.get(id);
			JsonObject payload = new JsonObject();
			payload.addProperty("requestId", id);
			JsonObject activity = new JsonObject();
			if ("user_input".equals(kind)) {
				if (item == null || !"user_input_request".equals(string(item, "type"))) continue;
				payload.add("questions", array(item, "questions"));
				activity.addProperty("kind", "user-input.requested");
			} else {
				payload.addProperty("requestKind", kind);
				if (item != null) copy(string(item, "prompt"), payload, "detail");
				activity.addProperty("kind", "approval.requested");
			}
			activity.add("payload", payload);
			activities.add(activity);
		}
		detail.add("activities", activities);

		// The latest checkpoint's files, for the report book.
		JsonObject latest = null;
		for (JsonElement element : array(projection, "checkpoints")) {
			if (!element.isJsonObject()) continue;
			JsonObject checkpoint = element.getAsJsonObject();
			String at = string(checkpoint, "capturedAt");
			if (latest == null || (at != null && at.compareTo(String.valueOf(string(latest, "capturedAt"))) >= 0)) latest = checkpoint;
		}
		JsonArray checkpoints = new JsonArray();
		if (latest != null) {
			JsonObject checkpoint = new JsonObject();
			checkpoint.add("files", array(latest, "files"));
			checkpoints.add(checkpoint);
		}
		detail.add("checkpoints", checkpoints);

		JsonObject session = new JsonObject();
		copy(lastError, session, "lastError");
		detail.add("session", session);
		return detail;
	}

	private static void indexRequestItem(Map<String, JsonObject> index, JsonObject item) {
		String type = string(item, "type");
		String requestId = string(item, "requestId");
		if (requestId != null && ("approval_request".equals(type) || "user_input_request".equals(type))) index.put(requestId, item);
	}

	private static void copy(String value, JsonObject into, String key) {
		if (value == null) into.add(key, JsonNull.INSTANCE);
		else into.addProperty(key, value);
	}

	private static boolean isNull(JsonObject object, String key) {
		return object == null || !object.has(key) || object.get(key).isJsonNull();
	}

	static JsonObject object(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonObject() ? null : object.getAsJsonObject(key);
	}

	static JsonArray array(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonArray() ? new JsonArray() : object.getAsJsonArray(key);
	}

	static String string(JsonObject object, String key) {
		return isNull(object, key) || !object.get(key).isJsonPrimitive() ? null : object.get(key).getAsString();
	}

	private static boolean bool(JsonObject object, String key) {
		return !isNull(object, key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
	}
}

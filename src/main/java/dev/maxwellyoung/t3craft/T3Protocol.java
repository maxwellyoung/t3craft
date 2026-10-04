package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Adapts protocol 2's run projection to the panel's existing thread view. */
final class T3Protocol {
	static boolean v2(JsonObject thread) { return thread.has("pendingRuntimeRequest") || thread.has("activeRunId"); }

	static JsonObject shell(JsonObject source) {
		if (!v2(source)) return source;
		JsonObject out = source.deepCopy();
		JsonObject request = object(source, "pendingRuntimeRequest");
		String kind = request == null ? null : string(request, "kind");
		out.addProperty("hasPendingApprovals", request != null && !"user_input".equals(kind) && !"auth_refresh".equals(kind));
		out.addProperty("hasPendingUserInput", "user_input".equals(kind));
		String run = string(source, "latestRunId"), status = string(source, "status");
		if (run != null) {
			JsonObject turn = new JsonObject();
			turn.addProperty("turnId", run);
			turn.addProperty("state", legacyStatus(status));
			turn.add("requestedAt", source.get("latestRunRequestedAt"));
			turn.add("startedAt", source.has("activityRunStartedAt") ? source.get("activityRunStartedAt") : source.get("latestRunStartedAt"));
			out.add("latestTurn", turn);
		}
		return out;
	}

	static JsonObject projection(JsonObject p) {
		JsonObject out = p.getAsJsonObject("thread").deepCopy();
		JsonObject latest = null;
		for (var value : array(p, "runs")) {
			JsonObject run = value.getAsJsonObject();
			if (latest == null || run.get("ordinal").getAsInt() > latest.get("ordinal").getAsInt()) latest = run;
		}
		if (latest != null) {
			JsonObject turn = latest.deepCopy();
			turn.add("turnId", latest.get("id")); turn.addProperty("state", legacyStatus(string(latest, "status")));
			out.add("latestTurn", turn);
		}
		out.add("messages", array(p, "messages"));
		JsonArray activities = new JsonArray(); boolean approvals = false, inputs = false;
		for (var value : array(p, "runtimeRequests")) {
			JsonObject request = value.getAsJsonObject();
			if (!"pending".equals(string(request, "status"))) continue;
			JsonObject capability = object(request, "responseCapability");
			if (capability == null || "not_resumable".equals(string(capability, "type"))) continue;
			String id = string(request, "id");
			for (var itemValue : array(p, "turnItems")) {
				JsonObject item = itemValue.getAsJsonObject();
				if (!id.equals(string(item, "requestId")) || !request.get("nodeId").equals(item.get("nodeId"))) continue;
				String type = string(item, "type");
				if (!"approval_request".equals(type) && !"user_input_request".equals(type)) continue;
				JsonObject payload = new JsonObject(); payload.addProperty("requestId", id);
				if ("user_input_request".equals(type)) { payload.add("questions", array(item, "questions")); inputs = true; }
				else {
					payload.add("requestKind", item.get("requestKind"));
					payload.addProperty("detail", string(item, "prompt") == null ? string(item, "title") : string(item, "prompt"));
					approvals = true;
				}
				JsonObject activity = new JsonObject(); activity.addProperty("kind", "user_input_request".equals(type) ? "user-input.requested" : "approval.requested");
				activity.add("payload", payload); activity.add("createdAt", request.get("createdAt")); activities.add(activity);
				break;
			}
		}
		out.add("activities", activities); out.addProperty("hasPendingApprovals", approvals); out.addProperty("hasPendingUserInput", inputs);
		JsonArray checkpoints = new JsonArray();
		for (var value : array(p, "checkpoints")) {
			JsonObject checkpoint = value.getAsJsonObject();
			if (string(checkpoint, "appRunOrdinal") == null) continue; // Nested tool/subagent captures do not advance the thread checkpoint range.
			JsonObject normalized = checkpoint.deepCopy(); normalized.add("checkpointTurnCount", checkpoint.get("appRunOrdinal"));
			String replyId = null; java.time.Instant newest = java.time.Instant.MIN;
			java.time.Instant captured = java.time.Instant.parse(string(checkpoint, "capturedAt"));
			for (var messageValue : array(p, "messages")) {
				JsonObject message = messageValue.getAsJsonObject();
				String at = string(message, "createdAt");
				if ("assistant".equals(string(message, "role")) && !message.get("streaming").getAsBoolean()
					&& checkpoint.get("runId").equals(message.get("runId")) && at != null
					&& !java.time.Instant.parse(at).isAfter(captured) && !java.time.Instant.parse(at).isBefore(newest)) {
					newest = java.time.Instant.parse(at); replyId = string(message, "id");
				}
			}
			normalized.addProperty("assistantMessageId", replyId); checkpoints.add(normalized);
		}
		out.add("checkpoints", checkpoints);
		return out;
	}

	static String legacyStatus(String status) {
		return switch (status == null ? "idle" : status) {
			case "preparing", "queued", "starting", "running", "waiting" -> "running";
			case "failed" -> "error";
			default -> status;
		};
	}
	static JsonArray array(JsonObject object, String name) { return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray(); }
	static JsonObject object(JsonObject object, String name) { return object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : null; }
	static String string(JsonObject object, String name) { return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null; }
}

package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** All waiting requests, independent of how many villagers fit in the office. */
final class T3Decisions {
	record Entry(T3State.ThreadRow thread, String requestId, String kind, String detail, Instant requestedAt) {
		String key() { return thread.id() + ":" + requestId; }
	}

	static List<Entry> collect(List<T3State.ThreadRow> rows, Map<String, JsonObject> details) {
		List<Entry> entries = new ArrayList<>();
		for (var row : rows) {
			if (row.status() != T3State.Status.NEEDS_YOU) continue;
			JsonObject raw = details.get(row.id());
			int before = entries.size();
			if (raw != null) {
				var focus = T3State.focus(raw);
				for (var request : focus.approvals()) entries.add(new Entry(row, request.requestId(), request.kind(),
					request.detail() == null ? "No command detail supplied." : request.detail(), at(raw, request.requestId())));
				for (var input : focus.userInputs()) entries.add(new Entry(row, input.requestId(), "Question",
					input.questions().stream().map(q -> q.question() == null ? "Question" : q.question())
						.reduce((a, b) -> a + "\n\n" + b).orElse("Question"), at(raw, input.requestId())));
			}
			if (before == entries.size()) entries.add(new Entry(row, null, "Waiting",
				"Waiting for current request details…", null));
		}
		entries.sort(Comparator.comparing(Entry::requestedAt, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(e -> e.thread().id()).thenComparing(Entry::key));
		return List.copyOf(entries);
	}

	private static Instant at(JsonObject raw, String requestId) {
		if (!raw.has("activities")) return null;
		for (var value : raw.getAsJsonArray("activities")) {
			var activity = value.getAsJsonObject();
			if (!activity.has("payload") || !activity.get("payload").isJsonObject()) continue;
			var payload = activity.getAsJsonObject("payload");
			if (!payload.has("requestId") || !payload.get("requestId").isJsonPrimitive()
				|| !requestId.equals(payload.get("requestId").getAsString())) continue;
			if (!activity.has("createdAt") || activity.get("createdAt").isJsonNull()) continue;
			try { return Instant.parse(activity.get("createdAt").getAsString()); }
			catch (RuntimeException ignored) { return null; }
		}
		return null;
	}
}

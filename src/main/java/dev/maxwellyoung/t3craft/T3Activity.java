package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Display explicit backend fields; completion is not a claim that a test passed. */
final class T3Activity {
	record Entry(String kind, String status, String at, String summary, String detail) {}
	static List<Entry> entries(JsonObject thread) {
		List<Entry> entries = new ArrayList<>();
		if (thread.has("turnItems")) {
			for (var value : T3Protocol.array(thread, "turnItems")) {
				if (!value.isJsonObject()) continue;
				JsonObject item = value.getAsJsonObject();
				String kind = T3Protocol.string(item, "type");
				if (kind == null || "assistant_message".equals(kind) || "user_message".equals(kind)) continue;
				String summary = T3Protocol.string(item, "title");
				String detail = "command_execution".equals(kind) ? T3Protocol.string(item, "input")
					: "approval_request".equals(kind) ? T3Protocol.string(item, "prompt") : null;
				if ("command_execution".equals(kind) && T3Protocol.string(item, "exitCode") != null)
					detail = (detail == null ? "" : detail + "\n") + "Exit code: " + T3Protocol.string(item, "exitCode");
				entries.add(new Entry(kind, T3Protocol.string(item, "status"), T3Protocol.string(item, "startedAt"),
					summary == null ? kind.replace('_', ' ') : summary, detail));
			}
		} else {
			for (var value : T3Protocol.array(thread, "activities")) {
				if (!value.isJsonObject()) continue;
				JsonObject item = value.getAsJsonObject();
				String kind = T3Protocol.string(item, "kind");
				if (kind == null) continue;
				String summary = T3Protocol.string(item, "summary");
				entries.add(new Entry(kind, null, T3Protocol.string(item, "createdAt"), summary == null ? kind : summary, null));
			}
		}
		return List.copyOf(entries);
	}
}

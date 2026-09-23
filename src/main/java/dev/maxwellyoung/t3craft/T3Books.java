package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * When a watched thread finishes, hands the player a written book: the agent's final reply and the
 * files it changed. Uses /give, so it only runs where the player has command permission.
 */
final class T3Books {
	private static final int PAGE_CHARS = 230;
	private static final int MAX_PAGES = 12;
	// The /give feedback for our own book; the toast already said the thread is done.
	private static volatile String expectedFeedback;
	private static volatile long expectedUntil;

	private T3Books() {
	}

	static void deliver(T3State state, T3State.ThreadRow thread) {
		var player = Minecraft.getInstance().player;
		if (player == null || !player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return;
		T3Api api = state.apiFor(thread.id());
		if (api == null) return;
		state.run(() -> {
			JsonObject detail = api.thread(thread.id(), 2);
			String command = command(thread.title(), reply(detail), files(detail));
			Minecraft.getInstance().execute(() -> {
				if (Minecraft.getInstance().player == null) return;
				expectedFeedback = "Gave 1 [" + shortTitle(thread.title()) + "]";
				expectedUntil = System.currentTimeMillis() + 5_000;
				Minecraft.getInstance().player.connection.sendCommand(command);
			});
		}, e -> T3Log.LOGGER.warn("Could not write the book for {}", thread.title(), e));
	}

	static String command(String title, String reply, List<String> files) {
		List<String> pages = new ArrayList<>();
		String body = title + "\n\n" + plain(reply);
		for (int i = 0; i < body.length() && pages.size() < MAX_PAGES - (files.isEmpty() ? 0 : 1); i += PAGE_CHARS) {
			pages.add(body.substring(i, Math.min(body.length(), i + PAGE_CHARS)));
		}
		if (!files.isEmpty()) {
			StringBuilder page = new StringBuilder("Changed files:\n");
			for (String file : files.subList(0, Math.min(10, files.size()))) page.append("- ").append(file).append('\n');
			if (files.size() > 10) page.append("+").append(files.size() - 10).append(" more");
			pages.add(page.toString());
		}
		StringBuilder list = new StringBuilder();
		for (String page : pages) list.append(list.isEmpty() ? "" : ",").append('"').append(escape(page)).append('"');
		return "give @s minecraft:written_book[minecraft:written_book_content={title:\"" + escape(shortTitle(title))
			+ "\",author:\"T3 agent\",pages:[" + list + "]}]";
	}

	/** Book titles are capped at 32 characters. */
	private static String shortTitle(String title) {
		return title.length() > 32 ? title.substring(0, 31) + "…" : title;
	}

	/** True for the server's "Gave 1 [book]" reply to our own /give, which chat doesn't need. */
	static boolean isOwnFeedback(String message) {
		String expected = expectedFeedback;
		return expected != null && System.currentTimeMillis() < expectedUntil && message.startsWith(expected);
	}

	/** The agent's last reply in the thread. */
	static String reply(JsonObject thread) {
		JsonArray messages = thread.has("messages") ? thread.getAsJsonArray("messages") : new JsonArray();
		for (int i = messages.size() - 1; i >= 0; i--) {
			JsonObject message = messages.get(i).getAsJsonObject();
			if ("assistant".equals(message.get("role").getAsString())) return message.get("text").getAsString();
		}
		return "(no reply)";
	}

	/** Files changed in the thread's latest checkpoint. */
	static List<String> files(JsonObject thread) {
		List<String> files = new ArrayList<>();
		if (!thread.has("checkpoints") || !thread.get("checkpoints").isJsonArray()) return files;
		JsonArray checkpoints = thread.getAsJsonArray("checkpoints");
		if (checkpoints.isEmpty()) return files;
		JsonObject last = checkpoints.get(checkpoints.size() - 1).getAsJsonObject();
		if (!last.has("files")) return files;
		for (JsonElement file : last.getAsJsonArray("files")) files.add(file.getAsJsonObject().get("path").getAsString());
		return files;
	}

	/** Book pages are plain text: drop Markdown markup. */
	private static String plain(String markdown) {
		return markdown.replaceAll("(?m)^\\s*```.*$", "").replaceAll("(?m)^#{1,6}\\s+", "")
			.replace("**", "").replace("__", "").replace("`", "").trim();
	}

	/** For a double-quoted SNBT string. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}
}

package dev.maxwellyoung.t3craft;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.Duration;
import java.time.Instant;

/**
 * One line in the top-left corner while an agent is busy, waiting on you, or just finished.
 * Quiet otherwise, so it stays out of the way of normal play.
 */
final class T3Hud implements HudElement {
	private static final Duration SHOW_DONE_FOR = Duration.ofSeconds(20);

	private final T3CraftClient mod;

	T3Hud(T3CraftClient mod) {
		this.mod = mod;
	}

	static String label(T3State.Status status) {
		return switch (status) {
			case WORKING -> "Working";
			case NEEDS_YOU -> "Needs you";
			case DONE -> "Done";
			case ERROR -> "Failed";
			case IDLE -> "Idle";
		};
	}

	static int color(T3State.Status status) {
		return switch (status) {
			case WORKING -> 0xFF5EA8FF;
			case NEEDS_YOU -> 0xFFFFB02E;
			case DONE -> 0xFF4ADE80;
			case ERROR -> 0xFFF87171;
			case IDLE -> 0xFF9CA3AF;
		};
	}

	static String elapsed(Instant since) {
		if (since == null) return "";
		long seconds = Math.max(0, Duration.between(since, Instant.now()).getSeconds());
		return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!mod.paired() || minecraft.gui.screen() instanceof T3Screen) return;
		T3State.Snapshot snapshot = mod.state().snapshot();
		T3State.ThreadRow row = snapshot.focusedRow();
		if (row == null) return;

		boolean recentlyDone = row.status() == T3State.Status.DONE && completedWithin(row, SHOW_DONE_FOR);
		long othersWaiting = snapshot.threads().stream()
			.filter(other -> other != row && other.status() == T3State.Status.NEEDS_YOU).count();
		if (row.status() == T3State.Status.IDLE || (row.status() == T3State.Status.DONE && !recentlyDone)) {
			if (othersWaiting == 0) return;
		}

		Font font = minecraft.font;
		StringBuilder text = new StringBuilder(label(row.status()));
		if (row.status() == T3State.Status.WORKING) {
			text.append(' ').append(elapsed(row.workingSince()));
			if (row.step() != null) text.append(" · ").append(row.step());
		}
		String title = ellipsize(font, row.title(), 160);
		String suffix = othersWaiting > 0 ? "  +" + othersWaiting + " waiting" : "";
		String hint = row.status() == T3State.Status.NEEDS_YOU ? "  [`]" : "";

		int x = 4;
		int y = 4;
		int width = 14 + font.width(title) + 8 + font.width(text.toString()) + font.width(suffix + hint) + 6;
		graphics.fill(x, y, x + width, y + 14, 0xA0101014);
		int dotColor = color(row.status());
		// Pulse the dot while working so the corner reads as alive at a glance.
		if (row.status() == T3State.Status.WORKING && (System.currentTimeMillis() / 500) % 2 == 0) dotColor = 0xFF2F5F99;
		graphics.fill(x + 4, y + 5, x + 8, y + 9, dotColor);
		int cursor = x + 12;
		graphics.text(font, title, cursor, y + 3, 0xFFE5E7EB, false);
		cursor += font.width(title) + 8;
		graphics.text(font, text.toString(), cursor, y + 3, color(row.status()), false);
		cursor += font.width(text.toString());
		graphics.text(font, suffix + hint, cursor, y + 3, 0xFF9CA3AF, false);
	}

	private static boolean completedWithin(T3State.ThreadRow row, Duration window) {
		var turn = row.raw().get("latestTurn");
		if (turn == null || !turn.isJsonObject()) return false;
		var latestTurn = turn.getAsJsonObject();
		if (!latestTurn.has("completedAt") || latestTurn.get("completedAt").isJsonNull()) return false;
		return Instant.parse(latestTurn.get("completedAt").getAsString()).plus(window).isAfter(Instant.now());
	}

	static String ellipsize(Font font, String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		String ellipsis = "…";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) + font.width(ellipsis) > maxWidth) end--;
		return text.substring(0, end) + ellipsis;
	}
}

package dev.maxwellyoung.t3craft;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.Duration;
import java.time.Instant;

/**
 * One line in the top-left corner while an agent is working or waiting on you. Quiet
 * otherwise; completions are announced by the toast, not here.
 */
final class T3Hud implements HudElement {
	/** Widest a toast gets once T3CraftClient keeps its text short (160 + Minecraft's 30 padding). */
	static final int TOAST_COLUMN = 190;
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

		// Ongoing state only; finishing or failing is an event and the toast says it. If the focused
		// thread is settled, surface a thread that is waiting on you instead of showing "Done".
		T3State.ThreadRow shown = row.status() == T3State.Status.WORKING || row.status() == T3State.Status.NEEDS_YOU ? row
			// Only threads you're following; a stale approval elsewhere shouldn't own the corner.
			: snapshot.threads().stream().filter(t -> t.status() == T3State.Status.NEEDS_YOU && mod.state().isWatched(t.id()))
				.findFirst().orElse(null);
		long waitingAnywhere = snapshot.threads().stream().filter(t -> t.status() == T3State.Status.NEEDS_YOU).count();
		if (shown == null && waitingAnywhere > 0) {
			// Nothing followed is active, but threads elsewhere are waiting: just the count.
			Font font = minecraft.font;
			String text = waitingAnywhere + " waiting  [`]";
			graphics.fill(4, 4, 4 + 12 + font.width(text) + 6, 18, 0xA0101014);
			graphics.fill(8, 9, 12, 13, color(T3State.Status.NEEDS_YOU));
			graphics.text(font, text, 16, 7, 0xFF9CA3AF, false);
			return;
		}
		if (shown == null) return;
		long othersWaiting = snapshot.threads().stream()
			.filter(other -> other != shown && other.status() == T3State.Status.NEEDS_YOU).count();

		Font font = minecraft.font;
		String status = label(shown.status());
		String step = "";
		if (shown.status() == T3State.Status.WORKING) {
			status += " " + elapsed(shown.workingSince());
			if (shown.step() != null) step = " · " + shown.step();
		}
		String suffix = (othersWaiting > 0 ? "  +" + othersWaiting + " waiting" : "")
			+ (shown.status() == T3State.Status.NEEDS_YOU ? "  [`]" : "");

		// Stay clear of the toast column on the right; shorten the title first, then drop the step.
		int maxWidth = graphics.guiWidth() - TOAST_COLUMN - 12;
		int fixed = 12 + 8 + font.width(status) + font.width(suffix) + 6;
		int titleRoom = maxWidth - fixed - font.width(step);
		if (titleRoom < 60) {
			step = "";
			titleRoom = maxWidth - fixed;
		}
		String title = ellipsize(font, shown.title(), Math.max(30, Math.min(160, titleRoom)));
		String text = status + step;

		int x = 4;
		int y = 4;
		int width = 12 + font.width(title) + 8 + font.width(text) + font.width(suffix) + 6;
		graphics.fill(x, y, x + width, y + 14, 0xA0101014);
		int dotColor = color(shown.status());
		// Pulse the dot while working so the corner reads as alive at a glance.
		if (shown.status() == T3State.Status.WORKING && (System.currentTimeMillis() / 500) % 2 == 0) dotColor = 0xFF2F5F99;
		graphics.fill(x + 4, y + 5, x + 8, y + 9, dotColor);
		int cursor = x + 12;
		graphics.text(font, title, cursor, y + 3, 0xFFE5E7EB, false);
		cursor += font.width(title) + 8;
		graphics.text(font, text, cursor, y + 3, color(shown.status()), false);
		cursor += font.width(text);
		graphics.text(font, suffix, cursor, y + 3, 0xFF9CA3AF, false);
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

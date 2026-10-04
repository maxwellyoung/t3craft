package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Read-only review of T3's completed checkpoints; feedback returns to the thread composer. */
final class T3ReviewScreen extends Screen {
	private final T3CraftClient mod;
	private final String threadId;
	private T3Api.Review review;
	private List<T3Diff.File> files = List.of();
	private int selected = -1, scroll, fileScroll, horizontal, serial, patchWidth;
	private boolean loading, closed;
	private String error;
	private Button feedback, refresh;

	T3ReviewScreen(T3CraftClient mod, String threadId) { super(Component.literal("Checkpoint review")); this.mod = mod; this.threadId = threadId; }
	@Override protected void init() {
		closed = false;
		mod.state().setPanelOpen(true);
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> { mod.focus(threadId); mod.openPanel(); }).bounds(12, height - 30, 54, 20).build());
		refresh = addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> load()).bounds(72, height - 30, 66, 20).build());
		feedback = addRenderableWidget(Button.builder(Component.literal("Give feedback"), b -> {
			mod.focus(threadId);
			String draft = mod.draft(threadId);
			mod.saveDraft(threadId, draft.isBlank() ? "Feedback on checkpoint " + review.turnCount() + ":\n" : draft);
			mod.openPanel();
		}).bounds(width - 142, height - 30, 130, 20).build());
		if (review == null && !loading) load();
		tick();
	}
	@Override public void tick() {
		refresh.active = !loading && mod.state().online(threadId);
		feedback.active = review != null && mod.state().online(threadId);
	}
	void load() {
		int request = ++serial;
		loading = true; error = null;
		T3Api owner = mod.state().apiFor(threadId);
		mod.state().run(() -> {
			if (owner == null || owner != mod.state().apiFor(threadId) || !mod.state().online(threadId))
				throw new java.io.IOException("This thread's machine is offline. Reconnect before loading a review.");
			T3Api.Review result = owner.review(threadId);
			Minecraft.getInstance().execute(() -> {
				if (closed || request != serial || owner != mod.state().apiFor(threadId)) return;
				review = result; files = T3Diff.files(result.diff()); choose(-1); fileScroll = 0; loading = false;
			});
		}, e -> Minecraft.getInstance().execute(() -> {
			if (closed || request != serial) return;
			loading = false; error = e.getMessage();
		}));
	}
	T3Api.Review reviewForTest() { return review; }
	List<T3Diff.File> filesForTest() { return files; }
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }
	@Override public void removed() { closed = true; serial++; mod.state().setPanelOpen(false); }
	private int side() { return Math.max(130, Math.min(205, width / 3)); }
	private int visible() { return Math.max(1, (height - 94) / 20); }
	private void choose(int file) {
		selected = file; scroll = 0; horizontal = 0;
		patchWidth = file < 0 ? 0 : files.get(file).lines().stream().mapToInt(font::width).max().orElse(0);
	}
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= 12 && event.x() < side() && event.y() >= 58 && event.y() < height - 36) {
			int index = fileScroll + (int) ((event.y() - 58) / 20) - 1;
			if (index >= -1 && index < files.size()) choose(index);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
	@Override public boolean mouseScrolled(double x, double y, double h, double v) {
		if (x < side()) fileScroll = Math.max(0, Math.min(fileScroll - (int) v, Math.max(0, files.size() + 1 - visible())));
		else scroll = Math.max(0, scroll - (int) (v * 3));
		return true;
	}
	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_DOWN) { scroll += 3; return true; }
		if (event.key() == InputConstants.KEY_UP) { scroll = Math.max(0, scroll - 3); return true; }
		if (event.key() == InputConstants.KEY_RIGHT) { horizontal += 40; return true; }
		if (event.key() == InputConstants.KEY_LEFT) { horizontal = Math.max(0, horizontal - 40); return true; }
		if (event.key() == InputConstants.KEY_HOME) { scroll = horizontal = 0; return true; }
		if (event.key() == InputConstants.KEY_F5 && refresh.active) { load(); return true; }
		return super.keyPressed(event);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(8, 8, width - 8, height - 8, 0xF0222529);
		g.text(font, T3Hud.ellipsize(font, review == null ? "Checkpoint review" : review.title(), width - 32), 16, 17, 0xFFE5E7EB, false);
		String meta = review == null ? "Read-only · completed checkpoints" : "Checkpoint " + review.turnCount() + " · " + files.size() + " files · read-only";
		if (!mod.state().online(threadId)) meta += " · OFFLINE / last loaded";
		g.text(font, T3Hud.ellipsize(font, meta, width - 32), 16, 34, 0xFF9CA3AF, false);
		for (int i = fileScroll; i < Math.min(files.size() + 1, fileScroll + visible()); i++) {
			int y = 58 + (i - fileScroll) * 20;
			if (selected == i - 1) g.fill(12, y, side(), y + 19, 0xFF41464E);
			g.text(font, T3Hud.ellipsize(font, i == 0 ? "Agent reply" : files.get(i - 1).path(), side() - 24), 16, y + 5, 0xFFE5E7EB, false);
		}
		int x = side() + 16, available = Math.max(20, width - x - 16);
		g.enableScissor(x, 56, width - 12, height - 40);
		if (review == null || selected < 0 || loading || error != null) {
			String text = error != null ? "Could not load review:\n" + error : loading ? "Loading completed checkpoint…"
				: review.reply() + (files.isEmpty() ? "\n\nNo file changes in this checkpoint range." : "\n\nChoose a file to inspect its patch.\nArrows: scroll / pan · F5: refresh");
			var lines = font.split(Component.literal(text), available);
			scroll = Math.min(scroll, Math.max(0, lines.size() - Math.max(1, (height - 100) / (font.lineHeight + 2))));
			int y = 60;
			for (int i = scroll; i < lines.size() && y < height - 40; i++, y += font.lineHeight + 2) g.text(font, lines.get(i), x, y, error == null ? 0xFFE5E7EB : 0xFFFF9772, false);
		} else {
			var lines = files.get(selected).lines();
			scroll = Math.min(scroll, Math.max(0, lines.size() - Math.max(1, (height - 100) / font.lineHeight)));
			horizontal = Math.min(horizontal, Math.max(0, patchWidth - available));
			int y = 60;
			for (int i = scroll; i < lines.size() && y < height - 40; i++, y += font.lineHeight) {
				String line = lines.get(i);
				int color = line.startsWith("+") ? 0xFF9FE6B3 : line.startsWith("-") ? 0xFFFFA9A9 : line.startsWith("@@") ? 0xFF91C9FF : 0xFFE5E7EB;
				g.text(font, line, x - horizontal, y, color, false);
			}
		}
		g.disableScissor();
		super.extractRenderState(g, mx, my, delta);
	}
}

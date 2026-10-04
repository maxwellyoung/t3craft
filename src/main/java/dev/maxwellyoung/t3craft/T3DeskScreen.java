package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A decision queue available from the whiteboard or a key, even away from the office. */
final class T3DeskScreen extends Screen {
	private final T3CraftClient mod;
	private String selected;
	private int scroll, detailScroll;
	private Button open;
	private List<T3Decisions.Entry> rows = List.of();

	T3DeskScreen(T3CraftClient mod) { super(Component.literal("Decision desk")); this.mod = mod; }
	@Override protected void init() {
		mod.state().setPanelOpen(true);
		open = addRenderableWidget(Button.builder(Component.literal("Open request"), b -> openSelected())
			.bounds(width - 140, height - 30, 128, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> mod.openPanel()).bounds(12, height - 30, 60, 20).build());
		tick();
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }
	@Override public void removed() { mod.state().setPanelOpen(false); }
	@Override public void tick() {
		rows = mod.state().decisions();
		if (selected == null && !rows.isEmpty()) selected = rows.getFirst().key();
		var entry = selection();
		open.active = entry != null && entry.requestId() != null && mod.state().online(entry.thread().id());
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible())));
	}
	private int side() { return Math.max(150, Math.min(230, width / 3)); }
	private int visible() { return Math.max(1, (height - 90) / 38); }
	private T3Decisions.Entry selection() { return rows.stream().filter(e -> e.key().equals(selected)).findFirst().orElse(null); }
	void openSelected() { var e = selection(); if (e != null && open.active) mod.openRequest(e); }
	@Override public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_J || event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
		if (event.isConfirmation()) { openSelected(); return true; }
		return super.keyPressed(event);
	}
	@Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= 12 && event.x() < side() && event.y() >= 56 && event.y() < height - 34) {
			int i = scroll + (int) ((event.y() - 56) / 38);
			if (i < rows.size()) { selected = rows.get(i).key(); detailScroll = 0; tick(); if (doubleClick) openSelected(); }
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (x < side()) scroll = Math.max(0, Math.min(scroll - (int) vertical, Math.max(0, rows.size() - visible())));
		else detailScroll = Math.max(0, detailScroll - (int) (vertical * 3));
		return true;
	}
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(8, 8, width - 8, height - 8, 0xF0222529);
		g.text(font, "Decision desk · " + rows.size() + " waiting", 16, 17, 0xFFFFCC72, false);
		String offline = mod.state().offlineMachines().isEmpty() ? "All paired machines available"
			: "Offline / reconnecting: " + String.join(", ", mod.state().offlineMachines());
		g.text(font, T3Hud.ellipsize(font, offline, width - 32), 16, 35, mod.state().offlineMachines().isEmpty() ? 0xFF9CA3AF : 0xFFFF9772, false);
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) {
			var e = rows.get(i); int y = 56 + (i - scroll) * 38;
			if (e.key().equals(selected)) g.fill(12, y, side(), y + 36, 0xFF41464E);
			g.text(font, T3Hud.ellipsize(font, e.thread().title(), side() - 24), 16, y + 4, 0xFFE5E7EB, false);
			String machine = e.thread().environment() == null ? "T3" : e.thread().environment();
			g.text(font, T3Hud.ellipsize(font, machine + " · " + e.kind() + " · " + age(e.requestedAt()), side() - 24), 16, y + 17, 0xFFFFCC72, false);
			g.text(font, T3Hud.ellipsize(font, e.thread().projectTitle(), side() - 24), 16, y + 27, 0xFF9CA3AF, false);
		}
		var e = selection();
		g.enableScissor(side() + 12, 56, width - 16, height - 36);
		String detail = e == null ? rows.isEmpty() ? "No decisions waiting. Keep playing." : "That request changed. Select a current request."
			: e.kind() + " · " + e.thread().projectTitle() + "\n\n" + e.detail()
				+ (!mod.state().online(e.thread().id()) ? "\n\nLast-known request. This machine is offline; answering is disabled." : "");
		var lines = font.split(Component.literal(detail), Math.max(20, width - side() - 32));
		detailScroll = Math.min(detailScroll, Math.max(0, lines.size() - Math.max(1, (height - 94) / font.lineHeight)));
		int y = 60;
		for (int i = detailScroll; i < lines.size() && y < height - 36; i++, y += font.lineHeight + 2) g.text(font, lines.get(i), side() + 16, y, 0xFFE5E7EB, false);
		g.disableScissor();
		super.extractRenderState(g, mouseX, mouseY, delta);
	}
	private static String age(Instant at) {
		if (at == null) return "age unknown";
		long seconds = Math.max(0, Duration.between(at, Instant.now()).getSeconds());
		return seconds < 60 ? seconds + "s" : seconds < 3600 ? seconds / 60 + "m" : seconds / 3600 + "h";
	}
}

package dev.maxwellyoung.t3craft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Unavailable pins remain visible and removable without sending anything to T3. */
final class T3PinsScreen extends Screen {
	private final T3CraftClient mod;
	private int page;
	private final java.util.Map<OfficeRoster.Pin, Button> openButtons = new java.util.HashMap<>();
	private List<OfficeRoster.Pin> shown = List.of();
	T3PinsScreen(T3CraftClient mod) { super(Component.literal("Pinned desks")); this.mod = mod; }
	@Override protected void init() {
		clearWidgets(); openButtons.clear();
		shown = List.copyOf(mod.officePreferences().pins);
		int count = Math.max(1, (height - 95) / 32);
		page = Math.min(page, Math.max(0, (shown.size() - 1) / count));
		for (int i = page * count; i < Math.min(shown.size(), (page + 1) * count); i++) {
			OfficeRoster.Pin pin = shown.get(i); int y = 48 + (i - page * count) * 32;
			Button open = addRenderableWidget(Button.builder(Component.literal("Open"), b -> {
				T3State.ThreadRow row = row(pin);
				if (row != null) { mod.focus(row.id()); mod.openPanel(); }
			}).bounds(width - 126, y, 48, 18).build());
			open.active = row(pin) != null; openButtons.put(pin, open);
			addRenderableWidget(Button.builder(Component.literal("Unpin"), b -> { mod.unpin(pin); init(); })
				.bounds(width - 74, y, 54, 18).build());
		}
		Button previous = addRenderableWidget(Button.builder(Component.literal("Previous"), b -> { page--; init(); }).bounds(20, height - 32, 70, 18).build());
		previous.active = page > 0;
		Button next = addRenderableWidget(Button.builder(Component.literal("Next"), b -> { page++; init(); }).bounds(94, height - 32, 54, 18).build());
		next.active = (page + 1) * count < shown.size();
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> mod.openPanel()).bounds(width - 74, height - 32, 54, 18).build());
	}
	@Override public void tick() { openButtons.forEach((pin, button) -> button.active = row(pin) != null); }
	private T3State.ThreadRow row(OfficeRoster.Pin pin) {
		return mod.state().snapshot().threads().stream().filter(r -> pin.key().equals(OfficeRoster.key(r))).findFirst().orElse(null);
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public void onClose() { mod.openPanel(); }
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(10, 10, width - 10, height - 10, 0xF0101014);
		g.text(font, "Pinned desks · this world", 20, 20, 0xFFFFFFFF, false);
		g.text(font, "Up to eight per machine. Pins do not change T3 permissions.", 20, 32, 0xFF9CA3AF, false);
		int count = Math.max(1, (height - 95) / 32);
		if (shown.isEmpty()) g.text(font, "Open a thread and choose Pin desk to keep it in the office.", 20, 52, 0xFF9CA3AF, false);
		for (int i = page * count; i < Math.min(shown.size(), (page + 1) * count); i++) {
			OfficeRoster.Pin pin = shown.get(i); T3State.ThreadRow row = row(pin); int y = 48 + (i - page * count) * 32;
			g.text(font, T3Hud.ellipsize(font, row == null ? pin.title() : row.title(), width - 166), 20, y + 1, 0xFFE5E7EB, false);
			String status = row == null ? "Unavailable / archived" : mod.state().online(row.id()) ? T3Hud.label(row.status()) : "Offline";
			g.text(font, T3Hud.ellipsize(font, mod.machineLabel(pin.owner()) + " · " + status, width - 166), 20, y + 12, 0xFF9CA3AF, false);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}
}

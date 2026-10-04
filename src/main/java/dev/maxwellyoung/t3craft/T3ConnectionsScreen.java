package dev.maxwellyoung.t3craft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

/** Pairing credentials stay in a private form, outside Minecraft's chat and narration. */
final class T3ConnectionsScreen extends Screen {
	private final T3CraftClient mod;
	private boolean form, busy, confirming;
	private String replacing, removing, message = "";
	private EditBox name;
	private PrivateLink link;
	private Button pair, paste;
	private final java.util.Map<String, Button> retryButtons = new java.util.HashMap<>();
	private int page, messageScroll;
	private List<T3Config.Environment> shown = List.of();

	T3ConnectionsScreen(T3CraftClient mod) {
		super(Component.literal("T3 connections")); this.mod = mod; form = !mod.paired();
	}
	private int left() { return Math.max(20, (width - 500) / 2); }
	private int right() { return width - left(); }
	private int perPage() { return Math.max(1, (height - 134) / 52); }

	@Override protected void init() {
		clearWidgets(); retryButtons.clear(); mod.state().setPanelOpen(true);
		int x = left(), w = right() - x;
		if (confirming) {
			addRenderableWidget(Button.builder(Component.literal("Remove locally"), b -> {
				boolean saved = mod.removeEnvironment(removing); confirming = false; message = saved
					? "Removed locally. Revoke Minecraft in that machine's T3 Settings > Connections too."
					: "Could not save removal. Check Minecraft's config folder permissions; the machine remains paired."; init();
			}).bounds(x, height - 58, 140, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Keep machine"), b -> { confirming = false; init(); }).bounds(x + 146, height - 58, 120, 20).build());
		} else if (form) {
			name = new EditBox(font, x, 96, w, 20, name, Component.literal("Machine name (optional)"));
			name.setMaxLength(48); name.setHint(Component.literal("Optional machine name")); addRenderableWidget(name);
			link = new PrivateLink(x, 134, w, link); link.setResponder(value -> tick()); addRenderableWidget(link); setInitialFocus(link);
			paste = addRenderableWidget(Button.builder(Component.literal("Paste link"), b -> { link.setValue(minecraft.keyboardHandler.getClipboard().trim()); setFocused(link); })
				.bounds(x, 164, 104, 20).build());
			pair = addRenderableWidget(Button.builder(Component.literal("Pair machine"), b -> submit()).bounds(x + 110, 164, 120, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Back"), b -> back()).bounds(right() - 60, height - 30, 60, 20).build());
			tick();
		} else {
			shown = mod.environments(); int count = perPage();
			page = Math.min(page, Math.max(0, (shown.size() - 1) / count));
			for (int i = page * count; i < Math.min(shown.size(), (page + 1) * count); i++) {
				T3Config.Environment env = shown.get(i); String owner = T3Config.ownerKey(env.baseUrl); int y = 58 + (i - page * count) * 52;
				retryButtons.put(owner, addRenderableWidget(Button.builder(Component.literal("Retry"), b -> mod.state().retry(owner)).bounds(right() - 172, y, 48, 18).build()));
				addRenderableWidget(Button.builder(Component.literal("Re-pair"), b -> edit(owner, env.label)).bounds(right() - 120, y, 58, 18).build());
				addRenderableWidget(Button.builder(Component.literal("Remove"), b -> { removing = owner; confirming = true; message = ""; init(); }).bounds(right() - 58, y, 58, 18).build());
			}
			Button previous = addRenderableWidget(Button.builder(Component.literal("Previous"), b -> { page--; init(); }).bounds(x, height - 58, 70, 18).build());
			previous.active = page > 0;
			Button next = addRenderableWidget(Button.builder(Component.literal("Next"), b -> { page++; init(); }).bounds(x + 74, height - 58, 54, 18).build());
			next.active = (page + 1) * count < shown.size();
			addRenderableWidget(Button.builder(Component.literal("Add machine"), b -> edit(null, "")).bounds(x, height - 30, 112, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(right() - 60, height - 30, 60, 20).build());
		}
	}
	private void edit(String owner, String label) {
		form = true; replacing = owner; message = ""; name = null; link = null; init(); name.setValue(label);
	}
	private void submit() {
		if (busy || link.getValue().isBlank()) return;
		String value = link.getValue(); busy = true; message = "Pairing…"; tick();
		mod.pairEnvironment(value, name.getValue(), replacing, error -> {
			busy = false;
			if (error != null) { message = error; if (minecraft.gui.screen() == this) tick(); return; }
			link.setValue(""); name = null; link = null; form = false; replacing = null;
			message = "Pairing saved. Waiting for machine health below; Done opens your work.";
			if (minecraft.gui.screen() == this) init();
		});
	}
	private void back() {
		if (busy) { onClose(); return; }
		if (link != null) link.setValue(""); link = null; name = null;
		form = false; replacing = null; message = ""; init();
	}
	@Override public void tick() {
		for (var health : mod.state().machines()) {
			Button button = retryButtons.get(health.owner());
			if (button != null) button.setTooltip(Tooltip.create(Component.literal(health.error() == null ? "Retry this machine without interrupting the others." : health.error())));
		}
		if (form && pair != null) { pair.active = !busy && !link.getValue().isBlank(); paste.active = !busy; link.setEditable(!busy); name.setEditable(!busy); pair.setTooltip(message.isEmpty() ? null : Tooltip.create(Component.literal(message))); }
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean isInGameUi() { return true; }
	@Override public void removed() { if (link != null) link.setValue(""); mod.state().setPanelOpen(false); }
	@Override public void onClose() {
		if (confirming) { confirming = false; init(); }
		else if (form && !busy) back();
		else if (mod.paired()) mod.openPanel();
		else minecraft.gui.setScreen(null);
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (form) {
			int lines = font.split(Component.literal(message), right() - left()).size();
			messageScroll = Math.max(0, Math.min(messageScroll - (int) (vertical * 12), Math.max(0, lines * (font.lineHeight + 2) - (height - 218))));
		} else if (!confirming) { page = Math.max(0, Math.min(page - (int) vertical, Math.max(0, (shown.size() - 1) / perPage()))); init(); }
		return true;
	}
	boolean formForTest() { return form; }
	String messageForTest() { return message; }
	String privateNarrationForTest() { return link == null ? "" : link.createNarrationMessage().getString(); }
	String linkForTest() { return link == null ? "" : link.getValue(); }

	@Override public boolean keyPressed(KeyEvent e) {
		if (form && e.isConfirmation()) { submit(); return true; }
		return super.keyPressed(e);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int x = left(), w = right() - x;
		g.fill(x - 10, 10, right() + 10, height - 8, 0xF0222529);
		g.text(font, "T3 connections", x, 20, 0xFFE5E7EB, false);
		if (confirming) {
			wrapped(g, "Remove " + mod.machineLabel(removing) + " from this Minecraft client? Other machines stay connected. Its saved pins remain available if you pair this address again.", x, 58, w, 0xFFFFCC72);
			wrapped(g, "This removes local access only. It does not revoke the device in T3 Code or remove the operator's shared pairing.", x, 110, w, 0xFF9CA3AF);
		} else if (form) {
			wrapped(g, "1. In T3: Settings > Connections > pairing link.\n2. Copy the full link. Keep T3 running.", x, 40, w, 0xFFE5E7EB);
			g.text(font, replacing == null ? "Add a machine · name optional" : "Re-pair " + T3Hud.ellipsize(font, mod.machineLabel(replacing), w - 60), x, 82, 0xFF93C5FD, false);
			g.text(font, "Pairing link · hidden from screen and narration", x, 122, 0xFF9CA3AF, false);
			g.enableScissor(x, 188, right(), height - 30);
			int y = 188 - messageScroll;
			for (var line : font.split(Component.literal(message.isEmpty() ? "Use a reachable address for another machine. No chat command needed." : message), w)) {
				g.text(font, line, x, y, message.isEmpty() ? 0xFF9CA3AF : 0xFFFFCC72, false); y += font.lineHeight + 2;
			}
			g.disableScissor();
		} else {
			g.text(font, "Your personal machines · shared server pairing is operator-owned", x, 36, 0xFF9CA3AF, false);
			int count = perPage();
			if (shown.isEmpty()) g.text(font, "Add a machine to bring your existing T3 work into Minecraft.", x, 62, 0xFF9CA3AF, false);
			for (int i = page * count; i < Math.min(shown.size(), (page + 1) * count); i++) {
				var env = shown.get(i); String owner = T3Config.ownerKey(env.baseUrl); int y = 58 + (i - page * count) * 52;
				var health = mod.state().machines().stream().filter(m -> m.owner().equals(owner)).findFirst().orElse(null);
				String status = health == null ? "Connecting…" : health.error() != null ? recovery(health.error()) : health.online()
					? (health.live() ? "Live" : "Connected · polling") + " · protocol " + health.protocol() + " · " + health.threads() + " threads" : "Connecting…";
				g.text(font, T3Hud.ellipsize(font, mod.machineLabel(owner), w - 182), x, y + 2, 0xFFE5E7EB, false);
				g.text(font, T3Hud.ellipsize(font, owner, w), x, y + 22, 0xFF9CA3AF, false);
				g.text(font, T3Hud.ellipsize(font, status, w), x, y + 35, health != null && health.online() ? 0xFF86EFAC : 0xFFFFCC72, false);
			}
			wrapped(g, message, x + 138, height - 58, w - 138, 0xFFFFCC72);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}
	private static String recovery(String error) {
		if (error.contains("Settings > Connections")) return "Access expired/revoked · Re-pair with a fresh T3 link";
		if (error.contains("Update T3 Craft")) return "Unsupported protocol · Update T3 Craft";
		if (error.contains("Update T3 Code")) return "Unsupported protocol · Update T3 Code";
		return error;
	}

	private void wrapped(GuiGraphicsExtractor g, String value, int x, int y, int w, int color) {
		for (var line : font.split(Component.literal(value), Math.max(1, w))) { g.text(font, line, x, y, color, false); y += font.lineHeight + 2; }
	}

	/** Render a fixed placeholder and suppress copying/narrating the secret value. */
	private final class PrivateLink extends EditBox {
		PrivateLink(int x, int y, int w, PrivateLink previous) {
			super(T3ConnectionsScreen.this.font, x, y, w, 20, Component.literal("Private pairing link")); setMaxLength(8192);
			if (previous != null) setValue(previous.getValue());
		}
		@Override protected MutableComponent createNarrationMessage() { return Component.literal(getValue().isBlank() ? "Private pairing link, empty" : "Private pairing link entered, value hidden"); }
	@Override public boolean keyPressed(KeyEvent e) { if (e.isCopy() || e.isCut()) return true; return super.keyPressed(e); }
		@Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1, isFocused() ? 0xFF93C5FD : 0xFF9CA3AF);
			g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF101114);
			g.text(font, getValue().isBlank() ? "Paste the complete pairing link" : "Pairing link entered (hidden)", getX() + 4, getY() + 6, 0xFFE5E7EB, false);
		}
	}
}

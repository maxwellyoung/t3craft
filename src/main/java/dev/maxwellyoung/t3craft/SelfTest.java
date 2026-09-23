package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Dev-only end-to-end check, enabled with {@code -Dt3craft.selftest=<prompt>}. The prompt
 * {@code look} only opens the panel, captures it, and hands the game back (read-only).
 * Any other prompt: opens the panel,
 * sends the prompt, approves the first request, and captures Minecraft's own framebuffer at
 * each stage into run/screenshots. Never active in normal play.
 */
final class SelfTest {
	private enum Step { WAIT_WORLD, OPEN, SEND, ASK_WAIT, ASK_ANSWERED, LOOK, WATCH, VILLAGE, VILLAGE_LOOK, VILLAGE_CLICK, VILLAGE_CHECK, WAIT_WORKING, WAIT_APPROVAL_OR_DONE, WAIT_DONE, CLOSE, HUD, FINISH, DONE }

	private final T3CraftClient mod;
	private final String prompt;
	private Step step = Step.WAIT_WORLD;
	private int ticks;
	private int deadline = 20 * 600;
	private int targetEntity;
	private String targetThread;
	private String chosen;
	private boolean inWorld;

	private SelfTest(T3CraftClient mod, String prompt) {
		this.mod = mod;
		this.prompt = prompt;
	}

	static SelfTest fromSystemProperty(T3CraftClient mod) {
		String prompt = System.getProperty("t3craft.selftest");
		return prompt == null || prompt.isBlank() ? null : new SelfTest(mod, prompt);
	}

	void tick(Minecraft minecraft) {
		ticks++;
		if (step == Step.DONE) return;
		if (--deadline == 0) finish(minecraft, "FAIL timed out at " + step);
		T3State.Snapshot snapshot = mod.state().snapshot();
		T3State.ThreadRow row = snapshot.focusedRow();
		switch (step) {
			case WAIT_WORLD -> {
				if (minecraft.player != null && snapshot.connected() && !snapshot.threads().isEmpty() && ticks > 100) {
					advance(Step.OPEN, "world ready, " + snapshot.threads().size() + " threads, focused " + (row == null ? "none" : row.title()));
				}
			}
			case OPEN -> {
				String wanted = System.getProperty("t3craft.focus");
				if (wanted != null) {
					snapshot.threads().stream().filter(t -> t.title().toLowerCase().contains(wanted.toLowerCase()))
						.findFirst().ifPresent(t -> mod.focus(t.id()));
				}
				if ("watch".equals(prompt)) {
					// Local test world: daylight, creative, and let the agent under test build.
					minecraft.player.connection.sendCommand("time set day");
					minecraft.player.connection.sendCommand("gamemode creative");
					// Above the canopy, looking down ahead, so screenshots show what gets built.
					minecraft.player.connection.sendCommand("tp @s ~ ~14 ~ ~ 40");
					mod.setAgentCommands(true);
					advance(Step.WATCH, "watching; screenshot every 15s");
					return;
				}
				if ("village".equals(prompt)) {
					// Local test server only: daylight for readable screenshots.
					minecraft.player.connection.sendCommand("time set day");
					mod.placeVillage(2);
					advance(Step.VILLAGE, "village placed");
					return;
				}
				mod.openPanel();
				advance(Step.SEND, "panel opened");
			}
			case SEND -> {
				if (ticks < 40) return;
				shot(minecraft, "panel");
				if ("look".equals(prompt)) {
					advance(Step.CLOSE, "look only; nothing sent");
					step = Step.LOOK;
					return;
				}
				if (prompt.startsWith("ask:")) {
					if (minecraft.gui.screen() instanceof T3Screen screen) screen.typeAndSubmit(prompt.substring(4).strip(), true);
					advance(Step.ASK_WAIT, "asked; waiting for the agent's question");
					return;
				}
				boolean fresh = prompt.startsWith("new:");
				String text = fresh ? prompt.substring(4).strip() : prompt;
				if (fresh) {
					// Test world: daylight, creative, and a view of the ground ahead for the screenshots.
					minecraft.player.connection.sendCommand("time set day");
					minecraft.player.connection.sendCommand("gamemode creative");
					minecraft.player.setXRot(25);
				}
				if (minecraft.gui.screen() instanceof T3Screen screen) {
					if (fresh) screen.startNewThread();
					// A new-thread run behaves like a real Enter: send and go back to the world.
					screen.typeAndSubmit(text, !fresh);
				}
				inWorld = fresh;
				advance(Step.WAIT_WORKING, (fresh ? "new thread: " : "sent: ") + text);
			}
			case WAIT_WORKING -> {
				if (row != null && row.status() != T3State.Status.DONE) advance(Step.WAIT_APPROVAL_OR_DONE, "status " + row.status());
				else if (ticks > 200) advance(Step.WAIT_APPROVAL_OR_DONE, "never saw working (fast turn)");
			}
			case WAIT_APPROVAL_OR_DONE -> {
				if (ticks == 30) shot(minecraft, "working");
				if (row == null) return;
				if (row.status() == T3State.Status.NEEDS_YOU && snapshot.focus() != null && !snapshot.focus().approvals().isEmpty()) {
					if (ticks < 40) return;
					shot(minecraft, inWorld ? "needs-you" : "approval");
					var approval = snapshot.focus().approvals().getFirst();
					if (approval.detail() != null && approval.detail().startsWith("mcp__") && !approval.detail().startsWith("mcp__minecraft__")) {
						finish(minecraft, "FAIL unexpected approval: " + approval.detail());
						return;
					}
					mod.respond(approval, "accept");
					advance(Step.WAIT_DONE, "approved " + approval.detail());
				} else if (row.status() == T3State.Status.DONE && ticks > 40) {
					advance(Step.WAIT_DONE, "done without approval");
				}
			}
			case WAIT_DONE -> {
				if (row != null && row.status() == T3State.Status.NEEDS_YOU && snapshot.focus() != null
					&& !snapshot.focus().approvals().isEmpty() && ticks % 20 == 0) {
					var approval = snapshot.focus().approvals().getFirst();
					if (approval.detail() == null || !approval.detail().startsWith("mcp__minecraft__")) {
						finish(minecraft, "FAIL unexpected approval: " + approval.detail());
						return;
					}
					mod.respond(approval, "accept");
					T3CraftClient.LOGGER.info("SELFTEST approved {}", approval.detail());
				}
				if (inWorld && row != null && row.status() == T3State.Status.DONE && ticks > 20) {
					shot(minecraft, "done");
					advance(Step.FINISH, "done in world: " + lastMessage(snapshot));
					return;
				}
				if (row != null && row.status() == T3State.Status.DONE && ticks > 40) {
					shot(minecraft, "done-panel");
					advance(Step.CLOSE, "done: " + lastMessage(snapshot));
				}
			}
			case ASK_WAIT -> {
				var focus = snapshot.focus();
				if (focus == null || focus.userInputs().isEmpty() || ticks < 20) return;
				var question = focus.userInputs().getFirst().questions().getFirst();
				shot(minecraft, "question");
				if (question.options().size() < 2) {
					finish(minecraft, "FAIL question has fewer than 2 options");
					return;
				}
				chosen = question.options().get(1).label();
				// Real number-key path on the panel.
				if (minecraft.gui.screen() instanceof T3Screen screen) screen.pressOption(2);
				advance(Step.ASK_ANSWERED, "question \"" + question.question() + "\" → pressed 2 (" + chosen + ")");
			}
			case ASK_ANSWERED -> {
				if (row == null || row.status() != T3State.Status.DONE || ticks < 40) return;
				String reply = lastMessage(snapshot);
				shot(minecraft, "answered");
				boolean ok = reply.toLowerCase().contains(chosen.toLowerCase());
				T3CraftClient.LOGGER.info("SELFTEST reply: {}", reply);
				finish(minecraft, ok ? "PASS" : "FAIL reply does not mention " + chosen);
			}
			case LOOK -> {
				if (ticks == 1 && minecraft.gui.screen() instanceof T3Screen screen) screen.openPicker();
				if (ticks == 30) shot(minecraft, "picker");
				if (ticks < 50) return;
				minecraft.gui.setScreen(null);
				T3CraftClient.LOGGER.info("SELFTEST RESULT PASS (look); game left running");
				step = Step.DONE;
			}
			case DONE -> {
			}
			case WATCH -> {
				deadline = Integer.MAX_VALUE;
				if (ticks == 20) {
					minecraft.player.getAbilities().flying = true;
					minecraft.player.onUpdateAbilities();
				}
				if (ticks % 300 == 0) shot(minecraft, "watch");
			}
			case VILLAGE -> {
				if (ticks < 60) return;
				var villagers = mod.village().threadsByEntity();
				if (villagers.isEmpty()) {
					if (ticks > 400) finish(minecraft, "FAIL no villagers spawned");
					return;
				}
				shot(minecraft, "village");
				targetEntity = villagers.keySet().stream().max(Integer::compare).orElseThrow();
				targetThread = villagers.get(targetEntity);
				var villager = minecraft.level.getEntity(targetEntity);
				minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
					villager.position().add(0, 1.2, 0));
				advance(Step.VILLAGE_LOOK, villagers.size() + " villagers; looking at " + villager.getCustomName().getString());
			}
			case VILLAGE_LOOK -> {
				if (ticks < 20) return;
				shot(minecraft, "village-close");
				boolean aimed = minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity().getId() == targetEntity;
				// A real use-key press, handled by the same path as a mouse right-click.
				net.minecraft.client.KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
				advance(Step.VILLAGE_CLICK, "crosshair on villager: " + aimed);
			}
			case VILLAGE_CLICK -> {
				if (ticks < 20) return;
				boolean opened = minecraft.gui.screen() instanceof T3Screen;
				boolean focused = targetThread.equals(mod.state().focusedThreadId());
				advance(Step.VILLAGE_CHECK, "panel open: " + opened + ", focused clicked thread: " + focused);
				if (!opened || !focused) finish(minecraft, "FAIL right-click did not open the villager's thread");
			}
			case VILLAGE_CHECK -> {
				if (ticks == 30) shot(minecraft, "villager-panel");
				if (ticks < 50) return;
				minecraft.gui.setScreen(null);
				T3CraftClient.LOGGER.info("SELFTEST RESULT PASS (village); game left running");
				step = Step.DONE;
			}
			case CLOSE -> {
				if (ticks < 20) return;
				minecraft.gui.setScreen(null);
				advance(Step.HUD, "panel closed");
			}
			case HUD -> {
				// While the done toast is still up.
				if (ticks < 20) return;
				shot(minecraft, "hud");
				advance(Step.FINISH, "hud captured");
			}
			case FINISH -> {
				if (ticks <= 40) return;
				if (prompt.startsWith("new:")) {
					// Real-environment demo: leave the game running for the player.
					T3CraftClient.LOGGER.info("SELFTEST RESULT PASS; game left running");
					step = Step.DONE;
				} else {
					finish(minecraft, "PASS");
				}
			}
		}
	}

	private void advance(Step next, String note) {
		T3CraftClient.LOGGER.info("SELFTEST {} -> {}: {}", step, next, note);
		step = next;
		ticks = 0;
	}

	private static String lastMessage(T3State.Snapshot snapshot) {
		if (snapshot.focus() == null || snapshot.focus().messages().isEmpty()) return "(none)";
		return snapshot.focus().messages().getLast().text();
	}

	private static void shot(Minecraft minecraft, String label) {
		T3CraftClient.LOGGER.info("SELFTEST screenshot {}", label);
		Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
			message -> T3CraftClient.LOGGER.info("SELFTEST {} {}", label, message.getString()));
	}

	private void finish(Minecraft minecraft, String result) {
		T3CraftClient.LOGGER.info("SELFTEST RESULT {}", result);
		minecraft.stop();
	}
}

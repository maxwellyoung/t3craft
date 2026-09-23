package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * T3 Code inside Minecraft. Everything is client-side: prompts go straight to your T3
 * environment over HTTP and never touch server chat, so it works on any server.
 */
public final class T3CraftClient implements ClientModInitializer {
	public static final String MOD_ID = "t3craft";
	public static final Logger LOGGER = T3Log.LOGGER;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

	private static T3CraftClient instance;

	// Overridable so tests can pair with a sandbox without touching the real pairing.
	private final Path configPath = System.getProperty("t3craft.config") != null
		? Path.of(System.getProperty("t3craft.config"))
		: FabricLoader.getInstance().getConfigDir().resolve("t3craft.json");
	private T3Config config;
	private T3State state;
	private KeyMapping openKey;
	private boolean openPanelNextTick;
	private String lastPingThread;
	private long lastPingAt;
	private static final long PING_JUMP_WINDOW_MS = 60_000;
	private T3Village village;

	public static T3CraftClient get() {
		return instance;
	}

	public T3State state() {
		return state;
	}

	public boolean paired() {
		return config.paired();
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		config = T3Config.load(configPath);
		state = new T3State(event -> Minecraft.getInstance().execute(() -> announce(event)));
		connectAll();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t3craft.open", InputConstants.KEY_GRAVE, category));

		new T3Mcp(() -> config.agentCommands).start();
		village = new T3Village(this);
		ClientTickEvents.START_CLIENT_TICK.register(village::tick);
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register(
			(message, overlay) -> overlay || !T3Books.isOwnFeedback(message.getString()));
		SelfTest selfTest = SelfTest.fromSystemProperty(this);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (selfTest != null) selfTest.tick(client);
			while (openKey.consumeClick()) {
				openPanelNextTick = true;
				// Answering a ping: open the thread that pinged, not whatever was focused before.
				if (lastPingThread != null && System.currentTimeMillis() - lastPingAt < PING_JUMP_WINDOW_MS) focus(lastPingThread);
				lastPingThread = null;
			}
			if (openPanelNextTick && client.gui.screen() == null) {
				openPanelNextTick = false;
				openPanel();
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new T3Hud(this));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
	}

	public net.minecraft.core.BlockPos villageAnchor() {
		return config.village == null || config.village.length != 3 || !config.paired() ? null
			: new net.minecraft.core.BlockPos(config.village[0], config.village[1], config.village[2]);
	}

	T3Village village() {
		return village;
	}

	/** Puts the agent village a few blocks in front of the player. */
	public void placeVillage() {
		placeVillage(4);
	}

	void placeVillage(int distance) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		var ahead = player.blockPosition().relative(player.getDirection(), distance);
		config.village = new int[] {ahead.getX(), ahead.getY(), ahead.getZ()};
		config.save(configPath);
		village.clear();
	}

	private void connectAll() {
		List<T3State.Connection> connections = new java.util.ArrayList<>();
		for (T3Config.Environment env : config.environments) {
			connections.add(new T3State.Connection(new T3Api(env.baseUrl, env.accessToken), env.label));
		}
		state.connect(connections, config.threadId);
	}

	public void openPanel() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!config.paired()) {
			chat(Component.literal("Not paired yet. In T3: Settings → Connections → create a pairing link, then run ")
				.append(Component.literal("/t3 pair <link>").withStyle(ChatFormatting.AQUA)));
			return;
		}
		minecraft.gui.setScreen(new T3Screen(this));
	}

	/** Remembers the focused thread across sessions. */
	public void focus(String threadId) {
		state.focus(threadId);
		config.threadId = threadId;
		config.save(configPath);
	}

	/** Sends to the focused thread, or starts a new thread beside it. Runs off-thread; reports back in chat. */
	public void send(String text, boolean newThread) {
		send(text, newThread, null);
	}

	/** {@code model} overrides the thread's model for this turn (and the thread from then on); null keeps it. */
	public void send(String text, boolean newThread, com.google.gson.JsonObject model) {
		T3State.Snapshot snapshot = state.snapshot();
		// A new thread only borrows project and settings, so any recent thread can stand in.
		T3State.ThreadRow row = snapshot.focusedRow();
		if (row == null && newThread && !snapshot.threads().isEmpty()) row = snapshot.threads().getFirst();
		if (row == null) {
			chat(Component.literal("Pick a thread first (` then click one, or /t3 threads).").withStyle(ChatFormatting.RED));
			return;
		}
		T3State.ThreadRow target = row;
		// The target's own environment: a Klaus thread goes to Klaus.
		T3Api api = state.apiFor(target.id());
		state.run(() -> {
			if (newThread) {
				String id = api.startThread(target.raw(), text, model);
				state.focus(id);
				Minecraft.getInstance().execute(() -> focus(id));
			} else {
				api.sendPrompt(target.raw(), text, model);
				state.watch(target.id());
			}
		}, this::reportError);
	}

	public void respond(T3State.Approval approval, String decision) {
		String threadId = state.focusedThreadId();
		T3Api api = state.api();
		state.run(() -> api.respondToApproval(threadId, approval.requestId(), decision), this::reportError);
	}

	public void answer(T3State.UserInput input, com.google.gson.JsonObject answers) {
		String threadId = state.focusedThreadId();
		T3Api api = state.api();
		state.run(() -> api.answerQuestions(threadId, input.requestId(), answers), this::reportError);
	}

	/** Unsent composer text per thread ("new" for a new thread), so switching threads never loses a draft. */
	private final java.util.Map<String, String> drafts = new java.util.HashMap<>();

	String draft(String key) {
		return drafts.getOrDefault(key, "");
	}

	void saveDraft(String key, String text) {
		if (key == null) return;
		if (text == null || text.isBlank()) drafts.remove(key);
		else drafts.put(key, text);
	}

	public void interrupt() {
		String threadId = state.focusedThreadId();
		T3Api api = state.api();
		if (threadId != null) state.run(() -> api.interrupt(threadId), this::reportError);
	}

	private void reportError(Exception e) {
		LOGGER.warn("T3 request failed", e);
		Minecraft.getInstance().execute(() -> chat(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())
			.withStyle(ChatFormatting.RED)));
	}

	/** The point of the mod: tell the player when an agent finishes or needs them, wherever they are. */
	private void announce(T3State.Event event) {
		Minecraft minecraft = Minecraft.getInstance();
		String title = event.thread().title();
		Component heading;
		switch (event.status()) {
			case NEEDS_YOU -> {
				heading = Component.literal("T3 · Needs you").withStyle(ChatFormatting.GOLD);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.0f));
			}
			case ERROR -> {
				heading = Component.literal("T3 · Failed").withStyle(ChatFormatting.RED);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.8f));
			}
			default -> {
				heading = Component.literal("T3 · Done").withStyle(ChatFormatting.GREEN);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.2f));
			}
		}
		// One signal per event: the toast (and sound). The corner status only shows ongoing state,
		// and ` right after a ping opens the thread that pinged, which replaces a chat [open] link.
		lastPingThread = event.thread().id();
		lastPingAt = System.currentTimeMillis();
		if (config.books && event.status() == T3State.Status.DONE) T3Books.deliver(state, event.thread());
		// Already looking at this thread in the panel: the sound is enough, a toast would cover it.
		if (minecraft.gui.screen() instanceof T3Screen && event.thread().id().equals(state.focusedThreadId())) return;
		// Short text keeps the toast at its 190-px minimum, so it never grows over the corner status.
		Component toastTitle = heading.copy().append(Component.literal(" · ` to open").withStyle(ChatFormatting.GRAY));
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, toastTitle,
			Component.literal(T3Hud.ellipsize(minecraft.font, title, 160)));
	}

	private int setBooks(boolean on) {
		config.books = on;
		config.save(configPath);
		chat(Component.literal(on ? "Finished threads you started or opened here hand you a written report (needs command permission)."
			: "Report books off."));
		return 1;
	}

	private static void chat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			LOGGER.info(message.getString());
			return;
		}
		minecraft.gui.hud.getChat().addClientSystemMessage(
			Component.empty().append(Component.literal("[T3] ").withStyle(ChatFormatting.DARK_AQUA)).append(message));
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("t3")
			.executes(ctx -> {
				openPanelNextTick = true;
				return 1;
			})
			.then(literal("pair").then(argument("link", StringArgumentType.greedyString()).executes(this::pair)))
			.then(literal("unpair").executes(ctx -> {
				config.environments.clear();
				config.save(configPath);
				state.connect(List.of(), null);
				chat(Component.literal("Forgot all environments. Revoke \"Minecraft\" in each T3's Settings → Connections too."));
				return 1;
			}))
			.then(literal("threads").executes(ctx -> listThreads()))
			.then(literal("agent-build")
				.then(literal("on").executes(ctx -> setAgentCommands(true)))
				.then(literal("off").executes(ctx -> setAgentCommands(false))))
			.then(literal("books")
				.then(literal("on").executes(ctx -> setBooks(true)))
				.then(literal("off").executes(ctx -> setBooks(false))))
			.then(literal("village")
				.executes(ctx -> {
					placeVillage();
					chat(Component.literal("Your agents moved in here. Right-click one to open its thread; /t3 village off to hide them."));
					return 1;
				})
				.then(literal("off").executes(ctx -> {
					config.village = null;
					config.save(configPath);
					village.clear();
					chat(Component.literal("Village hidden."));
					return 1;
				})))
			.then(literal("use").then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
				List<T3State.ThreadRow> rows = state.snapshot().threads();
				int n = IntegerArgumentType.getInteger(ctx, "n");
				if (n > rows.size()) {
					chat(Component.literal("No thread #" + n + ". Try /t3 threads.").withStyle(ChatFormatting.RED));
					return 0;
				}
				focus(rows.get(n - 1).id());
				chat(Component.literal("Now talking to: " + rows.get(n - 1).title()));
				return 1;
			})))
			.then(literal("open").then(argument("id", StringArgumentType.word()).executes(ctx -> {
				focus(StringArgumentType.getString(ctx, "id"));
				openPanelNextTick = true;
				return 1;
			})))
			.then(literal("ask").then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, false))))
			.then(literal("new").then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, true))))
			.then(literal("approve").executes(ctx -> answer("accept")))
			.then(literal("deny").executes(ctx -> answer("decline")))
			.then(literal("stop").executes(ctx -> {
				interrupt();
				chat(Component.literal("Asked the agent to stop."));
				return 1;
			})));
	}

	int setAgentCommands(boolean allowed) {
		config.agentCommands = allowed;
		config.save(configPath);
		chat(Component.literal(allowed
			? "Agents on this machine can now run commands in your world (you'll see each one in chat). /t3 agent-build off to stop."
			: "Agents can no longer run commands in your world."));
		return 1;
	}

	private int pair(CommandContext<FabricClientCommandSource> ctx) {
		String link = StringArgumentType.getString(ctx, "link");
		chat(Component.literal("Pairing…"));
		Thread thread = new Thread(() -> {
			try {
				T3Api.Pairing pairing = T3Api.pair(link, "Minecraft");
				String label = T3Api.environmentLabel(pairing.baseUrl());
				Minecraft.getInstance().execute(() -> {
					// Pairing adds an environment; the others stay connected.
					config.upsert(new T3Config.Environment(label, pairing.baseUrl(), pairing.accessToken()));
					config.save(configPath);
					connectAll();
					chat(Component.literal("Paired with " + label + " (" + config.environments.size() + " environment"
						+ (config.environments.size() == 1 ? "" : "s") + "). Press ` to open the panel.").withStyle(ChatFormatting.GREEN));
				});
			} catch (Exception e) {
				reportError(e);
			}
		}, "t3craft-pair");
		thread.setDaemon(true);
		thread.start();
		return 1;
	}

	private int listThreads() {
		T3State.Snapshot snapshot = state.snapshot();
		if (!snapshot.connected()) {
			chat(Component.literal(snapshot.error() == null ? "Connecting…" : snapshot.error()).withStyle(ChatFormatting.RED));
			return 0;
		}
		int shown = Math.min(20, snapshot.threads().size());
		if (snapshot.threads().size() > shown) {
			chat(Component.literal("Most recent " + shown + " of " + snapshot.threads().size() + " threads; press ` for all of them.")
				.withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < shown; i++) {
			int n = i + 1;
			T3State.ThreadRow row = snapshot.threads().get(i);
			boolean focused = row.id().equals(state.focusedThreadId());
			chat(Component.literal((focused ? "▶ " : "  ") + n + ". ")
				.append(Component.literal(row.title()).withStyle(style -> style
					.withColor(focused ? ChatFormatting.WHITE : ChatFormatting.GRAY)
					.withClickEvent(new ClickEvent.RunCommand("/t3 use " + n))))
				.append(Component.literal("  " + row.projectTitle() + " · " + T3Hud.label(row.status()))
					.withStyle(ChatFormatting.DARK_GRAY)));
		}
		return 1;
	}

	private int prompt(CommandContext<FabricClientCommandSource> ctx, boolean newThread) {
		send(StringArgumentType.getString(ctx, "prompt"), newThread);
		return 1;
	}

	private int answer(String decision) {
		T3State.Focus focus = state.snapshot().focus();
		if (focus == null || focus.approvals().isEmpty()) {
			chat(Component.literal("Nothing waiting for approval on this thread."));
			return 0;
		}
		respond(focus.approvals().getFirst(), decision);
		return 1;
	}
}

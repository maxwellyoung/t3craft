package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated-server side: a shared village of the server's T3 threads that every player sees.
 * An operator pairs the server (/t3village pair <link>) and places it (/t3village here).
 */
public final class T3CraftServer implements DedicatedServerModInitializer {
	private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("t3craft.json");
	private T3Config config;
	private T3State state;
	private ServerVillage village;

	@Override
	public void onInitializeServer() {
		config = T3Config.load(configPath);
		state = new T3State(event -> {});
		connect();
		village = new ServerVillage(state, config);
		ServerTickEvents.END_SERVER_TICK.register(village::tick);
		// Village villagers are saved with their chunks; ones from an earlier run are removed as they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> village.onLoad(entity));

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
			Commands.literal("t3village")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("here").executes(ctx -> {
					var source = ctx.getSource();
					if (!config.paired()) {
						source.sendFailure(Component.literal("Pair the server first: /t3village pair <link>"));
						return 0;
					}
					var entity = source.getEntity();
					BlockPos anchor = entity != null ? entity.blockPosition().relative(entity.getDirection(), 4) : BlockPos.containing(source.getPosition());
					village.clear();
					config.village = new int[] {anchor.getX(), anchor.getY(), anchor.getZ()};
					config.save(configPath);
					source.sendSuccess(() -> Component.literal("Shared village placed; everyone here can see your threads."), true);
					return 1;
				}))
				.then(Commands.literal("off").executes(ctx -> {
					config.village = null;
					config.save(configPath);
					village.clear();
					ctx.getSource().sendSuccess(() -> Component.literal("Shared village removed."), true);
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					var snapshot = state.snapshot();
					String where = config.village == null ? "not placed" : "at " + village.anchor().toShortString();
					ctx.getSource().sendSuccess(() -> Component.literal("T3 " + (snapshot.connected() ? "connected" : "not connected")
						+ ", " + snapshot.threads().size() + " threads; village " + where), false);
					return 1;
				}))
				.then(Commands.literal("pair").then(Commands.argument("link", StringArgumentType.greedyString()).executes(ctx -> {
					String link = StringArgumentType.getString(ctx, "link");
					var source = ctx.getSource();
					Thread thread = new Thread(() -> {
						try {
							T3Api.Pairing pairing = T3Api.pair(link, "Minecraft server");
							String label = T3Api.environmentLabel(pairing.baseUrl());
							source.getServer().execute(() -> {
								config.upsert(new T3Config.Environment(label, pairing.baseUrl(), pairing.accessToken()));
								config.save(configPath);
								connect();
								source.sendSuccess(() -> Component.literal("Paired the server with " + label + ". Place the village with /t3village here."), true);
							});
						} catch (Exception e) {
							source.getServer().execute(() -> source.sendFailure(Component.literal("Pairing failed: " + e.getMessage())));
						}
					}, "t3craft-server-pair");
					thread.setDaemon(true);
					thread.start();
					return 1;
				})))));
	}

	private void connect() {
		List<T3State.Connection> connections = new ArrayList<>();
		for (T3Config.Environment env : config.environments) {
			connections.add(new T3State.Connection(new T3Api(env.baseUrl, env.accessToken), env.label));
		}
		state.connect(connections, null);
	}
}

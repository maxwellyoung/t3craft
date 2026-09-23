package dev.maxwellyoung.t3craft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared office: the same building and brain as the client village, but the agents are real
 * server villagers, so every player on the server sees them (even without the mod). Moving them on
 * the server lets each client interpolate and animate the walk on its own.
 */
final class ServerOffice {
	static final String TAG = "t3craft_office";

	private final T3State state;
	private final T3Config config;
	private final Runnable save;
	private final OfficeBrain brain = new OfficeBrain();
	private final Map<String, Villager> villagers = new HashMap<>();
	private final Map<Integer, String> boards = new HashMap<>();
	private Boolean night;
	private int ticks;

	ServerOffice(T3State state, T3Config config, Runnable save) {
		this.state = state;
		this.config = config;
		this.save = save;
	}

	List<String> floors() {
		return config.environments.stream().map(e -> e.label).toList();
	}

	BlockPos origin() {
		return config.serverOffice == null ? null : new BlockPos(config.serverOffice[0], config.serverOffice[1], config.serverOffice[2]);
	}

	/** Builds the office with its door a few blocks south of {@code feet} (same placement as the client). */
	void build(MinecraftServer server, BlockPos feet) {
		clear(server);
		BlockPos origin = new BlockPos(feet.getX() - 10, feet.getY(), feet.getZ() + 5);
		config.serverOffice = new int[] {origin.getX(), origin.getY(), origin.getZ()};
		save.run();
		run(server, origin, T3Office.blueprint(floors()));
	}

	void off(MinecraftServer server) {
		clear(server);
		config.serverOffice = null;
		save.run();
	}

	/** Removes this office's villagers (and any left over from a previous run). */
	void clear(MinecraftServer server) {
		for (Villager villager : villagers.values()) villager.discard();
		villagers.clear();
		brain.clear();
		boards.clear();
		night = null;
		server.getCommands().performPrefixedCommand(quiet(server), "kill @e[type=villager,tag=" + TAG + "]");
	}

	/** A tagged villager loaded from disk (an earlier run) that this office doesn't own: remove it. */
	void onLoad(net.minecraft.world.entity.Entity entity) {
		if (entity instanceof Villager villager && villager.entityTags().contains(TAG) && !villagers.containsValue(villager)) {
			villager.discard();
		}
	}

	void tick(MinecraftServer server) {
		BlockPos o = origin();
		if (o == null) return;
		ServerLevel level = server.overworld();
		if (!level.isLoaded(o)) return;
		ticks++;
		if (ticks % 10 == 0) sync(server, level, o);
		brain.tick();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Villager villager = villagers.get(agent.threadId);
			if (villager == null || villager.isRemoved()) continue;
			villager.setPos(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z);
			villager.setYRot(agent.yaw);
			villager.setYBodyRot(agent.yaw);
			villager.setYHeadRot(agent.yaw);
			boolean holds = !villager.getMainHandItem().isEmpty();
			if (agent.holding != holds) {
				villager.setItemSlot(EquipmentSlot.MAINHAND, agent.holding ? new ItemStack(Items.HONEY_BOTTLE) : ItemStack.EMPTY);
			}
			if (agent.settled() && agent.zone == OfficeBrain.Zone.WORK && Math.random() < 0.05) {
				level.playSound(null, villager.blockPosition(), SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.NEUTRAL,
					0.12F, 1.7F + (float) Math.random() * 0.4F);
			}
		}
	}

	private void sync(MinecraftServer server, ServerLevel level, BlockPos o) {
		List<String> floors = floors();
		for (OfficeBrain.Agent gone : brain.sync(state.snapshot().threads(), floors)) {
			Villager villager = villagers.remove(gone.threadId);
			if (villager != null) villager.discard();
		}
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Villager villager = villagers.get(agent.threadId);
			if (villager == null || villager.isRemoved()) {
				villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
				if (villager == null) continue;
				villager.setNoAi(true);
				villager.setSilent(true);
				villager.setNoGravity(true);
				villager.setPermanentlyInvulnerable(true);
				villager.setPersistenceRequired();
				villager.addTag(TAG);
				villager.setCustomNameVisible(true);
				villager.snapTo(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z, 0, 0);
				villager.setUUID(OfficeBrain.villagerUuid(agent.threadId));
				villagers.put(agent.threadId, villager);
				if (!level.addFreshEntity(villager)) {
					// A stale copy with this UUID is still in the world; it is discarded on load, so retry next sync.
					villagers.remove(agent.threadId);
					if (level.getEntity(villager.getUUID()) instanceof Villager stale) stale.discard();
					continue;
				}
			}
			T3State.ThreadRow row = state.snapshot().threads().stream().filter(r -> r.id().equals(agent.threadId)).findFirst().orElse(null);
			if (row != null) {
				villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillageLayout.outfit(row)).withLevel(5));
				villager.setCustomName(VillageLayout.label(row, state.waitingDetail(row.id())));
			}
		}
		for (int floor = 0; floor < Math.max(1, floors.size()); floor++) {
			List<T3Office.Note> notes = brain.notes(floor, state::waitingDetail);
			String key = notes.toString();
			if (!key.equals(boards.get(floor))) {
				boards.put(floor, key);
				run(server, o, T3Office.whiteboard(floor, notes));
			}
		}
		boolean dark = NycSun.isNight(Instant.now());
		if (night == null || night != dark) {
			night = dark;
			run(server, o, T3Office.lamps(floors.size(), dark));
		}
	}

	private static void run(MinecraftServer server, BlockPos o, List<String> commands) {
		CommandSourceStack source = quiet(server);
		for (String command : commands) {
			server.getCommands().performPrefixedCommand(source, T3Office.absolute(command, o.getX(), o.getY(), o.getZ()));
		}
	}

	private static CommandSourceStack quiet(MinecraftServer server) {
		return server.createCommandSourceStack().withSuppressedOutput();
	}
}

package dev.maxwellyoung.t3craft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared village: the server's paired threads as real villagers in the overworld, so every
 * player sees them, with or without the mod. Players with the mod (paired to the same T3) can
 * right-click one to open its thread; the entity UUID says which.
 */
final class ServerVillage {
	static final String TAG = "t3craft";

	private final T3State state;
	private final T3Config config;
	private final Map<String, Villager> villagers = new HashMap<>();
	private int ticks;

	ServerVillage(T3State state, T3Config config) {
		this.state = state;
		this.config = config;
	}

	BlockPos anchor() {
		return config.village == null ? null : new BlockPos(config.village[0], config.village[1], config.village[2]);
	}

	/** Removes the village's villagers (the ones loaded now; stale ones go as their chunks load). */
	void clear() {
		for (Villager villager : villagers.values()) villager.discard();
		villagers.clear();
	}

	/** A tagged villager loaded from disk (an earlier run) that this village doesn't own: remove it. */
	void onLoad(Entity entity) {
		if (entity instanceof Villager villager && villager.entityTags().contains(TAG) && !villagers.containsValue(villager)) {
			villager.discard();
		}
	}

	void tick(MinecraftServer server) {
		BlockPos anchor = anchor();
		if (anchor == null) {
			if (!villagers.isEmpty()) clear();
			return;
		}
		ServerLevel level = server.overworld();
		ticks++;
		if (ticks % 10 == 0) sync(level, anchor);
		if (ticks % 4 == 0) {
			for (Villager villager : villagers.values()) face(level, villager);
		}
		if (ticks % 8 == 0) {
			for (Map.Entry<String, Villager> entry : villagers.entrySet()) effects(level, entry.getValue(), row(entry.getKey()));
		}
	}

	private void sync(ServerLevel level, BlockPos anchor) {
		List<T3State.ThreadRow> threads = state.snapshot().threads();
		List<T3State.ThreadRow> rows = threads.subList(0, Math.min(VillageLayout.MAX_VILLAGERS, threads.size()));
		Set<String> keep = new HashSet<>();
		for (int slot = 0; slot < rows.size(); slot++) {
			T3State.ThreadRow row = rows.get(slot);
			BlockPos column = VillageLayout.column(anchor, slot);
			if (!level.isLoaded(column)) continue;
			keep.add(row.id());
			int y = VillageLayout.standY(level, column);
			Villager villager = villagers.get(row.id());
			if (villager == null || villager.isRemoved()) {
				villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
				if (villager == null) continue;
				villager.setNoAi(true);
				villager.setSilent(true);
				villager.setPermanentlyInvulnerable(true);
				villager.setPersistenceRequired();
				villager.addTag(TAG);
				villager.setCustomNameVisible(true);
				villager.setUUID(VillageLayout.villagerUuid(row.id()));
				villager.snapTo(column.getX() + 0.5, y, column.getZ() + 0.5, 0, 0);
				villagers.put(row.id(), villager);
				if (!level.addFreshEntity(villager)) {
					// A stale copy with this UUID is still loaded; drop it and spawn on the next sync.
					villagers.remove(row.id());
					if (level.getEntity(villager.getUUID()) instanceof Villager stale) stale.discard();
					continue;
				}
			} else if (villager.getX() != column.getX() + 0.5 || villager.getY() != y || villager.getZ() != column.getZ() + 0.5) {
				villager.snapTo(column.getX() + 0.5, y, column.getZ() + 0.5, villager.getYRot(), 0);
			}
			villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillageLayout.outfit(row)).withLevel(5));
			villager.setCustomName(VillageLayout.label(row, state.waitingDetail(row.id())));
		}
		villagers.entrySet().removeIf(entry -> {
			if (keep.contains(entry.getKey())) return false;
			entry.getValue().discard();
			return true;
		});
	}

	/** Turn toward the nearest player, like the client village does. */
	private static void face(ServerLevel level, Villager villager) {
		Player player = level.getNearestPlayer(villager, 24);
		if (player == null) return;
		float yaw = (float) (Math.toDegrees(Math.atan2(player.getZ() - villager.getZ(), player.getX() - villager.getX())) - 90);
		villager.setYRot(yaw);
		villager.setYBodyRot(yaw);
		villager.setYHeadRot(yaw);
	}

	private static void effects(ServerLevel level, Villager villager, T3State.ThreadRow row) {
		if (row == null) return;
		ParticleOptions particle = switch (row.status()) {
			case WORKING -> ParticleTypes.ENCHANT;
			case NEEDS_YOU -> ParticleTypes.NOTE;
			case ERROR -> ParticleTypes.ANGRY_VILLAGER;
			case DONE -> VillageLayout.recentlyDone(row) ? ParticleTypes.HAPPY_VILLAGER : null;
			case IDLE -> null;
		};
		if (particle == null) return;
		level.sendParticles(particle, villager.getX(), villager.getY() + 2.3, villager.getZ(),
			row.status() == T3State.Status.WORKING ? 3 : 1, 0.3, 0.1, 0.3, 0.05);
	}

	private T3State.ThreadRow row(String threadId) {
		for (T3State.ThreadRow row : state.snapshot().threads()) if (row.id().equals(threadId)) return row;
		return null;
	}
}

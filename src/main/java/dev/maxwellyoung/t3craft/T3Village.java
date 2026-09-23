package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your threads as villagers standing around a spot you choose. Name and particles show
 * status; right-click one to open its thread. They exist only on this client (negative
 * entity ids, never sent to the server), so this works on any server. Right-clicks on a
 * server's shared village (real villagers, see {@link ServerVillage}) open threads too.
 */
final class T3Village {
	private static final int FIRST_ID = -7_300_000;

	private final T3CraftClient mod;
	private final Map<String, Villager> villagers = new HashMap<>();
	private final Map<Integer, String> threadByEntity = new HashMap<>();
	private ClientLevel level;
	private int ticks;
	private int nextId = FIRST_ID;

	T3Village(T3CraftClient mod) {
		this.mod = mod;
	}

	/** Runs at the start of the client tick so a right-click on a villager is claimed before vanilla sees it. */
	void tick(Minecraft minecraft) {
		BlockPos anchor = mod.villageAnchor();
		if (minecraft.level != level || anchor == null) clear();
		level = minecraft.level;
		if (level == null || anchor == null || minecraft.player == null) return;
		ticks++;

		if (minecraft.gui.screen() == null && minecraft.hitResult instanceof EntityHitResult hit) {
			String threadId = threadByEntity.get(hit.getEntity().getId());
			// A shared village's villagers are real entities whose UUID encodes the thread.
			if (threadId == null && hit.getEntity() instanceof Villager other) threadId = threadForUuid(other.getUUID());
			if (threadId != null && minecraft.options.keyUse.consumeClick()) {
				while (minecraft.options.keyUse.consumeClick()) {
					// Drain queued clicks so vanilla never sends an interact for an entity the server doesn't have.
				}
				mod.focus(threadId);
				mod.openPanel();
				return;
			}
		}

		T3State.Snapshot snapshot = mod.state().snapshot();
		if (ticks % 10 == 0) sync(snapshot, anchor);
		for (Map.Entry<String, Villager> entry : villagers.entrySet()) {
			Villager villager = entry.getValue();
			face(villager, minecraft);
			if (ticks % 8 == 0) effects(villager, row(snapshot, entry.getKey()));
		}
	}

	/** The thread a shared-village villager stands for, if it is one of ours. */
	private String threadForUuid(java.util.UUID uuid) {
		for (T3State.ThreadRow row : mod.state().snapshot().threads()) {
			if (VillageLayout.villagerUuid(row.id()).equals(uuid)) return row.id();
		}
		return null;
	}

	/** Entity id → thread, for the self-test. */
	Map<Integer, String> threadsByEntity() {
		return Map.copyOf(threadByEntity);
	}

	void clear() {
		if (level != null) {
			for (Villager villager : villagers.values()) level.removeEntity(villager.getId(), Entity.RemovalReason.DISCARDED);
		}
		villagers.clear();
		threadByEntity.clear();
	}

	private void sync(T3State.Snapshot snapshot, BlockPos anchor) {
		List<T3State.ThreadRow> rows = snapshot.threads().subList(0, Math.min(VillageLayout.MAX_VILLAGERS, snapshot.threads().size()));
		Set<String> keep = new HashSet<>();
		for (int slot = 0; slot < rows.size(); slot++) {
			T3State.ThreadRow row = rows.get(slot);
			keep.add(row.id());
			BlockPos column = VillageLayout.column(anchor, slot);
			if (!level.isLoaded(column)) continue;
			int y = VillageLayout.standY(level, column);

			Villager villager = villagers.get(row.id());
			if (villager == null) {
				villager = new Villager(EntityTypes.VILLAGER, level);
				villager.setId(nextId--);
				villager.setNoAi(true);
				villager.setSilent(true);
				villager.setCustomNameVisible(true);
				level.addEntity(villager);
				villagers.put(row.id(), villager);
				threadByEntity.put(villager.getId(), row.id());
			}
			villager.snapTo(column.getX() + 0.5, y, column.getZ() + 0.5, villager.getYRot(), 0);
			villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillageLayout.outfit(row)).withLevel(5));
			villager.setCustomName(VillageLayout.label(row, mod.state().waitingDetail(row.id())));
		}
		villagers.entrySet().removeIf(entry -> {
			if (keep.contains(entry.getKey())) return false;
			threadByEntity.remove(entry.getValue().getId());
			level.removeEntity(entry.getValue().getId(), Entity.RemovalReason.DISCARDED);
			return true;
		});
	}

	private static void face(Villager villager, Minecraft minecraft) {
		double dx = minecraft.player.getX() - villager.getX();
		double dz = minecraft.player.getZ() - villager.getZ();
		if (dx * dx + dz * dz > 24 * 24) return;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		villager.setYRot(yaw);
		villager.setYBodyRot(yaw);
		villager.setYHeadRot(yaw);
	}

	private void effects(Villager villager, T3State.ThreadRow row) {
		if (row == null) return;
		ParticleOptions particle = switch (row.status()) {
			case WORKING -> ParticleTypes.ENCHANT;
			case NEEDS_YOU -> ParticleTypes.NOTE;
			case ERROR -> ParticleTypes.ANGRY_VILLAGER;
			case DONE -> VillageLayout.recentlyDone(row) ? ParticleTypes.HAPPY_VILLAGER : null;
			case IDLE -> null;
		};
		if (row.status() == T3State.Status.NEEDS_YOU) villager.setUnhappyCounter(20);
		if (particle == null) return;
		for (int i = 0; i < (row.status() == T3State.Status.WORKING ? 3 : 1); i++) {
			level.addParticle(particle, villager.getX() + (Math.random() - 0.5) * 0.8, villager.getY() + 2.2 + Math.random() * 0.3,
				villager.getZ() + (Math.random() - 0.5) * 0.8, 0, 0.05, 0);
		}
	}

	private static T3State.ThreadRow row(T3State.Snapshot snapshot, String threadId) {
		for (T3State.ThreadRow row : snapshot.threads()) if (row.id().equals(threadId)) return row;
		return null;
	}
}

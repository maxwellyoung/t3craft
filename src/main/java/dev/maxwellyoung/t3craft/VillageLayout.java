package dev.maxwellyoung.t3craft;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.BlockGetter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the client village and the shared server village have in common: where each thread
 * stands, what its name tag says, and how it looks. No client classes, so a server can load it.
 */
final class VillageLayout {
	static final int MAX_VILLAGERS = 8;
	private static final int PER_ROW = 4;
	private static final int SPACING = 3;
	private static final int DETAIL_CHARS = 36;
	// Project → outfit, so one project's threads look alike.
	private static final List<ResourceKey<VillagerProfession>> OUTFITS = List.of(
		VillagerProfession.LIBRARIAN, VillagerProfession.CARTOGRAPHER, VillagerProfession.CLERIC,
		VillagerProfession.TOOLSMITH, VillagerProfession.ARMORER, VillagerProfession.FARMER,
		VillagerProfession.FLETCHER, VillagerProfession.MASON, VillagerProfession.SHEPHERD,
		VillagerProfession.WEAPONSMITH, VillagerProfession.BUTCHER, VillagerProfession.LEATHERWORKER);

	private VillageLayout() {
	}

	/** The column a thread in {@code slot} stands on: rows of four, three blocks apart. */
	static BlockPos column(BlockPos anchor, int slot) {
		return new BlockPos(anchor.getX() + (slot % PER_ROW) * SPACING, anchor.getY(), anchor.getZ() + (slot / PER_ROW) * SPACING);
	}

	/** Nearest spot within a few blocks of the anchor's height with ground below and room to stand. Heightmaps put them on treetops; leaves don't count as ground. */
	static int standY(BlockGetter level, BlockPos column) {
		for (int offset = 0; offset <= 4; offset++) {
			for (int dy : new int[] {offset, -offset}) {
				BlockPos feet = column.above(dy);
				var ground = level.getBlockState(feet.below());
				if (ground.isSolid() && !ground.is(BlockTags.LEAVES)
					&& level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) {
					return feet.getY();
				}
			}
		}
		return column.getY();
	}

	static ResourceKey<VillagerProfession> outfit(T3State.ThreadRow row) {
		return OUTFITS.get(Math.floorMod(row.projectTitle().hashCode(), OUTFITS.size()));
	}

	/** Title and status; a waiting thread also says what it's asking, once known. */
	static Component label(T3State.ThreadRow row, String waitingDetail) {
		String title = row.title().length() > 32 ? row.title().substring(0, 31) + "…" : row.title();
		String status = label(row.status());
		if (row.status() == T3State.Status.WORKING) status += " " + elapsed(row.workingSince());
		if (row.status() == T3State.Status.NEEDS_YOU && waitingDetail != null && !waitingDetail.isBlank()) {
			String detail = waitingDetail.strip().replaceAll("\\s+", " ");
			status += ": " + (detail.length() > DETAIL_CHARS ? detail.substring(0, DETAIL_CHARS - 1) + "…" : detail);
		}
		return Component.literal(title).withColor(0xFFFFFFFF)
			.append(Component.literal("  " + status).withColor(color(row.status())));
	}

	/** A shared-village villager's entity UUID, derived from its thread so any client can tell whose it is. */
	static UUID villagerUuid(String threadId) {
		return UUID.nameUUIDFromBytes(("t3craft:" + threadId).getBytes(StandardCharsets.UTF_8));
	}

	static String label(T3State.Status status) {
		return switch (status) {
			case WORKING -> "Working";
			case NEEDS_YOU -> "Needs you";
			case DONE -> "Done";
			case ERROR -> "Failed";
			case IDLE -> "Idle";
		};
	}

	static int color(T3State.Status status) {
		return switch (status) {
			case WORKING -> 0xFF5EA8FF;
			case NEEDS_YOU -> 0xFFFFB02E;
			case DONE -> 0xFF4ADE80;
			case ERROR -> 0xFFF87171;
			case IDLE -> 0xFF9CA3AF;
		};
	}

	static String elapsed(Instant since) {
		if (since == null) return "";
		long seconds = Math.max(0, Duration.between(since, Instant.now()).getSeconds());
		return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	/** Recently finished threads sparkle for two minutes. */
	static boolean recentlyDone(T3State.ThreadRow row) {
		var turn = row.raw().get("latestTurn");
		if (turn == null || !turn.isJsonObject() || !turn.getAsJsonObject().has("completedAt")
			|| turn.getAsJsonObject().get("completedAt").isJsonNull()) return false;
		return Instant.parse(turn.getAsJsonObject().get("completedAt").getAsString()).plus(Duration.ofMinutes(2)).isAfter(Instant.now());
	}
}

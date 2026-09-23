package dev.maxwellyoung.t3craft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Where every agent is in the office and where it's going, with no Minecraft entities involved.
 * The client village and the shared server office each mirror these agents onto their own
 * villagers. Positions are relative to the office origin; each machine has its own floor.
 */
final class OfficeBrain {
	static final int PER_FLOOR = 8;
	private static final double WALK_SPEED = 0.09;
	private static final int FRIDGE_PAUSE = 50;

	enum Zone { WORK, NEEDS_YOU, LOUNGE }

	/** One agent (thread) and its villager's state. */
	static final class Agent {
		final String threadId;
		String title;
		T3State.Status status;
		int floor;
		double x, z, sink;
		float yaw;
		boolean walking;
		/** Holding a drink from the fridge. */
		boolean holding;
		T3Office.Spot goal;
		Zone zone;
		final ArrayDeque<double[]> path = new ArrayDeque<>();
		int pause;

		Agent(String threadId) {
			this.threadId = threadId;
		}

		/** Standing (or sitting) at its spot, not walking or pausing. */
		boolean settled() {
			return path.isEmpty() && pause == 0 && goal != null;
		}

		double y() {
			return floor * T3Office.FLOOR_HEIGHT + sink;
		}
	}

	private final Map<String, Agent> agents = new LinkedHashMap<>();

	Map<String, Agent> agents() {
		return agents;
	}

	/**
	 * Places the most recent threads of each machine on that machine's floor. Returns agents that
	 * left (their threads dropped off the list) so the caller can remove their villagers.
	 */
	List<Agent> sync(List<T3State.ThreadRow> rows, List<String> floors) {
		Set<String> keep = new HashSet<>();
		int[][] counters = new int[Math.max(1, floors.size())][3];
		int[] perFloor = new int[Math.max(1, floors.size())];
		for (T3State.ThreadRow row : rows) {
			int floor = row.environment() == null ? 0 : Math.max(0, floors.indexOf(row.environment()));
			if (floor >= perFloor.length || perFloor[floor] >= PER_FLOOR) continue;
			perFloor[floor]++;
			keep.add(row.id());
			Zone zone = switch (row.status()) {
				case WORKING -> Zone.WORK;
				case NEEDS_YOU -> Zone.NEEDS_YOU;
				default -> Zone.LOUNGE;
			};
			T3Office.Spot[] spots = switch (zone) {
				case WORK -> T3Office.WORK;
				case NEEDS_YOU -> T3Office.NEEDS_YOU;
				case LOUNGE -> T3Office.LOUNGE;
			};
			T3Office.Spot spot = spots[counters[floor][zone.ordinal()]++ % spots.length];
			Agent agent = agents.get(row.id());
			if (agent == null) {
				agent = new Agent(row.id());
				agent.floor = floor;
				// Ground-floor arrivals come in the door; upper floors, up the ladder.
				agent.x = floor == 0 ? T3Office.DOOR[0] + 0.5 : T3Office.LADDER[0] - 0.5;
				agent.z = floor == 0 ? T3Office.DOOR[1] + 0.5 : T3Office.LADDER[1] + 0.5;
				agents.put(row.id(), agent);
			}
			agent.title = row.title();
			agent.status = row.status();
			agent.floor = floor;
			if (!spot.equals(agent.goal)) {
				Zone previous = agent.zone;
				agent.goal = spot;
				agent.zone = zone;
				agent.sink = 0; // stand up before walking anywhere
				agent.path.clear();
				agent.pause = 0;
				int fromX = (int) Math.floor(agent.x), fromZ = (int) Math.floor(agent.z);
				if (previous == Zone.WORK && zone == Zone.LOUNGE) {
					// Finished: grab a drink from the fridge on the way to the sofa.
					T3Office.Spot fridge = T3Office.FRIDGE;
					agent.path.addAll(T3Office.path(fromX, fromZ, fridge.x(), fridge.z()));
					agent.path.add(new double[] {fridge.x() + 0.5, fridge.z() + 0.5, FRIDGE_PAUSE});
					agent.path.addAll(T3Office.path(fridge.x(), fridge.z(), spot.x(), spot.z()));
				} else {
					agent.path.addAll(T3Office.path(fromX, fromZ, spot.x(), spot.z()));
				}
				if (zone != Zone.LOUNGE) agent.holding = false;
			}
		}
		List<Agent> gone = new ArrayList<>();
		agents.values().removeIf(agent -> {
			if (keep.contains(agent.threadId)) return false;
			gone.add(agent);
			return true;
		});
		return gone;
	}

	/** One game tick: walk, pause at the fridge, or settle into the spot. */
	void tick() {
		for (Agent agent : agents.values()) {
			if (agent.pause > 0) {
				agent.pause--;
				agent.walking = false;
				agent.yaw = T3Office.FRIDGE.yaw();
				if (agent.pause == 0) agent.holding = true;
				continue;
			}
			double[] next = agent.path.peek();
			if (next != null) {
				double dx = next[0] - agent.x, dz = next[1] - agent.z;
				double distance = Math.sqrt(dx * dx + dz * dz);
				if (distance <= WALK_SPEED) {
					agent.x = next[0];
					agent.z = next[1];
					agent.path.poll();
					if (next.length > 2) agent.pause = (int) next[2];
				} else {
					agent.x += dx / distance * WALK_SPEED;
					agent.z += dz / distance * WALK_SPEED;
				}
				agent.yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
				agent.walking = true;
				continue;
			}
			agent.walking = false;
			if (agent.goal != null) {
				agent.sink = agent.goal.sink();
				if (!Float.isNaN(agent.goal.yaw())) agent.yaw = agent.goal.yaw();
			}
		}
	}

	/** What each floor's whiteboard should say: its waiting threads, oldest first. */
	List<T3Office.Note> notes(int floor, Function<String, String> detail) {
		List<T3Office.Note> notes = new ArrayList<>();
		for (Agent agent : agents.values()) {
			if (agent.floor == floor && agent.zone == Zone.NEEDS_YOU) notes.add(new T3Office.Note(agent.title, detail.apply(agent.threadId)));
		}
		return notes;
	}

	void clear() {
		agents.clear();
	}

	/** A shared-office villager's entity UUID: derived from its thread, distinct from the shared village's. */
	static java.util.UUID villagerUuid(String threadId) {
		return java.util.UUID.nameUUIDFromBytes(("t3craft-office:" + threadId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}

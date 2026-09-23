package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Client side of the office: sends its build and update commands a few per tick, quietly. */
final class OfficeQueue {
	private final Deque<String> queue = new ArrayDeque<>();
	private long quietUntil;

	boolean building() {
		return !queue.isEmpty();
	}

	/** Queues the whole building. Needs command permission (operator) on the server. */
	void build(BlockPos origin, List<String> floors) {
		queue.clear();
		send(origin, T3Office.blueprint(floors));
	}

	void send(BlockPos origin, List<String> commands) {
		for (String command : commands) queue.add(T3Office.absolute(command, origin.getX(), origin.getY(), origin.getZ()));
	}

	/**
	 * While building, the server answers every fill with "Successfully filled…". Those lines are
	 * noise; errors (no permission, unknown block) still come through. "Could not set the block"
	 * is the whiteboard being wiped when it's already blank.
	 */
	boolean hideFeedback(String message) {
		boolean quiet = !queue.isEmpty() || System.currentTimeMillis() < quietUntil;
		return quiet && (message.startsWith("Successfully filled") || message.startsWith("Changed the block")
			|| message.startsWith("Could not set the block"));
	}

	void tick(Minecraft minecraft) {
		if (queue.isEmpty() || minecraft.player == null) return;
		quietUntil = System.currentTimeMillis() + 3000;
		for (int i = 0; i < 6 && !queue.isEmpty(); i++) minecraft.player.connection.sendCommand(queue.poll());
	}
}

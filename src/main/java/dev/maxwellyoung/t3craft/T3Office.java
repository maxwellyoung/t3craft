package dev.maxwellyoung.t3craft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Suite 408: the long loft from the photos, one floor per paired machine, and where the agent
 * villagers go inside it. Pure layout (no client or server classes) so the client village and the
 * shared server office both use it.
 *
 * Coordinates are relative to the office origin (inside floor corner of the ground floor). A floor
 * runs +z from the kitchen/entry wall (z = -1) to the big window (z = LENGTH); facing the window,
 * +x is the left (plain) wall and -x the right (shelf) wall. Floor n sits FLOOR_HEIGHT * n above.
 */
final class T3Office {
	static final int WIDTH = 14;
	static final int LENGTH = 30;
	static final int HEIGHT = 8;
	static final int FLOOR_HEIGHT = HEIGHT + 1;

	// Silk logo, traced from the door and rasterized at 25×29. '#' is a pebble.
	private static final String[] LOGO = {
		".........................",
		".....##.##...............",
		"....###.#####............",
		"...####..######..........",
		"...####..######..........",
		"..######.###.............",
		".#######........##.......",
		"...........#######.......",
		"...........#######.#.....",
		"..#####.....######.##....",
		"######...#...#####.###...",
		"#####....##...####.####..",
		"#####...####...###.#####.",
		"####....#####...#........",
		"###.....######...........",
		".##....########.......##.",
		".#.....#########....####.",
		".#.....#########....####.",
		"......#######........##..",
		"......#####.....#...###..",
		"......##......###.####...",
		"............##########...",
		"..........############...",
		"........########..####...",
		"........########...###...",
		".........#######....##...",
		"...........#####.....#...",
		"............####.........",
		"..............#..........",
	};

	/**
	 * Where a villager ends up: cell, how far it sinks in (seats), and which way it faces
	 * (NaN = toward the player).
	 */
	record Spot(int x, int z, double sink, float yaw) {
		boolean seat() {
			return sink > 0;
		}
	}

	private static final float FACE_PLAYER = Float.NaN;

	/** Seated in the grey office chairs, facing across the table. */
	static final Spot[] WORK = {
		new Spot(5, 8, 0.3, -90), new Spot(8, 9, 0.3, 90), new Spot(5, 10, 0.3, -90), new Spot(8, 11, 0.3, 90),
		new Spot(5, 12, 0.3, -90), new Spot(8, 13, 0.3, 90), new Spot(5, 14, 0.3, -90), new Spot(8, 15, 0.3, 90)};
	/** At the rolling whiteboard in the window corner. */
	static final Spot[] NEEDS_YOU = {new Spot(2, 27, 0, 90), new Spot(2, 28, 0, 90), new Spot(2, 26, 0, 90), new Spot(3, 27, 0, 90)};
	/** Sunk into the sofa facing the window, then the side-table corner and the kitchen. */
	static final Spot[] LOUNGE = {
		new Spot(6, 25, 0.45, 0), new Spot(7, 25, 0.45, 0), new Spot(5, 25, 0.45, 0), new Spot(8, 25, 0.45, 0),
		new Spot(11, 22, 0, FACE_PLAYER), new Spot(4, 4, 0, FACE_PLAYER), new Spot(6, 4, 0, FACE_PLAYER), new Spot(8, 4, 0, FACE_PLAYER)};
	/** In front of the fridge, where finished agents grab a drink on the way to the sofa. */
	static final Spot FRIDGE = new Spot(2, 1, 0, 180);
	/** Just inside the entry door (ground floor); arrivals on upper floors come up the ladder. */
	static final int[] DOOR = {10, 0};
	static final int[] LADDER = {13, 2};

	private static boolean[][] blocked;

	/** Floor cells furniture stands on; villagers walk around them (a seat can be the destination). */
	static synchronized boolean[][] blocked() {
		if (blocked != null) return blocked;
		boolean[][] b = new boolean[WIDTH][LENGTH];
		// Kitchen wall: wire rack, fridge, counter, sink, bin.
		mark(b, 0, 1, 0, 4); mark(b, 1, 0, 5, 0);
		// Long table and its eight office chairs.
		mark(b, 6, 7, 7, 16);
		for (Spot chair : WORK) mark(b, chair.x(), chair.z(), chair.x(), chair.z());
		// Shelf on the right wall; the ladder in the left kitchen corner.
		mark(b, 0, 16, 0, 19); mark(b, LADDER[0], LADDER[1], LADDER[0], LADDER[1]);
		// Left corner: side table, its chairs, floor lamps, the little chair by the window.
		mark(b, 13, 20, 13, 21); mark(b, 12, 21, 12, 21); mark(b, 13, 23, 13, 23); mark(b, 13, 27, 13, 27); mark(b, 12, 28, 12, 28);
		// Sofa, basket, beanbags, whiteboard, curtains.
		mark(b, 3, 24, 10, 25); mark(b, 2, 25, 2, 25); mark(b, 5, 28, 6, 29); mark(b, 8, 28, 9, 29); mark(b, 1, 27, 1, 28);
		mark(b, 0, 29, 0, 29); mark(b, 13, 29, 13, 29);
		blocked = b;
		return b;
	}

	private static void mark(boolean[][] b, int x0, int z0, int x1, int z1) {
		for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) b[x][z] = true;
	}

	/**
	 * Shortest walk between floor cells (8-way, no corner cutting), as cell centres. The destination
	 * may be a seat. Falls back to a straight line if there's no path.
	 */
	static List<double[]> path(int fromX, int fromZ, int toX, int toZ) {
		boolean[][] b = blocked();
		fromX = clamp(fromX, WIDTH); fromZ = clamp(fromZ, LENGTH);
		int[][] previous = new int[WIDTH * LENGTH][];
		boolean[] seen = new boolean[WIDTH * LENGTH];
		ArrayDeque<int[]> open = new ArrayDeque<>();
		open.add(new int[] {fromX, fromZ});
		seen[fromX * LENGTH + fromZ] = true;
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		boolean found = false;
		while (!open.isEmpty()) {
			int[] cell = open.poll();
			if (cell[0] == toX && cell[1] == toZ) {
				found = true;
				break;
			}
			for (int[] s : steps) {
				int x = cell[0] + s[0], z = cell[1] + s[1];
				if (x < 0 || z < 0 || x >= WIDTH || z >= LENGTH || seen[x * LENGTH + z]) continue;
				boolean target = x == toX && z == toZ;
				if (b[x][z] && !target) continue;
				// Diagonals only between two open cells, so nobody clips a table corner.
				if (s[0] != 0 && s[1] != 0 && (b[cell[0] + s[0]][cell[1]] || b[cell[0]][cell[1] + s[1]])) continue;
				seen[x * LENGTH + z] = true;
				previous[x * LENGTH + z] = cell;
				open.add(new int[] {x, z});
			}
		}
		List<double[]> route = new ArrayList<>();
		if (!found) {
			route.add(new double[] {toX + 0.5, toZ + 0.5});
			return route;
		}
		for (int[] cell = {toX, toZ}; cell != null && !(cell[0] == fromX && cell[1] == fromZ); cell = previous[cell[0] * LENGTH + cell[1]]) {
			route.addFirst(new double[] {cell[0] + 0.5, cell[1] + 0.5});
		}
		return route;
	}

	private static int clamp(int v, int size) {
		return Math.max(0, Math.min(size - 1, v));
	}

	/** Commands for the whole building, one floor per label (bottom up), relative to the origin. */
	static List<String> blueprint(List<String> floors) {
		List<String> c = new ArrayList<>();
		int w = WIDTH, l = LENGTH, count = Math.max(1, floors.size());
		int top = count * FLOOR_HEIGHT - 1;
		// Site: clear it, then a stone plaza to stand on.
		c.add("fill -8 0 -7 22 " + (top + 3) + " " + (l + 3) + " air");
		c.add("fill -8 -1 -7 22 -1 " + (l + 3) + " smooth_stone");
		for (int i = 0; i < count; i++) c.addAll(floor(i * FLOOR_HEIGHT, i < floors.size() ? floors.get(i) : ""));
		// Ladder up through the floors in the left kitchen corner.
		if (count > 1) {
			for (int i = 1; i < count; i++) c.add("setblock " + LADDER[0] + " " + (i * FLOOR_HEIGHT - 1) + " " + LADDER[1] + " air");
			c.add("fill " + LADDER[0] + " 0 " + LADDER[1] + " " + LADDER[0] + " " + ((count - 1) * FLOOR_HEIGHT) + " " + LADDER[1]
				+ " ladder[facing=west]");
		}
		// Entry side: a black facade over every floor, the black suite door, SUITE 408, the logo board.
		c.add("fill -1 0 -2 " + w + " " + top + " -2 black_concrete");
		c.add("fill 10 0 -1 10 1 -1 air");
		c.add("setblock 10 0 -2 dark_oak_door[half=lower,facing=south,hinge=left]");
		c.add("setblock 10 1 -2 dark_oak_door[half=upper,facing=south,hinge=left]");
		c.add("setblock 11 1 -3 dark_oak_wall_sign[facing=north]{front_text:{color:\"white\",has_glowing_text:1b,messages:[\"\",\"SUITE\",\"408\",\"\"]}}");
		int logoWidth = LOGO[0].length();
		int left = w / 2 - logoWidth / 2;
		int logoTop = top + 1 + LOGO.length;
		c.add("fill " + (left - 1) + " " + (top + 1) + " -2 " + (left + logoWidth) + " " + (logoTop + 1) + " -2 black_concrete");
		for (int row = 0; row < LOGO.length; row++) {
			int y = logoTop - row;
			String line = LOGO[row];
			for (int x = 0; x < logoWidth; ) {
				if (line.charAt(x) != '#') {
					x++;
					continue;
				}
				int end = x;
				while (end + 1 < logoWidth && line.charAt(end + 1) == '#') end++;
				c.add("fill " + (left + x) + " " + y + " -2 " + (left + end) + " " + y + " -2 smooth_quartz");
				x = end + 1;
			}
		}
		return c;
	}

	/** One floor of the loft at height {@code y0}, labelled with its machine. */
	private static List<String> floor(int y0, String label) {
		List<String> c = new ArrayList<>();
		int w = WIDTH, l = LENGTH, h = HEIGHT;
		// The long white loft: maple strip floor (birch), white walls and ceiling.
		c.add("fill -1 " + (y0 - 1) + " -1 " + w + " " + (y0 + h) + " " + l + " white_concrete hollow");
		c.add("fill 0 " + (y0 - 1) + " 0 " + (w - 1) + " " + (y0 - 1) + " " + (l - 1) + " birch_planks");
		// The real loft is bright; invisible light sources under the ceiling keep the white walls white.
		for (int x = 2; x < w; x += 4) {
			for (int z = 2; z < l; z += 5) c.add("setblock " + x + " " + (y0 + h - 1) + " " + z + " light[level=15]");
		}
		// Window wall: one big window, four sash columns by two rows of big panes, thin black mullions.
		c.add("fill 1 " + (y0 + 1) + " " + l + " 13 " + (y0 + 7) + " " + l + " black_concrete");
		for (int x : new int[] {2, 5, 8, 11}) {
			c.add("fill " + x + " " + (y0 + 2) + " " + l + " " + (x + 1) + " " + (y0 + 3) + " " + l + " glass");
			c.add("fill " + x + " " + (y0 + 5) + " " + l + " " + (x + 1) + " " + (y0 + 6) + " " + l + " glass");
		}
		c.add("fill 8 " + (y0 + 2) + " " + l + " 9 " + (y0 + 2) + " " + l + " smooth_quartz"); // the AC unit
		// Full-height white curtains either side of the window.
		c.add("fill 0 " + (y0 + 1) + " " + (l - 1) + " 0 " + (y0 + 7) + " " + (l - 1) + " white_wool");
		c.add("fill 13 " + (y0 + 1) + " " + (l - 1) + " 13 " + (y0 + 7) + " " + (l - 1) + " white_wool");

		// Kitchen wall, left to right facing it: wire rack + Red Bull fridge, steel fridge,
		// drawer counter with a small wire shelf, farmhouse sink, bin, electrical panel.
		c.add("fill 0 " + y0 + " 1 0 " + y0 + " 4 iron_bars");
		c.add("setblock 0 " + (y0 + 1) + " 1 red_concrete");
		c.add("setblock 0 " + (y0 + 1) + " 2 white_concrete");
		c.add("fill 1 " + y0 + " 0 2 " + (y0 + 1) + " 0 iron_block");
		c.add("setblock 3 " + y0 + " 0 birch_planks");
		c.add("setblock 3 " + (y0 + 1) + " 0 iron_bars");
		c.add("setblock 4 " + y0 + " 0 water_cauldron[level=3]");
		c.add("setblock 5 " + y0 + " 0 white_shulker_box");
		c.add("setblock 12 " + (y0 + 2) + " 0 iron_trapdoor[facing=south,half=top,open=true]");
		// Which machine this floor is.
		if (!label.isBlank()) {
			String text = label.replaceAll("[^A-Za-z0-9 .'-]", "");
			c.add("setblock 11 " + (y0 + 2) + " 0 birch_wall_sign[facing=south]{front_text:{messages:[\"\",\"" + text + "\",\"floor\",\"\"]}}");
		}
		// Jute runner in front of the kitchen (woven floor inlay).
		c.add("fill 1 " + (y0 - 1) + " 2 8 " + (y0 - 1) + " 3 bamboo_mosaic");

		// The long oak work table down the middle, four grey office chairs a side,
		// two white paper pendant lamps above it.
		c.add("fill 6 " + y0 + " 7 7 " + y0 + " 16 oak_slab[type=top]");
		for (Spot chair : WORK) {
			c.add("setblock " + chair.x() + " " + y0 + " " + chair.z() + " andesite_stairs[facing=" + (chair.x() < 6 ? "west" : "east") + "]");
		}
		for (int z : new int[] {9, 14}) {
			c.add("fill 6 " + (y0 + h - 2) + " " + z + " 6 " + (y0 + h - 1) + " " + z + " iron_chain");
			c.add("setblock 6 " + (y0 + h - 3) + " " + z + " sea_lantern");
		}

		// Pine ladder shelf on the right wall: four open shelves, a red-edged magazine stack.
		for (int y = 0; y <= 3; y++) c.add("fill 0 " + (y0 + y) + " 16 0 " + (y0 + y) + " 19 birch_slab[type=top]");
		c.add("setblock 0 " + (y0 + 1) + " 17 red_concrete");

		// Left corner before the lounge: round white side table, two wooden chairs,
		// tripod floor lamps with white shades, a little chair by the window.
		c.add("setblock 13 " + y0 + " 21 smooth_quartz_slab[type=top]");
		c.add("setblock 13 " + y0 + " 20 birch_stairs[facing=north]");
		c.add("setblock 12 " + y0 + " 21 birch_stairs[facing=west]");
		for (int z : new int[] {23, 27}) {
			c.add("fill 13 " + y0 + " " + z + " 13 " + (y0 + 1) + " " + z + " birch_fence");
			c.add("setblock 13 " + (y0 + 2) + " " + z + " white_wool");
		}
		c.add("setblock 12 " + y0 + " 28 birch_stairs[facing=east]");

		// The lounge: plywood box sofa facing the window (tall back board toward the kitchen),
		// white cushion and pillows; laundry basket to its right; sisal rug with two big taupe
		// beanbags against the window; rolling whiteboard in the right corner.
		c.add("fill 3 " + y0 + " 24 10 " + (y0 + 1) + " 24 birch_planks");
		c.add("setblock 3 " + y0 + " 25 birch_planks");
		c.add("setblock 10 " + y0 + " 25 birch_planks");
		c.add("fill 4 " + y0 + " 25 9 " + y0 + " 25 white_wool");
		c.add("setblock 4 " + (y0 + 1) + " 25 white_carpet");
		c.add("setblock 9 " + (y0 + 1) + " 25 white_carpet");
		c.add("setblock 2 " + y0 + " 25 composter");
		c.add("fill 4 " + (y0 - 1) + " 26 10 " + (y0 - 1) + " 29 bamboo_mosaic");
		c.add("fill 5 " + y0 + " 28 6 " + y0 + " 29 light_gray_wool");
		c.add("fill 8 " + y0 + " 28 9 " + y0 + " 29 light_gray_wool");
		c.add("fill 1 " + y0 + " 27 1 " + y0 + " 28 iron_bars");
		c.add("fill 1 " + (y0 + 1) + " 27 1 " + (y0 + 3) + " 28 white_concrete");
		return c;
	}

	/**
	 * Lamps by time of day: at night the paper pendants glow and the floor lamps light up;
	 * by day the pendants are plain white shades. The hidden ceiling lights stay on (daylight).
	 */
	static List<String> lamps(int floors, boolean night) {
		List<String> c = new ArrayList<>();
		for (int i = 0; i < Math.max(1, floors); i++) {
			int y0 = i * FLOOR_HEIGHT;
			for (int z : new int[] {9, 14}) c.add("setblock 6 " + (y0 + HEIGHT - 3) + " " + z + " " + (night ? "sea_lantern" : "white_concrete"));
			for (int z : new int[] {23, 27}) c.add("setblock 12 " + (y0 + 2) + " " + z + " " + (night ? "light[level=13]" : "air"));
		}
		return c;
	}

	/** One thing written on the whiteboard: who needs you and what for. */
	record Note(String title, String detail) {}

	/**
	 * What's written on a floor's whiteboard: the first waiting thread and what it's asking
	 * (the command or question), on two birch signs above the villagers waiting there.
	 * An empty list wipes the board.
	 */
	static List<String> whiteboard(int floor, List<Note> notes) {
		List<String> c = new ArrayList<>();
		int y = floor * FLOOR_HEIGHT + 3;
		if (notes.isEmpty()) {
			c.add("setblock 2 " + y + " 27 air");
			c.add("setblock 2 " + y + " 28 air");
			return c;
		}
		Note first = notes.getFirst();
		List<String> left = new ArrayList<>();
		left.add(notes.size() > 1 ? "NEEDS YOU (" + notes.size() + ")" : "NEEDS YOU");
		left.addAll(wrap(first.title(), 15, 3));
		String detail = first.detail() == null || first.detail().isBlank() ? "Open the panel to answer" : first.detail();
		List<String> right = new ArrayList<>(wrap(detail, 15, 4));
		// Facing the board from the room, z 28 is on the left and z 27 on the right.
		c.add(sign(2, y, 28, left));
		c.add(sign(2, y, 27, right));
		return c;
	}

	private static String sign(int x, int y, int z, List<String> lines) {
		List<String> padded = new ArrayList<>(lines);
		while (padded.size() < 4) padded.add("");
		StringBuilder messages = new StringBuilder();
		for (String line : padded.subList(0, 4)) messages.append(messages.isEmpty() ? "" : ",").append('"').append(line).append('"');
		return "setblock " + x + " " + y + " " + z + " birch_wall_sign[facing=east]{front_text:{messages:[" + messages + "]}}";
	}

	static List<String> wrap(String text, int width, int maxLines) {
		// Sign text goes inside a quoted SNBT string: keep it to plain printable characters.
		String clean = text.replaceAll("[^A-Za-z0-9 .,:;!?&()+'/=_<>-]", "").trim();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : clean.split("\\s+")) {
			while (word.length() > width) {
				if (!line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
				}
				lines.add(word.substring(0, width));
				word = word.substring(width);
				if (lines.size() >= maxLines) return lines.subList(0, maxLines);
			}
			if (line.length() + word.length() + (line.isEmpty() ? 0 : 1) > width) {
				lines.add(line.toString());
				line.setLength(0);
				if (lines.size() >= maxLines) return lines.subList(0, maxLines);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty() && lines.size() < maxLines) lines.add(line.toString());
		return lines;
	}

	/** Rewrites "fill x y z …" / "setblock x y z …" relative coordinates to absolute ones. */
	static String absolute(String command, int ox, int oy, int oz) {
		int coords = command.startsWith("fill") ? 6 : 3;
		String[] parts = command.split(" ", coords + 2);
		for (int i = 0; i < coords; i++) {
			int base = switch (i % 3) {
				case 0 -> ox;
				case 1 -> oy;
				default -> oz;
			};
			parts[i + 1] = Integer.toString(base + Integer.parseInt(parts[i + 1]));
		}
		return String.join(" ", parts);
	}
}

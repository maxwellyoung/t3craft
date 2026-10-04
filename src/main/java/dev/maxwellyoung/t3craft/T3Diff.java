package dev.maxwellyoung.t3craft;

import java.util.ArrayList;
import java.util.List;

/** Keeps Git's patch text intact, including deleted/binary files and rename metadata. */
final class T3Diff {
	record File(String path, List<String> lines) {}
	record Line(String text, Integer oldLine, Integer newLine) {}
	private static final java.util.regex.Pattern HUNK = java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
	static List<Line> numbered(File file) {
		List<Line> result = new ArrayList<>();
		int old = 0, next = 0, oldRemaining = 0, newRemaining = 0;
		for (String text : file.lines()) {
			var hunk = HUNK.matcher(text);
			if (text.startsWith("@@")) {
				oldRemaining = newRemaining = 0;
				if (hunk.matches()) try {
					old = Integer.parseInt(hunk.group(1)); next = Integer.parseInt(hunk.group(3));
					oldRemaining = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
					newRemaining = hunk.group(4) == null ? 1 : Integer.parseInt(hunk.group(4));
				} catch (NumberFormatException invalid) { oldRemaining = newRemaining = 0; }
			}
			Integer a = null, b = null;
			if (text.startsWith(" ") && oldRemaining > 0 && newRemaining > 0) {
				a = old++; b = next++; oldRemaining--; newRemaining--;
			} else if (text.startsWith("-") && oldRemaining > 0) { a = old++; oldRemaining--; }
			else if (text.startsWith("+") && newRemaining > 0) { b = next++; newRemaining--; }
			result.add(new Line(text, a, b));
		}
		return List.copyOf(result);
	}
	static List<Integer> hunks(File file) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < file.lines().size(); i++) if (HUNK.matcher(file.lines().get(i)).matches()) result.add(i);
		return List.copyOf(result);
	}
	static List<File> files(String patch) {
		List<File> files = new ArrayList<>();
		String path = "Changes";
		List<String> lines = new ArrayList<>();
		for (String line : patch.split("\n", -1)) {
			if (line.startsWith("diff --git ")) {
				if (!lines.isEmpty()) files.add(new File(path, List.copyOf(lines)));
				lines.clear();
				// Prefer +++/--- headers below; this fallback also covers binary files and pure renames.
				String header = line.substring(11);
				int split = header.lastIndexOf(" b/");
				path = split >= 0 ? header.substring(split + 3) : header;
			}
			if (line.startsWith("+++ ") && !line.equals("+++ /dev/null")) path = path(line.substring(4));
			else if (line.startsWith("--- ") && line.length() > 4 && !line.equals("--- /dev/null")) path = path(line.substring(4));
			else if (line.startsWith("rename to ")) path = line.substring(10);
			lines.add(line);
		}
		if (!patch.isBlank() && !lines.isEmpty()) files.add(new File(path, List.copyOf(lines)));
		return List.copyOf(files);
	}

	private static String path(String text) {
		// Git quotes escaped paths. Preserve its spelling rather than guessing away bytes.
		if (text.startsWith("\"")) return text;
		return text.startsWith("a/") || text.startsWith("b/") ? text.substring(2) : text;
	}
}

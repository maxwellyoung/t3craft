package dev.maxwellyoung.t3craft;

import java.util.ArrayList;
import java.util.List;

/** Keeps Git's patch text intact, including deleted/binary files and rename metadata. */
final class T3Diff {
	record File(String path, List<String> lines) {}
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

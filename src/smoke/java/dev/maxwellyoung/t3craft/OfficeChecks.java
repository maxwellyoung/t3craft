package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Spatial acceptance checks use synthetic rows and disposable owner-only configuration. */
public final class OfficeChecks {
	public static void main(String[] args) throws Exception {
		String a = "http://127.0.0.1:25680", b = "http://127.0.0.1:25681";
		List<T3State.ThreadRow> rows = new ArrayList<>();
		for (String owner : List.of(a, b)) for (int i = 0; i < 12; i++) rows.add(row(owner, i, i == 10 ? T3State.Status.NEEDS_YOU : i == 9 ? T3State.Status.WORKING : T3State.Status.DONE));
		OfficeRoster.Preferences prefs = new OfficeRoster.Preferences();
		T3State.ThreadRow pin = rows.get(11);
		check(prefs.pin(pin), "old thread can be pinned");
		var selected = OfficeRoster.sync(rows, List.of(a, b), prefs);
		check(selected.size() == 16, "each machine has eight residents despite duplicate names");
		check(selected.stream().anyMatch(r -> r.row().equals(pin)), "old pin beats recent threads");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.NEEDS_YOU).count() == 2, "old waiting work is included on both machines");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.WORKING).count() == 2, "working rows beat recent done rows");
		Map<String, Integer> before = Map.copyOf(prefs.desks);
		List<T3State.ThreadRow> reordered = new ArrayList<>(selected.stream().map(OfficeRoster.Resident::row).toList());
		Collections.reverse(reordered);
		OfficeRoster.sync(reordered, List.of(a, b), prefs);
		check(before.equals(prefs.desks), "recency reorder keeps every included desk");
		OfficeBrain brain = new OfficeBrain(); brain.sync(reordered, List.of(a, b), prefs);
		check(brain.agents().values().stream().filter(r -> r.floor == 1).count() == 8, "same display name still has a distinct second floor");
		check(brain.agents().get(pin.id()).desk == before.get(OfficeRoster.key(pin)), "brain consumes the saved desk");
		check(!OfficeRoster.projectKey(rows.get(0)).equals(OfficeRoster.projectKey(rows.get(12))), "same project title on two machines remains distinct");

		var directory = Files.createTempDirectory("t3craft-office-check-");
		var file = directory.resolve("config.json");
		try {
			T3Config config = new T3Config();
			config.environments.add(new T3Config.Environment("Same name", a, "fixture-only"));
			config.environments.add(new T3Config.Environment("Same name", b, "fixture-only"));
			config.officePreferences.put("world-a", prefs);
			config.officePreferences.put("world-b", new OfficeRoster.Preferences());
			config.save(file);
			T3Config restored = T3Config.load(file);
			check(restored.officePreferences.get("world-a").pinned(pin), "pin survives actual JSON save/load");
			OfficeRoster.sync(reordered, List.of(a, b), restored.officePreferences.get("world-a"));
			check(before.equals(restored.officePreferences.get("world-a").desks), "all desks survive process-style reload");
			check(restored.sharedOfficePreferences.pins.isEmpty() && restored.officePreferences.get("world-b").pins.isEmpty(), "client worlds and operator-owned pins are isolated");
			restored.upsert(new T3Config.Environment("New name", a + "/", "fixture-only"));
			check(restored.environments.size() == 2 && restored.environments.getFirst().label.equals("New name"), "re-pair keeps floor order and owner identity");
			if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "saved config is owner-only");
		} finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }

		int saved = prefs.desks.get(OfficeRoster.key(pin));
		OfficeRoster.sync(List.of(), List.of(a, b), prefs);
		check(prefs.desks.get(OfficeRoster.key(pin)) == saved, "empty reconnect snapshot retains pin identity");
		OfficeRoster.sync(reordered, List.of(a, b), prefs);
		check(prefs.desks.get(OfficeRoster.key(pin)) == saved, "returning pin reclaims its desk");
		List<T3State.ThreadRow> absent = reordered.stream().filter(r -> !r.equals(pin)).toList();
		OfficeRoster.sync(absent, List.of(a, b), prefs);
		check(prefs.pinned(pin), "unavailable pin stays visible for removal");
		prefs.unpin(prefs.pins.getFirst()); OfficeRoster.sync(absent, List.of(a, b), prefs);
		check(!prefs.desks.containsKey(OfficeRoster.key(pin)), "unpin frees unavailable assignment");

		OfficeRoster.Preferences capacity = new OfficeRoster.Preferences();
		for (int i = 0; i < 8; i++) check(capacity.pin(rows.get(i)), "eight pins accepted");
		check(!capacity.pin(rows.get(8)) && capacity.pin(rows.get(12)), "limit applies per owner");
		List<T3State.ThreadRow> waiting = new ArrayList<>();
		for (int i = 0; i < 8; i++) waiting.add(row(a, i, T3State.Status.NEEDS_YOU));
		brain.clear(); brain.sync(waiting, List.of(a), new OfficeRoster.Preferences());
		check(brain.agents().values().stream().map(r -> r.goal).distinct().count() == 8, "eight waiting residents do not share a physical spot");
		System.out.println("PASS stable desks, pinned/waiting/working priority, restart and reconnect persistence, distinct owners/projects, pin removal and shared/client isolation");
	}
	private static T3State.ThreadRow row(String owner, int index, T3State.Status status) {
		JsonObject raw = new JsonObject(); raw.addProperty("projectId", "project");
		return new T3State.ThreadRow(owner.endsWith("0") ? "A-" + index : "B-" + index, "Thread " + index, "Same project", "Same machine", owner, status, Instant.now(), null, raw);
	}
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

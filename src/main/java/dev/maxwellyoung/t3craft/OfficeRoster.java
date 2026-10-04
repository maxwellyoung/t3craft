package dev.maxwellyoung.t3craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved spatial identity; names and activity order never identify a machine or a desk. */
final class OfficeRoster {
	static final int DESKS = 8;
	record Pin(String owner, String threadId, String title) {
		String key() { return OfficeRoster.key(owner, threadId); }
	}
	static final class Preferences {
		List<Pin> pins = new ArrayList<>();
		Map<String, Integer> desks = new LinkedHashMap<>();
		boolean pinned(T3State.ThreadRow row) { return pins.stream().anyMatch(p -> p.key().equals(key(row))); }
		boolean pin(T3State.ThreadRow row) {
			if (pinned(row)) return true;
			if (pins.stream().filter(p -> p.owner().equals(row.ownerKey())).count() >= DESKS) return false;
			pins.add(new Pin(row.ownerKey(), row.id(), row.title())); return true;
		}
		void unpin(Pin pin) { pins.removeIf(p -> p.key().equals(pin.key())); }
		void normalize() {
			if (pins == null) pins = new ArrayList<>();
			if (desks == null) desks = new LinkedHashMap<>();
			pins.removeIf(p -> p == null || p.owner() == null || p.threadId() == null);
		}
	}
	record Resident(T3State.ThreadRow row, int desk) {}
	static String key(String owner, String thread) { return owner + "\n" + thread; }
	static String key(T3State.ThreadRow row) { return key(row.ownerKey(), row.id()); }
	static String projectKey(T3State.ThreadRow row) { return key(row.ownerKey(), row.raw().has("projectId") && !row.raw().get("projectId").isJsonNull() ? row.raw().get("projectId").getAsString() : ""); }

	/** Input is already newest first. Reserve pins, then waiting and working threads. */
	static List<Resident> sync(List<T3State.ThreadRow> rows, List<String> owners, Preferences prefs) {
		prefs.normalize();
		List<Resident> result = new ArrayList<>();
		Set<String> retain = new HashSet<>();
		for (String owner : owners) {
			List<T3State.ThreadRow> candidates = new ArrayList<>(rows.stream().filter(r -> owner.equals(r.ownerKey())).toList());
			Map<String, Integer> pinOrder = new HashMap<>();
			for (int i = 0; i < prefs.pins.size(); i++) pinOrder.put(prefs.pins.get(i).key(), i);
			candidates.sort(Comparator.comparingInt((T3State.ThreadRow r) -> pinOrder.containsKey(key(r)) ? 0 : r.status() == T3State.Status.NEEDS_YOU ? 1 : r.status() == T3State.Status.WORKING ? 2 : 3)
				.thenComparingInt(r -> pinOrder.getOrDefault(key(r), Integer.MAX_VALUE)));
			if (candidates.size() > DESKS) candidates = new ArrayList<>(candidates.subList(0, DESKS));
			boolean[] used = new boolean[DESKS];
			Map<String, Integer> assigned = new LinkedHashMap<>();
			// Keep selected residents in place before allocating any newcomers.
			for (T3State.ThreadRow row : candidates) {
				String key = key(row); Integer desk = prefs.desks.get(key);
				if (desk != null && desk >= 0 && desk < DESKS && !used[desk]) { assigned.put(key, desk); used[desk] = true; }
			}
			for (T3State.ThreadRow row : candidates) {
				String key = key(row);
				if (!assigned.containsKey(key)) for (int desk = 0; desk < DESKS; desk++) if (!used[desk]) { assigned.put(key, desk); used[desk] = true; break; }
				int desk = assigned.get(key); prefs.desks.put(key, desk); retain.add(key); result.add(new Resident(row, desk));
			}
		}
		// Preserve a pin through empty reconnect snapshots. An unavailable pin has no resident,
		// so its desk may be used temporarily; on return pins win any slot conflict.
		for (Pin pin : prefs.pins) retain.add(pin.key());
		prefs.desks.keySet().retainAll(retain);
		return result;
	}
}

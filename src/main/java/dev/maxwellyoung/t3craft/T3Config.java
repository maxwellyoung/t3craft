package dev.maxwellyoung.t3craft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Paired environments and last focused thread. Tokens are credentials, so the file is owner-only. */
public final class T3Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Environment {
		public String label;
		public String baseUrl;
		public String accessToken;

		public Environment(String label, String baseUrl, String accessToken) {
			this.label = label;
			this.baseUrl = baseUrl;
			this.accessToken = accessToken;
		}
	}

	public java.util.List<Environment> environments = new java.util.ArrayList<>();
	// Single-environment fields from 0.1; migrated into environments on load.
	public String baseUrl;
	public String accessToken;
	public String threadId;
	/**
	 * Where the agent villagers stand. On a client this is only the pre-0.1.5 single village,
	 * adopted by the first world visited; on a server it is the shared village.
	 */
	public int[] village;
	/** Client: each world's village, keyed by world (server address or save) and dimension. */
	public java.util.Map<String, int[]> villages = new java.util.HashMap<>();
	/** Client: worlds (same keys) whose village is the Silk office built at that spot. */
	public java.util.Set<String> officeWorlds = new java.util.HashSet<>();
	/** Server: where the shared Silk office stands; null when it's off. */
	public int[] serverOffice;
	/** Client spatial preferences, independently saved per world and dimension. */
	public java.util.Map<String, OfficeRoster.Preferences> officePreferences = new java.util.HashMap<>();
	/** Operator-owned shared office preferences; independent of client worlds. */
	public OfficeRoster.Preferences sharedOfficePreferences = new OfficeRoster.Preferences();
	/** Hand the player a written report when a watched thread finishes (needs command permission). */
	public boolean books = true;
	/** Lets local agents run commands through the MCP server. Off until the player opts in. */
	public boolean agentCommands;

	public boolean paired() {
		return !environments.isEmpty();
	}

	/** Adds or refreshes the environment at {@code baseUrl}. */
	public void upsert(Environment environment) {
		for (int i = 0; i < environments.size(); i++) {
			if (ownerKey(environments.get(i).baseUrl).equals(ownerKey(environment.baseUrl))) { environments.set(i, environment); return; }
		}
		environments.add(environment);
	}

	static String ownerKey(String baseUrl) { return baseUrl.replaceAll("/+$", ""); }

	String labelFor(String owner) {
		for (T3Config.Environment env : environments) if (T3Config.ownerKey(env.baseUrl).equals(owner)) {
			boolean duplicate = environments.stream().filter(e -> java.util.Objects.equals(e.label, env.label)).count() > 1;
			if (!duplicate) return env.label;
			java.net.URI address = java.net.URI.create(owner);
			boolean sameHost = environments.stream().filter(e -> java.util.Objects.equals(e.label, env.label))
				.allMatch(e -> java.util.Objects.equals(java.net.URI.create(e.baseUrl).getHost(), address.getHost()));
			return env.label + " · " + (sameHost && address.getPort() != -1 ? ":" + address.getPort() : address.getAuthority());
		}
		return "Unpaired machine";
	}

	public static T3Config load(Path path) {
		try {
			if (Files.exists(path)) {
				T3Config config = GSON.fromJson(Files.readString(path), T3Config.class);
				if (config != null) {
					if (config.environments == null) config.environments = new java.util.ArrayList<>();
					if (config.environments.isEmpty() && config.baseUrl != null && config.accessToken != null) {
						config.environments.add(new Environment("This Mac", config.baseUrl, config.accessToken));
					}
					if (config.villages == null) config.villages = new java.util.HashMap<>();
					if (config.officeWorlds == null) config.officeWorlds = new java.util.HashSet<>();
					if (config.officePreferences == null) config.officePreferences = new java.util.HashMap<>();
					config.officePreferences.values().removeIf(java.util.Objects::isNull);
					config.officePreferences.values().forEach(OfficeRoster.Preferences::normalize);
					if (config.sharedOfficePreferences == null) config.sharedOfficePreferences = new OfficeRoster.Preferences();
					config.sharedOfficePreferences.normalize();
					config.baseUrl = null;
					config.accessToken = null;
					return config;
				}
			}
		} catch (IOException | RuntimeException e) {
			T3Log.LOGGER.warn("Could not read {}; starting unpaired", path, e);
		}
		return new T3Config();
	}

	/** Write a private replacement first so a new pairing is never briefly world-readable. */
	public boolean save(Path path) {
		Path temporary = null;
		try {
			Path destination = path.toAbsolutePath();
			Files.createDirectories(destination.getParent());
			try {
				temporary = Files.createTempFile(destination.getParent(), ".t3craft-", ".tmp",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} catch (UnsupportedOperationException ignored) {
				// Windows: the replacement inherits the user profile directory ACLs.
				temporary = Files.createTempFile(destination.getParent(), ".t3craft-", ".tmp");
			}
			Files.writeString(temporary, GSON.toJson(this));
			try { Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
			catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
			return true;
		} catch (IOException e) {
			T3Log.LOGGER.warn("Could not save {}", path, e);
			return false;
		} finally {
			if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
		}
	}
}

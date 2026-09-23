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
	/** Where the agent villagers stand; null when the village is off. */
	public int[] village;
	/** Hand the player a written report when a watched thread finishes (needs command permission). */
	public boolean books = true;
	/** Lets local agents run commands through the MCP server. Off until the player opts in. */
	public boolean agentCommands;

	public boolean paired() {
		return !environments.isEmpty();
	}

	/** Adds or refreshes the environment at {@code baseUrl}. */
	public void upsert(Environment environment) {
		environments.removeIf(existing -> existing.baseUrl.equals(environment.baseUrl));
		environments.add(environment);
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

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
			try {
				Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Windows: rely on the user profile ACLs.
			}
		} catch (IOException e) {
			T3Log.LOGGER.warn("Could not save {}", path, e);
		}
	}
}

package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A tiny MCP server (streamable HTTP, JSON responses only) on 127.0.0.1 so agents on this
 * machine can see the player's world and, when the player allows it, run commands in it.
 * Register with: {@code claude mcp add --transport http minecraft http://127.0.0.1:25590/mcp}
 */
final class T3Mcp {
	static final int PORT = 25590;
	private static final String PROTOCOL = "2025-06-18";

	private final BooleanSupplier commandsAllowed;
	private HttpServer server;
	// Server feedback lines ("Successfully filled 49 block(s)") collected while a command is in flight.
	private final java.util.List<String> feedback = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile boolean collecting;

	T3Mcp(BooleanSupplier commandsAllowed) {
		this.commandsAllowed = commandsAllowed;
	}

	void start() {
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (collecting && !overlay) feedback.add(message.getString());
		});
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
			server.createContext("/mcp", this::handle);
			server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "t3craft-mcp");
				thread.setDaemon(true);
				return thread;
			}));
			server.start();
			T3CraftClient.LOGGER.info("Minecraft MCP server on http://127.0.0.1:{}/mcp", PORT);
		} catch (IOException e) {
			T3CraftClient.LOGGER.warn("Could not start the Minecraft MCP server on port {}", PORT, e);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			// Browsers can reach loopback too; refuse anything that came from a web page.
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			if (origin != null && !origin.startsWith("http://127.0.0.1") && !origin.startsWith("http://localhost")) {
				exchange.sendResponseHeaders(403, -1);
				return;
			}
			JsonElement body;
			try (InputStream in = exchange.getRequestBody()) {
				body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			JsonElement reply;
			if (body.isJsonArray()) {
				JsonArray replies = new JsonArray();
				for (JsonElement message : body.getAsJsonArray()) {
					JsonObject one = respond(message.getAsJsonObject());
					if (one != null) replies.add(one);
				}
				reply = replies.isEmpty() ? null : replies;
			} else {
				reply = respond(body.getAsJsonObject());
			}
			if (reply == null) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.warn("MCP request failed", e);
		}
	}

	/** One JSON-RPC message; null for notifications. */
	private JsonObject respond(JsonObject message) {
		if (!message.has("id")) return null;
		String method = message.get("method").getAsString();
		JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
		JsonObject result = switch (method) {
			case "initialize" -> {
				JsonObject r = new JsonObject();
				r.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				r.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", "0.1.0");
				r.add("serverInfo", info);
				r.addProperty("instructions", "The user's live Minecraft game. Use minecraft_status first to learn where they are. "
					+ "Coordinates are absolute block positions. minecraft_run_command only works after the player enables it in game.");
				yield r;
			}
			case "ping" -> new JsonObject();
			case "tools/list" -> {
				JsonObject r = new JsonObject();
				r.add("tools", tools());
				yield r;
			}
			case "tools/call" -> call(params.get("name").getAsString(),
				params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject());
			default -> null;
		};
		JsonObject reply = new JsonObject();
		reply.addProperty("jsonrpc", "2.0");
		reply.add("id", message.get("id"));
		if (result == null) {
			JsonObject error = new JsonObject();
			error.addProperty("code", -32601);
			error.addProperty("message", "Method not found: " + method);
			reply.add("error", error);
		} else {
			reply.add("result", result);
		}
		return reply;
	}

	private static JsonArray tools() {
		JsonArray tools = new JsonArray();
		tools.add(tool("minecraft_status", "Where the player is and what they are looking at: position, facing, dimension, time, game mode, health.",
			schema(Map.of())));
		tools.add(tool("minecraft_nearby_blocks", "Counts of non-air blocks in a cube around the player, plus the block under their feet.",
			schema(Map.of("radius", prop("integer", "Half-width of the cube, 1-8 (default 4)")))));
		tools.add(tool("minecraft_say", "Show a message in the player's chat. Only the player sees it.",
			schema(Map.of("message", prop("string", "Text to show"))), "message"));
		tools.add(tool("minecraft_run_command", "Run a Minecraft command as the player, e.g. \"fill 10 64 10 14 68 14 minecraft:stone\" or "
				+ "\"setblock 3 70 -2 minecraft:redstone_lamp\" (no leading slash). Needs the player to allow it (/t3 agent-build on) and "
				+ "permission on the server. The player sees every command in chat.",
			schema(Map.of("command", prop("string", "Command without the leading slash"))), "command"));
		return tools;
	}

	private JsonObject call(String name, JsonObject args) {
		try {
			String text = switch (name) {
				case "minecraft_status" -> onGameThread(T3Mcp::status);
				case "minecraft_nearby_blocks" -> {
					int radius = args.has("radius") ? Math.max(1, Math.min(8, args.get("radius").getAsInt())) : 4;
					yield onGameThread(() -> nearby(radius));
				}
				case "minecraft_say" -> onGameThread(() -> {
					agentChat(Component.literal(args.get("message").getAsString()).withStyle(ChatFormatting.WHITE));
					return "Shown in the player's chat.";
				});
				case "minecraft_run_command" -> {
					if (!commandsAllowed.getAsBoolean()) {
						yield error("The player has not allowed agent commands. Ask them to run /t3 agent-build on in Minecraft.");
					}
					String command = args.get("command").getAsString().replaceFirst("^/", "").trim();
					yield runCommand(command);
				}
				default -> error("Unknown tool " + name);
			};
			return text.startsWith("error: ") ? result(text.substring(7), true) : result(text, false);
		} catch (Exception e) {
			return result("Minecraft did not respond: " + e.getMessage(), true);
		}
	}

	/** Sends the command, then waits briefly for the server's reply so the agent can check its work. */
	private synchronized String runCommand(String command) throws Exception {
		feedback.clear();
		collecting = true;
		try {
			String sent = onGameThread(() -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.player == null) return "error: the player is not in a world.";
				agentChat(Component.literal("/" + command).withStyle(ChatFormatting.GRAY));
				minecraft.player.connection.sendCommand(command);
				return "sent";
			});
			if (!"sent".equals(sent)) return sent;
			// Feedback usually lands within a tick or two; stop early once it has settled.
			long deadline = System.currentTimeMillis() + 1500;
			int seen = -1;
			while (System.currentTimeMillis() < deadline) {
				Thread.sleep(150);
				if (!feedback.isEmpty() && feedback.size() == seen) break;
				seen = feedback.size();
			}
		} finally {
			collecting = false;
		}
		if (feedback.isEmpty()) return "Sent /" + command + ". The server gave no reply (it may lack permission to show feedback); verify with minecraft_nearby_blocks.";
		return "Ran /" + command + ". Server replied:\n" + String.join("\n", feedback);
	}

	private static String status() {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || minecraft.level == null) return "error: the player is not in a world.";
		JsonObject s = new JsonObject();
		s.addProperty("player", player.getName().getString());
		BlockPos pos = player.blockPosition();
		s.addProperty("position", pos.getX() + " " + pos.getY() + " " + pos.getZ());
		s.addProperty("facing", player.getDirection().getName());
		s.addProperty("yaw", Math.round(player.getYRot()));
		s.addProperty("pitch", Math.round(player.getXRot()));
		s.addProperty("dimension", minecraft.level.dimension().identifier().toString());
		long day = Math.floorMod(minecraft.level.getOverworldClockTime(), 24000L);
		s.addProperty("timeOfDay", day + " (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight)");
		if (minecraft.gameMode != null) s.addProperty("gameMode", minecraft.gameMode.getPlayerMode().getName());
		s.addProperty("health", player.getHealth());
		HitResult hit = minecraft.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos target = block.getBlockPos();
			s.addProperty("lookingAt", blockId(minecraft.level.getBlockState(target)) + " at " + target.getX() + " " + target.getY() + " " + target.getZ());
		} else if (hit instanceof EntityHitResult entity) {
			s.addProperty("lookingAt", entity.getEntity().getName().getString());
		}
		return s.toString();
	}

	private static String nearby(int radius) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) return "error: the player is not in a world.";
		BlockPos center = minecraft.player.blockPosition();
		Map<String, Integer> counts = new TreeMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			BlockState state = minecraft.level.getBlockState(pos);
			if (!state.isAir()) counts.merge(blockId(state), 1, Integer::sum);
		}
		JsonObject s = new JsonObject();
		s.addProperty("center", center.getX() + " " + center.getY() + " " + center.getZ());
		s.addProperty("radius", radius);
		s.addProperty("underFeet", blockId(minecraft.level.getBlockState(center.below())));
		JsonObject blocks = new JsonObject();
		counts.forEach(blocks::addProperty);
		s.add("blocks", blocks);
		return s.toString();
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static void agentChat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		minecraft.gui.hud.getChat().addClientSystemMessage(Component.empty()
			.append(Component.literal("[agent] ").withStyle(ChatFormatting.LIGHT_PURPLE)).append(message));
	}

	private static String onGameThread(java.util.function.Supplier<String> work) throws Exception {
		return Minecraft.getInstance().submit(work).get(5, TimeUnit.SECONDS);
	}

	private static String error(String message) {
		return "error: " + message;
	}

	private static JsonObject result(String text, boolean isError) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray array = new JsonArray();
		array.add(content);
		JsonObject result = new JsonObject();
		result.add("content", array);
		result.addProperty("isError", isError);
		return result;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema, String... required) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) req.add(r);
			inputSchema.add("required", req);
		}
		tool.add("inputSchema", inputSchema);
		return tool;
	}

	private static JsonObject schema(Map<String, JsonObject> properties) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		properties.forEach(props::add);
		schema.add("properties", props);
		return schema;
	}

	private static JsonObject prop(String type, String description) {
		JsonObject prop = new JsonObject();
		prop.addProperty("type", type);
		prop.addProperty("description", description);
		return prop;
	}
}

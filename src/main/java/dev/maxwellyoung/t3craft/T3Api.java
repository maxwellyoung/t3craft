package dev.maxwellyoung.t3craft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal HTTP client for a T3 Code environment. Uses the same public routes as the
 * mobile app: pairing-token exchange, shell/thread snapshots, and command dispatch.
 * Everything here is blocking; callers keep it off the render thread.
 */
public final class T3Api {
	// Only what the panel needs. Pairing can narrow scopes but never widen them.
	private static final String SCOPES = "orchestration:read orchestration:operate";

	// HTTP/1.1: the JDK's default h2c upgrade on plain http makes the server answer 400.
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(5))
		.build();
	private final String baseUrl;
	private final String accessToken;
	private volatile boolean protocol2;
	private volatile int wireProtocol;

	public T3Api(String baseUrl, String accessToken) {
		this.baseUrl = T3Config.ownerKey(baseUrl);
		this.accessToken = accessToken;
	}

	String ownerKey() { return baseUrl; }

	public record Pairing(String baseUrl, String accessToken, long expiresInSeconds) {}

	/** Public environment metadata determines the protocol before any authenticated request. */
	private int protocol() throws IOException, InterruptedException {
		if (wireProtocol != 0) return wireProtocol;
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/t3/environment"))
			.timeout(Duration.ofSeconds(5)).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) throw new IOException("Could not check this T3 machine's protocol. Check its address and network access.");
		JsonObject metadata = JsonParser.parseString(response.body()).getAsJsonObject();
		int version = metadata.has("orchestrationProtocolVersion") && !metadata.get("orchestrationProtocolVersion").isJsonNull()
			? metadata.get("orchestrationProtocolVersion").getAsInt() : 1;
		if (version < 1 || version > 2) throw new UnsupportedVersionException("This machine uses T3 protocol " + version
			+ ". T3 Craft supports protocols 1 and 2. " + (version > 2 ? "Update T3 Craft before reconnecting." : "Update T3 Code before reconnecting."));
		protocol2 = version == 2;
		wireProtocol = version;
		return version;
	}

	void forgetProtocol() { wireProtocol = 0; }

	public static final class UnsupportedVersionException extends IOException {
		UnsupportedVersionException(String message) { super(message); }
	}

	/** Accepts a T3 pairing URL (`http://host:port/pair#token=…`) and exchanges it for a bearer token. */
	public static Pairing pair(String pairingUrl, String clientLabel) throws IOException, InterruptedException {
		PairingTarget target = pairingTarget(pairingUrl);
		String base = target.baseUrl();
		String token = target.token();
		// Refuse incompatible machines before consuming their one-time bootstrap link.
		new T3Api(base, "").protocol();

		String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:token-exchange")
			+ "&subject_token=" + enc(token)
			+ "&subject_token_type=" + enc("urn:t3:params:oauth:token-type:environment-bootstrap")
			+ "&requested_token_type=" + enc("urn:ietf:params:oauth:token-type:access_token")
			+ "&scope=" + enc(SCOPES)
			+ "&client_label=" + enc(clientLabel)
			+ "&client_device_type=desktop";
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/oauth/token"))
			.timeout(Duration.ofSeconds(15))
			.header("content-type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException(response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403
				? "Link expired, used or revoked. In T3 Settings > Connections, create a fresh link."
				: "Pairing failed (" + response.statusCode() + "). Check this machine and try a fresh link.");
		}
		try {
			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
			String access = body.get("access_token").getAsString();
			if (access.isBlank()) throw new IllegalArgumentException();
			return new Pairing(base, access, body.get("expires_in").getAsLong());
		} catch (RuntimeException e) { throw new IOException("T3 returned an invalid pairing response. Update T3 Code and try a fresh link."); }
	}

	private record PairingTarget(String baseUrl, String token) {}

	/** Parse without ever putting the credential-bearing input in an exception. */
	private static PairingTarget pairingTarget(String link) throws IOException {
		try {
			if (link == null || link.length() > 8192) throw new IllegalArgumentException();
			URI uri = URI.create(link.trim());
			String token = param(uri.getRawFragment(), "token");
			if (token == null) token = param(uri.getRawQuery(), "token");
			if (token == null) throw new IOException("Copy the full pairing link, including #token=, from T3 Settings > Connections.");
			String host = param(uri.getRawQuery(), "host");
			String base = host != null ? (host.contains("://") ? host : "https://" + host)
				: uri.getScheme() + "://" + uri.getRawAuthority();
			base = base.replaceFirst("^ws", "http").replaceAll("/+$", "");
			URI address = URI.create(base);
			if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme())) || address.getHost() == null
				|| address.getUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
				|| !address.getPath().isEmpty() || address.getPort() > 65535 || address.getPort() == 0) throw new IllegalArgumentException();
			return new PairingTarget(base, token);
		} catch (RuntimeException e) { throw new IOException("That pairing link is invalid. Copy the complete link from T3 Settings > Connections."); }
	}

	static String pairingAddress(String link) throws IOException { return pairingTarget(link).baseUrl(); }
	boolean sameCredentials(T3Api other) { return baseUrl.equals(other.baseUrl) && accessToken.equals(other.accessToken); }
	int negotiatedProtocol() { return wireProtocol; }

	public record Model(String slug, String name) {}

	public record Provider(String instanceId, String name, boolean requiresNewThreadForModelChange, List<Model> models) {}

	/**
	 * Ready providers and their models. Only exposed over the RPC socket (`server.getConfig`),
	 * so this opens a short-lived WebSocket with a one-time ticket, makes one call, and closes.
	 */
	/** Opens the RPC socket with a one-time ticket, so the long-lived token stays out of the URL. */
	T3Socket openSocket(java.util.function.Consumer<String> onClose) throws IOException, InterruptedException {
		JsonObject ticket = JsonParser.parseString(send(authorized("/api/auth/websocket-ticket")
			.POST(HttpRequest.BodyPublishers.noBody()).build())).getAsJsonObject();
		URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + "/ws?wsTicket=" + enc(ticket.get("ticket").getAsString())
			+ (protocol2 ? "&orchestrationProtocol=2" : ""));
		return T3Socket.connect(HTTP, uri, onClose);
	}

	/** Ready providers and their models. Only exposed over RPC (`server.getConfig`). */
	public List<Provider> providers(T3Socket socket) throws IOException {
		JsonObject config = socket.call("server.getConfig", new JsonObject(), 20);
		List<Provider> providers = new ArrayList<>();
		for (JsonElement element : config.getAsJsonArray("providers")) {
			JsonObject provider = element.getAsJsonObject();
			boolean usable = provider.get("enabled").getAsBoolean()
				&& "ready".equals(provider.get("status").getAsString())
				&& !(provider.has("availability") && "unavailable".equals(provider.get("availability").getAsString()));
			if (!usable) continue;
			List<Model> models = new ArrayList<>();
			for (JsonElement m : provider.getAsJsonArray("models")) {
				JsonObject model = m.getAsJsonObject();
				if (model.has("isLegacy") && model.get("isLegacy").getAsBoolean()) continue;
				models.add(new Model(model.get("slug").getAsString(), model.get("name").getAsString()));
			}
			if (models.isEmpty()) continue;
			// Routers like OpenCode serve one model through several backends; name the backend on duplicates.
			java.util.Map<String, Long> counts = new java.util.HashMap<>();
			for (Model model : models) counts.merge(model.name(), 1L, Long::sum);
			for (int i = 0; i < models.size(); i++) {
				Model model = models.get(i);
				int slash = model.slug().indexOf('/');
				if (counts.get(model.name()) > 1 && slash > 0) {
					models.set(i, new Model(model.slug(), model.name() + " · " + model.slug().substring(0, slash)));
				}
			}
			String instanceId = provider.get("instanceId").getAsString();
			String name = provider.has("displayName") ? provider.get("displayName").getAsString() : providerName(provider.get("driver").getAsString());
			boolean locked = provider.has("requiresNewThreadForModelChange") && provider.get("requiresNewThreadForModelChange").getAsBoolean();
			providers.add(new Provider(instanceId, name, locked, List.copyOf(models)));
		}
		return List.copyOf(providers);
	}

	private static String providerName(String driver) {
		return switch (driver) {
			case "claudeAgent" -> "Claude";
			case "codex" -> "Codex";
			case "opencode" -> "OpenCode";
			default -> Character.toUpperCase(driver.charAt(0)) + driver.substring(1);
		};
	}

	/** Model selection wire shape; options are dropped because they are model-specific. */
	public static JsonObject modelSelection(String instanceId, String model) {
		JsonObject selection = new JsonObject();
		selection.addProperty("instanceId", instanceId);
		selection.addProperty("model", model);
		return selection;
	}

	/** The environment's own display name ("Sam's MacBook Pro", "home-server"…); public, no auth. */
	public static String environmentLabel(String baseUrl) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/.well-known/t3/environment"))
				.timeout(Duration.ofSeconds(5)).GET().build();
			JsonObject body = JsonParser.parseString(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
			return body.get("label").getAsString();
		} catch (Exception e) {
			return URI.create(baseUrl).getHost();
		}
	}

	public JsonObject shell() throws IOException, InterruptedException {
		return adaptShell(get("/api/orchestration/shell").getAsJsonObject());
	}

	/** Recent window of one thread: messages, activities (approvals), session. */
	public JsonObject thread(String threadId, int turnLimit) throws IOException, InterruptedException {
		protocol();
		JsonObject body = get("/api/orchestration/threads/" + enc(threadId) + (protocol2 ? "/bounded" : "?turnLimit=" + turnLimit)).getAsJsonObject();
		if (body.has("projection")) { protocol2 = true; return T3Protocol.projection(body.getAsJsonObject("projection")); }
		return body.getAsJsonObject("thread");
	}


	JsonObject adaptShell(JsonObject source) {
		if (source.has("schemaVersion") && source.get("schemaVersion").getAsInt() >= 2) protocol2 = true;
		JsonObject copy = source.deepCopy();
		var threads = new com.google.gson.JsonArray();
		for (var value : source.getAsJsonArray("threads")) threads.add(adaptShellThread(value.getAsJsonObject()));
		copy.add("threads", threads); return copy;
	}
	JsonObject adaptShellThread(JsonObject source) {
		if (T3Protocol.v2(source)) protocol2 = true;
		return T3Protocol.shell(source);
	}

	public record Review(String threadId, String title, int turnCount, String reply, String diff) {}

	/** Read a completed checkpoint, never the mutable working tree and never a merge operation. */
	public Review review(String threadId) throws IOException, InterruptedException {
		JsonObject detail = thread(threadId, 4);
		int turnCount = 0;
		String replyId = null;
		if (detail.has("checkpoints")) for (var item : detail.getAsJsonArray("checkpoints")) {
			JsonObject checkpoint = item.getAsJsonObject();
			if (checkpoint.has("status") && "ready".equals(checkpoint.get("status").getAsString())
				&& checkpoint.get("checkpointTurnCount").getAsInt() > turnCount) {
				turnCount = checkpoint.get("checkpointTurnCount").getAsInt();
				replyId = checkpoint.has("assistantMessageId") && !checkpoint.get("assistantMessageId").isJsonNull()
					? checkpoint.get("assistantMessageId").getAsString() : null;
			}
		}
		if (turnCount == 0) throw new IOException("No completed checkpoint is available to review yet.");
		JsonObject payload = new JsonObject();
		payload.addProperty("threadId", threadId);
		payload.addProperty("toTurnCount", turnCount);
		T3Socket socket = openSocket(reason -> {});
		try {
			JsonObject result = socket.call("orchestration.getFullThreadDiff", payload, 20);
			if (!threadId.equals(result.get("threadId").getAsString())) throw new IOException("T3 returned a diff for another thread.");
			if (result.get("toTurnCount").getAsInt() != turnCount) throw new IOException("T3 returned a diff for another checkpoint.");
			return new Review(threadId, detail.get("title").getAsString(), turnCount, checkpointReply(detail, replyId), result.get("diff").getAsString());
		} finally { socket.close(); }
	}

	private static String checkpointReply(JsonObject detail, String replyId) {
		if (replyId == null) return "No agent reply is recorded for this checkpoint.";
		if (detail.has("messages")) for (var item : detail.getAsJsonArray("messages")) {
			var message = item.getAsJsonObject();
			if (replyId.equals(message.get("id").getAsString()) && "assistant".equals(message.get("role").getAsString())
				&& message.has("text") && !message.get("text").isJsonNull()) return message.get("text").getAsString();
		}
		return "This checkpoint's reply is outside the recent history window. Its patch is available below.";
	}

	/** Sends a prompt to an existing thread, reusing its runtime and interaction modes. {@code model} null keeps the thread's model. */
	public void sendPrompt(JsonObject threadShell, String text, JsonObject model) throws IOException, InterruptedException {
		// A turn's model only applies to that turn; persist it on the thread first, as the mobile app does.
		if (model != null && !model.equals(threadShell.get("modelSelection"))) {
			JsonObject update = command("thread.meta.update", threadShell.get("id").getAsString());
			update.remove("createdAt");
			update.add("modelSelection", model);
			dispatch(update);
		}
		JsonObject command = turnStart(threadShell.get("id").getAsString(), threadShell, text, model);
		dispatch(command);
	}

	/** Starts a new thread in the same project as {@code template}, with the same settings. Returns its id. */
	public String startThread(JsonObject template, String text, JsonObject model) throws IOException, InterruptedException {
		String threadId = UUID.randomUUID().toString();
		JsonObject turn = turnStart(threadId, template, text, model);
		// turn.start's bootstrap only runs on the socket's dispatch; over HTTP, create the thread first.
		JsonObject create = command("thread.create", threadId);
		create.add("projectId", template.get("projectId"));
		// T3 may regenerate this from the titleSeed; until then the prompt itself is the best label.
		String firstLine = text.strip().lines().findFirst().orElse("New thread");
		create.addProperty("title", firstLine.length() > 60 ? firstLine.substring(0, 59) + "…" : firstLine);
		create.add("modelSelection", turn.get("modelSelection"));
		create.add("runtimeMode", template.get("runtimeMode"));
		create.add("interactionMode", template.get("interactionMode"));
		create.add("branch", template.get("branch"));
		create.add("worktreePath", template.get("worktreePath"));
		dispatch(create);
		turn.addProperty("titleSeed", text.length() > 80 ? text.substring(0, 80) : text);
		dispatch(turn);
		return threadId;
	}

	public void respondToApproval(String threadId, String requestId, String decision) throws IOException, InterruptedException {
		JsonObject command = command("thread.approval.respond", threadId);
		command.addProperty("requestId", requestId);
		command.addProperty("decision", decision);
		dispatch(command);
	}

	/** {@code answers}: question id → option value, list of values (multi-select), or free text. */
	public void answerQuestions(String threadId, String requestId, JsonObject answers) throws IOException, InterruptedException {
		JsonObject command = command("thread.user-input.respond", threadId);
		command.addProperty("requestId", requestId);
		command.add("answers", answers);
		dispatch(command);
	}

	public void interrupt(String threadId) throws IOException, InterruptedException {
		protocol();
		JsonObject command = command("thread.turn.interrupt", threadId);
		if (protocol2) {
			String run = null;
			for (var value : shell().getAsJsonArray("threads")) {
				var row = value.getAsJsonObject();
				if (threadId.equals(T3Protocol.string(row, "id"))) run = T3Protocol.string(row, "activeRunId");
			}
			if (run == null) throw new IOException("There is no active run to stop.");
			command.addProperty("type", "run.interrupt"); command.addProperty("runId", run);
		}
		dispatch(command);
	}

	private static JsonObject turnStart(String threadId, JsonObject settings, String text, JsonObject model) {
		JsonObject command = command("thread.turn.start", threadId);
		JsonObject message = new JsonObject();
		message.addProperty("messageId", UUID.randomUUID().toString());
		message.addProperty("role", "user");
		message.addProperty("text", text);
		message.add("attachments", new com.google.gson.JsonArray());
		command.add("message", message);
		command.add("modelSelection", model != null ? model : settings.get("modelSelection"));
		command.add("runtimeMode", settings.get("runtimeMode"));
		command.add("interactionMode", settings.get("interactionMode"));
		return command;
	}

	private static JsonObject command(String type, String threadId) {
		JsonObject command = new JsonObject();
		command.addProperty("type", type);
		command.addProperty("commandId", UUID.randomUUID().toString());
		command.addProperty("threadId", threadId);
		command.addProperty("createdAt", now());
		return command;
	}

	private void dispatch(JsonObject command) throws IOException, InterruptedException {
		protocol();
		if (protocol2) {
			JsonObject mapped = command.deepCopy(); mapped.remove("createdAt");
			switch (command.get("type").getAsString()) {
				case "thread.approval.respond", "thread.user-input.respond" -> mapped.addProperty("type", "runtime-request.respond");
				case "thread.meta.update" -> mapped.addProperty("type", "thread.model-selection.set");
				case "thread.create" -> { mapped.addProperty("createdBy", "user"); mapped.addProperty("creationSource", "web"); }
				case "thread.turn.start" -> {
					mapped.addProperty("createdBy", "user"); mapped.addProperty("creationSource", "web");
					mapped.addProperty("type", "message.dispatch");
					var message = mapped.remove("message").getAsJsonObject();
					for (String key : new String[] {"messageId", "text", "attachments"}) mapped.add(key, message.get(key));
					JsonObject mode = new JsonObject(); mode.addProperty("type", "start_immediately"); mapped.add("dispatchMode", mode);
				}
				default -> {}
			}
			T3Socket socket = openSocket(reason -> {});
			try { socket.call("orchestration.dispatchCommand", mapped, 20); } finally { socket.close(); }
			return;
		}
		send(authorized("/api/orchestration/dispatch")
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(command.toString()))
			.build());
	}

	private JsonElement get(String path) throws IOException, InterruptedException {
		return JsonParser.parseString(send(authorized(path).GET().build()));
	}

	private HttpRequest.Builder authorized(String path) throws IOException, InterruptedException {
		return HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(Duration.ofSeconds(20))
			.header("authorization", "Bearer " + accessToken)
			.header("x-t3-orchestration-protocol", Integer.toString(protocol()));
	}

	private String send(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new AuthException("Pairing expired, was revoked, or lacks access (" + response.statusCode()
				+ "). Create a new pairing link in T3 Settings > Connections, then use /t3 pair <link>.");
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("T3 " + request.method() + " " + request.uri().getPath() + " → " + response.statusCode() + ": " + errorText(response.body()));
		}
		return response.body();
	}

	public static final class AuthException extends IOException {
		public AuthException(String message) {
			super(message);
		}
	}

	private static String errorText(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			for (String key : new String[] {"message", "error_description", "error", "_tag"}) {
				if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsString();
			}
		} catch (RuntimeException ignored) {
			// Not JSON; fall through to the raw text.
		}
		return body.length() > 200 ? body.substring(0, 200) : body;
	}

	private static String now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
	}

	// The server's form decoder does not treat '+' as a space, so encode spaces as %20.
	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String param(String raw, String name) {
		if (raw == null) return null;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}
}

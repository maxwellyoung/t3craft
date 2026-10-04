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
 *
 * <p>T3 has shipped two orchestration protocols. Protocol 1 (T3 Code 0.0.43 to 0.0.45) dispatches
 * commands over HTTP; protocol 2 (0.0.46 nightlies onward) dispatches them over the RPC socket and
 * reshapes threads. The environment says which one it speaks at {@code /.well-known/t3/environment};
 * {@link T3V2} maps protocol 2 back to the shapes the rest of the mod reads.
 */
public final class T3Api {
	// Only what the panel needs. Pairing can narrow scopes but never widen them.
	private static final String SCOPES = "orchestration:read orchestration:operate";

	/** Orchestration protocols this build speaks. */
	public static final int MIN_PROTOCOL = 1;
	public static final int MAX_PROTOCOL = 2;
	/** Human-readable T3 Code range this build was checked against; keep in sync with the README. */
	public static final String SUPPORTED_T3 = "T3 Code 0.0.43 to 0.0.46 (nightly)";
	private static final String PROTOCOL_HEADER = "x-t3-orchestration-protocol";

	// HTTP/1.1: the JDK's default h2c upgrade on plain http makes the server answer 400.
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(5))
		.build();
	private final String baseUrl;
	private final String accessToken;
	private volatile int protocol;
	// The state's live socket, reused for protocol-2 commands so each one doesn't open its own.
	private volatile T3Socket sharedSocket;

	public T3Api(String baseUrl, String accessToken) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.accessToken = accessToken;
	}

	/** What an environment says about itself; public, no auth. Protocol is 1 when the server doesn't say. */
	public record ServerInfo(String label, String serverVersion, int protocol) {
		public boolean supported() {
			return protocol >= MIN_PROTOCOL && protocol <= MAX_PROTOCOL;
		}

		/** Shown in game when this build can't talk to the environment. */
		public String mismatchMessage() {
			return label + " runs T3 Code " + serverVersion + " (orchestration protocol " + protocol + "). This T3 Craft build supports "
				+ SUPPORTED_T3 + " (protocols " + MIN_PROTOCOL + "-" + MAX_PROTOCOL + ")"
				+ (protocol > MAX_PROTOCOL ? ". Update T3 Craft." : ". Update T3 Code.");
		}
	}

	public static ServerInfo serverInfo(String baseUrl) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/.well-known/t3/environment"))
			.timeout(Duration.ofSeconds(5)).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("T3 at " + baseUrl + " did not describe itself (" + response.statusCode() + ")");
		}
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		String label = T3V2.string(body, "label");
		String version = T3V2.string(body, "serverVersion");
		int protocol = body.has("orchestrationProtocolVersion") && !body.get("orchestrationProtocolVersion").isJsonNull()
			? body.get("orchestrationProtocolVersion").getAsInt() : 1;
		return new ServerInfo(label == null ? URI.create(baseUrl).getHost() : label, version == null ? "unknown" : version, protocol);
	}

	/** The environment's orchestration protocol, asked once; throws if this build can't speak it. */
	public int protocol() throws IOException, InterruptedException {
		int known = protocol;
		if (known != 0) return known;
		ServerInfo info = serverInfo(baseUrl);
		if (!info.supported()) throw new UnsupportedVersionException(info.mismatchMessage());
		T3Log.LOGGER.info("{} runs T3 Code {} (orchestration protocol {})", info.label(), info.serverVersion(), info.protocol());
		protocol = info.protocol();
		return protocol;
	}

	/** Protocol if already known, else 1; for callers that must not block. */
	int knownProtocol() {
		return protocol == 0 ? 1 : protocol;
	}

	public static final class UnsupportedVersionException extends IOException {
		public UnsupportedVersionException(String message) {
			super(message);
		}
	}

	/** Asks again next time; a dropped socket can mean T3 restarted, possibly as a newer version. */
	void forgetProtocol() {
		protocol = 0;
	}

	void attachSocket(T3Socket socket) {
		sharedSocket = socket;
	}

	public record Pairing(String baseUrl, String accessToken, long expiresInSeconds) {}

	/** Accepts a T3 pairing URL (`http://host:port/pair#token=…`) and exchanges it for a bearer token. */
	public static Pairing pair(String pairingUrl, String clientLabel) throws IOException, InterruptedException {
		URI uri = URI.create(pairingUrl.trim());
		String token = param(uri.getRawFragment(), "token");
		if (token == null) token = param(uri.getRawQuery(), "token");
		if (token == null) throw new IOException("That link has no #token=…; copy the full pairing URL from T3.");
		String host = param(uri.getRawQuery(), "host");
		String base = host != null
			? (host.contains("://") ? host : "https://" + host)
			: uri.getScheme() + "://" + uri.getRawAuthority();
		base = base.replaceFirst("^ws", "http").replaceAll("/+$", "");

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
			throw new IOException("Pairing failed (" + response.statusCode() + "): " + errorText(response.body()));
		}
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		return new Pairing(base, body.get("access_token").getAsString(), body.get("expires_in").getAsLong());
	}

	public record Model(String slug, String name) {}

	public record Provider(String instanceId, String name, boolean requiresNewThreadForModelChange, List<Model> models) {}

	/** Opens the RPC socket with a one-time ticket, so the long-lived token stays out of the URL. */
	T3Socket openSocket(java.util.function.Consumer<String> onClose) throws IOException, InterruptedException {
		int protocol = protocol();
		JsonObject ticket = JsonParser.parseString(send(authorized("/api/auth/websocket-ticket")
			.POST(HttpRequest.BodyPublishers.noBody()).build())).getAsJsonObject();
		// Protocol-2 servers refuse the upgrade (426) unless the client names the protocol it speaks.
		String query = "wsTicket=" + enc(ticket.get("ticket").getAsString()) + (protocol >= 2 ? "&orchestrationProtocol=" + protocol : "");
		URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + "/ws?" + query);
		return T3Socket.connect(HTTP, uri, onClose);
	}

	/** One RPC call on the shared socket, or on a short-lived one when the state has none open. */
	private JsonObject rpc(String tag, JsonObject payload) throws IOException, InterruptedException {
		T3Socket shared = sharedSocket;
		if (shared != null && shared.isOpen()) return shared.call(tag, payload, 30);
		T3Socket socket = openSocket(reason -> {
		});
		try {
			return socket.call(tag, payload, 30);
		} finally {
			socket.close();
		}
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

	/** The environment's own display name ("Maxwell's MacBook Pro", "Klaus"…); public, no auth. */
	public static String environmentLabel(String baseUrl) {
		try {
			return serverInfo(baseUrl).label();
		} catch (Exception e) {
			return URI.create(baseUrl).getHost();
		}
	}

	/** Projects and threads. Protocol-2 threads come back with protocol-1 fields added (see {@link T3V2}). */
	public JsonObject shell() throws IOException, InterruptedException {
		JsonObject shell = get("/api/orchestration/shell").getAsJsonObject();
		for (JsonElement thread : T3V2.array(shell, "threads")) {
			if (thread.isJsonObject()) T3V2.normalizeShellThread(thread.getAsJsonObject());
		}
		return shell;
	}

	/** Recent window of one thread: messages, activities (approvals), session. */
	public JsonObject thread(String threadId, int turnLimit) throws IOException, InterruptedException {
		if (protocol() >= 2) {
			// The bounded snapshot keeps every pending request but only a recent window of the timeline.
			JsonObject bounded = get("/api/orchestration/threads/" + enc(threadId) + "/bounded").getAsJsonObject();
			return T3V2.threadDetail(threadId, bounded, turnLimit);
		}
		return get("/api/orchestration/threads/" + enc(threadId) + "?turnLimit=" + turnLimit)
			.getAsJsonObject().getAsJsonObject("thread");
	}

	/** Sends a prompt to an existing thread, reusing its runtime and interaction modes. {@code model} null keeps the thread's model. */
	public void sendPrompt(JsonObject threadShell, String text, JsonObject model) throws IOException, InterruptedException {
		if (protocol() >= 2) {
			String threadId = threadShell.get("id").getAsString();
			if (model != null && !model.equals(threadShell.get("modelSelection"))) {
				JsonObject set = commandV2("thread.model-selection.set", threadId);
				set.add("modelSelection", model);
				rpc("orchestration.dispatchCommand", set);
			}
			JsonObject message = commandV2("message.dispatch", threadId);
			message.addProperty("createdBy", "user");
			message.addProperty("creationSource", CREATION_SOURCE);
			message.addProperty("messageId", UUID.randomUUID().toString());
			message.addProperty("text", text);
			message.add("attachments", new com.google.gson.JsonArray());
			if (model != null) message.add("modelSelection", model);
			// Let the server steer, queue, or start, as the T3 apps do.
			message.addProperty("deliveryIntent", "auto");
			JsonObject mode = new JsonObject();
			mode.addProperty("type", "start_immediately");
			message.add("dispatchMode", mode);
			rpc("orchestration.dispatchCommand", message);
			return;
		}
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
		if (protocol() >= 2) {
			JsonObject launch = new JsonObject();
			launch.addProperty("commandId", UUID.randomUUID().toString());
			launch.addProperty("creationSource", CREATION_SOURCE);
			launch.addProperty("threadId", threadId);
			launch.add("projectId", template.get("projectId"));
			launch.addProperty("title", firstLineTitle(text));
			launch.addProperty("generateTitle", true);
			launch.add("modelSelection", model != null ? model : template.get("modelSelection"));
			launch.add("runtimeMode", template.get("runtimeMode"));
			launch.add("interactionMode", template.get("interactionMode"));
			// Same checkout as the template thread: its worktree if it has one, else the project root.
			JsonObject workspace = new JsonObject();
			String worktree = T3V2.string(template, "worktreePath");
			String branch = T3V2.string(template, "branch");
			workspace.addProperty("type", worktree != null ? "existing_worktree" : "root");
			if (worktree != null) workspace.addProperty("worktreePath", worktree);
			if (branch != null) workspace.addProperty("branch", branch);
			launch.add("workspaceStrategy", workspace);
			JsonObject message = new JsonObject();
			message.addProperty("messageId", UUID.randomUUID().toString());
			message.addProperty("text", text);
			message.add("attachments", new com.google.gson.JsonArray());
			launch.add("initialMessage", message);
			JsonObject result = rpc("orchestration.launchThread", launch);
			return result != null && result.has("threadId") ? result.get("threadId").getAsString() : threadId;
		}
		JsonObject turn = turnStart(threadId, template, text, model);
		// turn.start's bootstrap only runs on the socket's dispatch; over HTTP, create the thread first.
		JsonObject create = command("thread.create", threadId);
		create.add("projectId", template.get("projectId"));
		// T3 may regenerate this from the titleSeed; until then the prompt itself is the best label.
		create.addProperty("title", firstLineTitle(text));
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
		if (protocol() >= 2) {
			JsonObject command = commandV2("runtime-request.respond", threadId);
			command.addProperty("requestId", requestId);
			command.addProperty("decision", decision);
			rpc("orchestration.dispatchCommand", command);
			return;
		}
		JsonObject command = command("thread.approval.respond", threadId);
		command.addProperty("requestId", requestId);
		command.addProperty("decision", decision);
		dispatch(command);
	}

	/** {@code answers}: question id → option value, list of values (multi-select), or free text. */
	public void answerQuestions(String threadId, String requestId, JsonObject answers) throws IOException, InterruptedException {
		if (protocol() >= 2) {
			JsonObject command = commandV2("runtime-request.respond", threadId);
			command.addProperty("requestId", requestId);
			command.add("answers", answers);
			rpc("orchestration.dispatchCommand", command);
			return;
		}
		JsonObject command = command("thread.user-input.respond", threadId);
		command.addProperty("requestId", requestId);
		command.add("answers", answers);
		dispatch(command);
	}

	/** Stops the thread's running turn. {@code threadShell} is the thread's shell row (protocol 2 needs its run id). */
	public void interrupt(JsonObject threadShell) throws IOException, InterruptedException {
		String threadId = threadShell.get("id").getAsString();
		if (protocol() >= 2) {
			String runId = T3V2.string(threadShell, "activeRunId");
			if (runId == null) runId = T3V2.string(threadShell, "latestRunId");
			if (runId == null) return;
			JsonObject command = commandV2("run.interrupt", threadId);
			command.addProperty("runId", runId);
			command.addProperty("holdQueue", true);
			rpc("orchestration.dispatchCommand", command);
			return;
		}
		dispatch(command("thread.turn.interrupt", threadId));
	}

	// Protocol 2 only knows its own clients; the mod pairs the way the mobile app does.
	private static final String CREATION_SOURCE = "mobile";

	private static String firstLineTitle(String text) {
		String firstLine = text.strip().lines().findFirst().orElse("New thread");
		return firstLine.length() > 60 ? firstLine.substring(0, 59) + "…" : firstLine;
	}

	private static JsonObject commandV2(String type, String threadId) {
		JsonObject command = new JsonObject();
		command.addProperty("type", type);
		command.addProperty("commandId", UUID.randomUUID().toString());
		command.addProperty("threadId", threadId);
		return command;
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
		send(authorized("/api/orchestration/dispatch")
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(command.toString()))
			.build());
	}

	private JsonElement get(String path) throws IOException, InterruptedException {
		return JsonParser.parseString(send(authorized(path).GET().build()));
	}

	private HttpRequest.Builder authorized(String path) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(Duration.ofSeconds(20))
			.header("authorization", "Bearer " + accessToken);
		// Protocol-2 orchestration routes reject requests that don't name the protocol.
		int protocol = protocol();
		if (protocol >= 2) builder.header(PROTOCOL_HEADER, Integer.toString(protocol));
		return builder;
	}

	private String send(HttpRequest request) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new AuthException("T3 rejected this device (" + response.statusCode() + "). Pair again with /t3 pair <url>.");
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

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

	public T3Api(String baseUrl, String accessToken) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.accessToken = accessToken;
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

	/**
	 * Ready providers and their models. Only exposed over the RPC socket (`server.getConfig`),
	 * so this opens a short-lived WebSocket with a one-time ticket, makes one call, and closes.
	 */
	/** Opens the RPC socket with a one-time ticket, so the long-lived token stays out of the URL. */
	T3Socket openSocket(java.util.function.Consumer<String> onClose) throws IOException, InterruptedException {
		JsonObject ticket = JsonParser.parseString(send(authorized("/api/auth/websocket-ticket")
			.POST(HttpRequest.BodyPublishers.noBody()).build())).getAsJsonObject();
		URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + "/ws?wsTicket=" + enc(ticket.get("ticket").getAsString()));
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

	/** The environment's own display name ("Maxwell's MacBook Pro", "Klaus"…); public, no auth. */
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
		return get("/api/orchestration/shell").getAsJsonObject();
	}

	/** Recent window of one thread: messages, activities (approvals), session. */
	public JsonObject thread(String threadId, int turnLimit) throws IOException, InterruptedException {
		return get("/api/orchestration/threads/" + enc(threadId) + "?turnLimit=" + turnLimit)
			.getAsJsonObject().getAsJsonObject("thread");
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
		dispatch(command("thread.turn.interrupt", threadId));
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

	private HttpRequest.Builder authorized(String path) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(Duration.ofSeconds(20))
			.header("authorization", "Bearer " + accessToken);
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

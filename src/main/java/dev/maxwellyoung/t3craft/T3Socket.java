package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One RPC WebSocket to a T3 environment, speaking Effect RPC's JSON framing:
 * {@code Request} out; {@code Chunk} (stream values, each needs an {@code Ack}) and
 * {@code Exit} (end of a call) in. Streams and one-shot calls share the connection.
 */
final class T3Socket {
	interface StreamHandler {
		void onValue(JsonObject value);

		default void onEnd(JsonObject exit) {
		}
	}

	private final WebSocket socket;
	private final Map<String, StreamHandler> handlers = new ConcurrentHashMap<>();
	private final AtomicLong ids = new AtomicLong();
	private final CompletableFuture<String> closed = new CompletableFuture<>();
	private final StringBuilder buffer = new StringBuilder();

	private T3Socket(WebSocket socket) {
		this.socket = socket;
	}

	static T3Socket connect(HttpClient http, URI uri, Consumer<String> onClose) throws IOException {
		T3Socket[] self = new T3Socket[1];
		CompletableFuture<Void> ready = new CompletableFuture<>();
		try {
			WebSocket socket = http.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
				.buildAsync(uri, new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket ws) {
						ws.request(1);
					}

					@Override
					public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
						ready.join();
						self[0].receive(data, last);
						ws.request(1);
						return null;
					}

					@Override
					public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
						ready.join();
						self[0].shutdown("closed (" + code + ") " + reason, onClose);
						return null;
					}

					@Override
					public void onError(WebSocket ws, Throwable error) {
						ready.join();
						self[0].shutdown(String.valueOf(error.getMessage()), onClose);
					}
				}).get(10, TimeUnit.SECONDS);
			self[0] = new T3Socket(socket);
			ready.complete(null);
			return self[0];
		} catch (Exception e) {
			ready.complete(null);
			throw new IOException("Could not open T3 socket: " + e.getMessage(), e);
		}
	}

	boolean isOpen() {
		return !closed.isDone();
	}

	/** Starts a streaming RPC; returns its request id so it can be interrupted. */
	String stream(String tag, JsonObject payload, StreamHandler handler) {
		String id = Long.toString(ids.incrementAndGet());
		handlers.put(id, handler);
		send(request(id, tag, payload));
		return id;
	}

	/** One-shot RPC; blocks for its Exit value. */
	JsonObject call(String tag, JsonObject payload, long timeoutSeconds) throws IOException {
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		String id = stream(tag, payload, new StreamHandler() {
			@Override
			public void onValue(JsonObject value) {
			}

			@Override
			public void onEnd(JsonObject exit) {
				result.complete(exit);
			}
		});
		try {
			JsonObject exit = CompletableFuture.anyOf(result, closed).get(timeoutSeconds, TimeUnit.SECONDS) instanceof JsonObject json
				? json : null;
			if (exit == null) throw new IOException("T3 socket closed during " + tag);
			if (!"Success".equals(exit.get("_tag").getAsString())) throw new IOException(tag + " failed: " + exit);
			return exit.getAsJsonObject("value");
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(tag + " did not answer: " + e.getMessage(), e);
		} finally {
			handlers.remove(id);
		}
	}

	void interrupt(String requestId) {
		if (requestId == null || handlers.remove(requestId) == null) return;
		send("{\"_tag\":\"Interrupt\",\"requestId\":\"" + requestId + "\"}");
	}

	void ping() {
		send("{\"_tag\":\"Ping\"}");
	}

	void close() {
		if (isOpen()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		closed.complete("closed by client");
	}

	private static String request(String id, String tag, JsonObject payload) {
		JsonObject request = new JsonObject();
		request.addProperty("_tag", "Request");
		request.addProperty("id", id);
		request.addProperty("tag", tag);
		request.add("payload", payload);
		request.add("headers", new JsonArray());
		return request.toString();
	}

	private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

	// WebSocket allows one outstanding send; chain them without blocking the listener thread.
	private synchronized void send(String text) {
		if (!isOpen()) return;
		sending = sending.handle((ok, error) -> null)
			.thenCompose(ignored -> socket.sendText(text, true))
			.exceptionally(error -> {
				T3CraftClient.LOGGER.debug("T3 socket send failed", error);
				return null;
			});
	}

	private void receive(CharSequence data, boolean last) {
		buffer.append(data);
		if (!last) return;
		String text = buffer.toString();
		buffer.setLength(0);
		JsonObject message;
		try {
			message = JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		String tag = message.get("_tag").getAsString();
		String requestId = message.has("requestId") ? message.get("requestId").getAsString() : null;
		StreamHandler handler = requestId == null ? null : handlers.get(requestId);
		switch (tag) {
			case "Chunk" -> {
				// Ack first: the server holds the next chunk until this one is acknowledged.
				send("{\"_tag\":\"Ack\",\"requestId\":\"" + requestId + "\"}");
				if (handler != null) {
					for (JsonElement value : message.getAsJsonArray("values")) {
						try {
							handler.onValue(value.getAsJsonObject());
						} catch (RuntimeException e) {
							T3CraftClient.LOGGER.warn("T3 stream handler failed", e);
						}
					}
				}
			}
			case "Exit" -> {
				if (handler != null) {
					handlers.remove(requestId);
					handler.onEnd(message.getAsJsonObject("exit"));
				}
			}
			default -> {
				// Pong and protocol notices need no handling.
			}
		}
	}

	private void shutdown(String reason, Consumer<String> onClose) {
		if (closed.complete(reason)) onClose.accept(reason);
	}
}

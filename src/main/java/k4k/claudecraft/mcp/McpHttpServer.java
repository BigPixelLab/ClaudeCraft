package k4k.claudecraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.ClaudeCraftException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Minimal MCP server over the Streamable HTTP transport: JSON-RPC requests are POSTed to {@code /mcp}
 * and answered with a plain JSON body (no SSE streams). Only tools are supported.
 */
public final class McpHttpServer {
	private static final String PATH = "/mcp";
	private static final String SESSION_HEADER = "Mcp-Session-Id";
	private static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
	private static final Gson GSON = new Gson();

	private final Map<String, McpTool> tools = new LinkedHashMap<>();
	private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
	private final String version;
	private final String token;
	private final RunningGameGuard runningGameGuard;
	@Nullable
	private HttpServer http;
	@Nullable
	private ExecutorService executor;

	/**
	 * Keeps the game running while a tool that needs it is executing.
	 */
	@FunctionalInterface
	public interface RunningGameGuard {
		/**
		 * @return a lease that is closed when the tool finishes
		 * @throws InterruptedException if the worker thread is interrupted while waiting
		 */
		AutoCloseable acquire() throws InterruptedException;
	}

	/**
	 * @param version          mod version reported to clients
	 * @param token            bearer token required from clients; empty disables authentication
	 * @param runningGameGuard keeps the game unpaused for tools marked {@link McpTool#needsRunningGame}
	 */
	public McpHttpServer(String version, String token, RunningGameGuard runningGameGuard) {
		this.version = version;
		this.token = token;
		this.runningGameGuard = runningGameGuard;
	}

	/**
	 * @param tool tool to expose; must be registered before {@link #start}
	 */
	public void register(McpTool tool) {
		tools.put(tool.name(), tool);
	}

	/**
	 * Starts listening on the loopback interface only, so nothing outside this machine can reach the server.
	 *
	 * @param port TCP port
	 * @throws IOException if the port cannot be bound
	 */
	public void start(int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "ClaudeCraft MCP");
			thread.setDaemon(true);
			return thread;
		});
		http.setExecutor(executor);
		http.createContext(PATH, this::handle);
		http.start();
		ClaudeCraft.LOGGER.info("MCP server listening on http://127.0.0.1:{}{}", port, PATH);
	}

	/**
	 * Stops the server and its worker threads.
	 */
	public void stop() {
		if (http != null) {
			http.stop(0);
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	/**
	 * Handles one HTTP exchange.
	 */
	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
				send(exchange, 403, null);
				return;
			}
			if (!token.isEmpty() && !("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				send(exchange, 401, null);
				return;
			}
			switch (exchange.getRequestMethod()) {
				case "POST" -> handlePost(exchange);
				case "DELETE" -> {
					String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
					if (id != null) {
						sessions.remove(id);
					}
					send(exchange, 200, null);
				}
				// No server-initiated messages, so no SSE stream to offer
				default -> send(exchange, 405, null);
			}
		} catch (RuntimeException e) {
			ClaudeCraft.LOGGER.error("MCP request failed", e);
		}
	}

	/**
	 * Handles a POST carrying a JSON-RPC message or a batch of them.
	 */
	private void handlePost(HttpExchange exchange) throws IOException {
		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		// Requiring JSON forces browsers into a CORS preflight, which we never answer
		if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
			send(exchange, 415, null);
			return;
		}
		JsonElement body;
		try (InputStream in = exchange.getRequestBody()) {
			body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			send(exchange, 400, errorResponse(null, -32700, "Parse error"));
			return;
		}

		McpSession session = resolveSession(exchange, body);
		List<Consumer<Boolean>> afterResponse = new ArrayList<>();
		JsonElement response;
		if (body.isJsonArray()) {
			JsonArray responses = new JsonArray();
			for (JsonElement message : body.getAsJsonArray()) {
				JsonObject reply = message.isJsonObject() ? dispatch(message.getAsJsonObject(), session, afterResponse) : errorResponse(null, -32600, "Invalid request");
				if (reply != null) {
					responses.add(reply);
				}
			}
			response = responses.isEmpty() ? null : responses;
		} else if (body.isJsonObject()) {
			response = dispatch(body.getAsJsonObject(), session, afterResponse);
		} else {
			response = errorResponse(null, -32600, "Invalid request");
		}

		exchange.getResponseHeaders().set(SESSION_HEADER, session.getId());
		boolean delivered = false;
		try {
			send(exchange, response == null ? 202 : 200, response);
			delivered = true;
		} finally {
			for (Consumer<Boolean> callback : afterResponse) {
				callback.accept(delivered);
			}
		}
	}

	/**
	 * Picks the session for a request. Unknown session ids are adopted instead of rejected with 404,
	 * so clients keep working after the game restarts; they only need to re-attach their agent.
	 */
	private McpSession resolveSession(HttpExchange exchange, JsonElement body) {
		boolean initialize = body.isJsonObject() && "initialize".equals(stringField(body.getAsJsonObject(), "method"));
		String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
		if (initialize || id == null) {
			id = UUID.randomUUID().toString();
		}
		return sessions.computeIfAbsent(id, McpSession::new);
	}

	/**
	 * Handles a single JSON-RPC message.
	 *
	 * @param afterResponse collects tool callbacks to run once the HTTP response is written
	 * @return the response, or {@code null} for notifications and responses
	 */
	@Nullable
	private JsonObject dispatch(JsonObject message, McpSession session, List<Consumer<Boolean>> afterResponse) {
		JsonElement id = message.get("id");
		String method = stringField(message, "method");
		if (id == null || method == null) {
			return null;
		}
		JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
		return switch (method) {
			case "initialize" -> resultResponse(id, initialize(params));
			case "ping" -> resultResponse(id, new JsonObject());
			case "tools/list" -> resultResponse(id, listTools());
			case "tools/call" -> callTool(id, params, session, afterResponse);
			default -> errorResponse(id, -32601, "Method not found: " + method);
		};
	}

	/**
	 * @return the {@code initialize} result
	 */
	private JsonObject initialize(JsonObject params) {
		String requested = stringField(params, "protocolVersion");
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0));
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());
		result.add("capabilities", capabilities);
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "claudecraft");
		serverInfo.addProperty("version", version);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", """
			Controls the Minecraft world hosted by this game client (singleplayer or LAN host).
			Typical flow: agent_list, then agent_attach to an existing agent or agent_spawn a new one; \
			execute_command runs commands relative to your agent (@s is the agent, ~ ~ ~ is its position); \
			move it with commands like 'tp @s ~ ~ ~5' or 'tp @s ~ ~ ~ facing 0 64 0'; screenshot shows what it sees. \
			Use complete_command and command_usage when unsure about command syntax (modded commands included).""");
		return result;
	}

	/**
	 * @return the {@code tools/list} result
	 */
	private JsonObject listTools() {
		JsonArray list = new JsonArray();
		tools.values().forEach(tool -> list.add(tool.toJson()));
		JsonObject result = new JsonObject();
		result.add("tools", list);
		return result;
	}

	/**
	 * Runs a tool; tool failures become {@code isError} results rather than JSON-RPC errors, as MCP prescribes.
	 */
	private JsonObject callTool(JsonElement id, JsonObject params, McpSession session, List<Consumer<Boolean>> afterResponse) {
		String name = stringField(params, "name");
		McpTool tool = name == null ? null : tools.get(name);
		if (tool == null) {
			return errorResponse(id, -32602, "Unknown tool: " + name);
		}
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
		ToolResult result;
		try (AutoCloseable lease = tool.needsRunningGame() ? runningGameGuard.acquire() : null) {
			result = tool.handler().call(new ToolCall(session, args));
		} catch (ClaudeCraftException e) {
			result = ToolResult.error(e.getMessage());
		} catch (TimeoutException e) {
			result = ToolResult.error("Timed out waiting for the game. Is it frozen or still loading?");
		} catch (Exception e) {
			ClaudeCraft.LOGGER.error("Tool {} failed", name, e);
			result = ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		if (result.getAfterResponse() != null) {
			afterResponse.add(result.getAfterResponse());
		}
		return resultResponse(id, result.toJson());
	}

	/**
	 * Rejects browser requests from non-local pages; tools like Claude Code send no Origin header at all.
	 */
	private static boolean isAllowedOrigin(@Nullable String origin) {
		if (origin == null) {
			return true;
		}
		try {
			String host = URI.create(origin).getHost();
			return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * @return the string value of a field, or {@code null} if it is absent or not a string
	 */
	@Nullable
	private static String stringField(JsonObject object, String field) {
		JsonElement value = object.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/**
	 * @return a JSON-RPC success response
	 */
	private static JsonObject resultResponse(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	/**
	 * @return a JSON-RPC error response
	 */
	private static JsonObject errorResponse(@Nullable JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

	/**
	 * Sends a response with an optional JSON body.
	 */
	private static void send(HttpExchange exchange, int status, @Nullable JsonElement body) throws IOException {
		if (body == null) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}

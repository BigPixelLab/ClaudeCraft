package k4k.claudecraft.mcp;

import com.google.gson.JsonObject;

/**
 * A tool exposed over MCP.
 *
 * @param name        tool name
 * @param description what the tool does and when to use it, shown to the model
 * @param inputSchema JSON schema of the arguments
 * @param handler     implementation, called on an HTTP worker thread
 * @param needsRunningGame whether the game must not be paused while the tool runs
 */
public record McpTool(String name, String description, JsonObject inputSchema, Handler handler, boolean needsRunningGame) {
	/**
	 * Creates a tool that works fine while the game is paused.
	 */
	public McpTool(String name, String description, JsonObject inputSchema, Handler handler) {
		this(name, description, inputSchema, handler, false);
	}

	/**
	 * @return a copy of this tool that unpauses the game while it runs
	 */
	public McpTool needingRunningGame() {
		return new McpTool(name, description, inputSchema, handler, true);
	}

	/**
	 * Tool implementation.
	 */
	@FunctionalInterface
	public interface Handler {
		/**
		 * @param call session and arguments
		 * @return tool result
		 * @throws Exception any failure; {@link k4k.claudecraft.ClaudeCraftException} messages are shown as is
		 */
		ToolResult call(ToolCall call) throws Exception;
	}

	/**
	 * @return the tool description in {@code tools/list} format
	 */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.addProperty("description", description);
		json.add("inputSchema", inputSchema);
		return json;
	}
}

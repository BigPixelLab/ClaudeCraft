package k4k.claudecraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Result of a tool call: a list of MCP content blocks plus the error flag.
 */
public final class ToolResult {
	private final JsonArray content = new JsonArray();
	private final boolean error;
	@Nullable
	private Consumer<Boolean> afterResponse;

	/**
	 * @param error whether the call failed
	 */
	private ToolResult(boolean error) {
		this.error = error;
	}

	/**
	 * @param text result text
	 * @return a successful text result
	 */
	public static ToolResult text(String text) {
		return new ToolResult(false).addText(text);
	}

	/**
	 * @param message what went wrong
	 * @return a failed result
	 */
	public static ToolResult error(String message) {
		return new ToolResult(true).addText(message);
	}

	/**
	 * @param base64   encoded image data
	 * @param mimeType image MIME type
	 * @return a successful result with the image as its first block
	 */
	public static ToolResult image(String base64, String mimeType) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "image");
		block.addProperty("data", base64);
		block.addProperty("mimeType", mimeType);
		ToolResult result = new ToolResult(false);
		result.content.add(block);
		return result;
	}

	/**
	 * @param text text block to append
	 * @return this result
	 */
	public ToolResult addText(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		content.add(block);
		return this;
	}

	/**
	 * Registers a callback for when the HTTP response carrying this result has been written (or failed to be).
	 * Lets tools hand something out exactly once, e.g. delete notes only after Claude actually received them.
	 *
	 * @param callback receives {@code true} if the response was written, {@code false} otherwise
	 * @return this result
	 */
	public ToolResult afterResponse(Consumer<Boolean> callback) {
		this.afterResponse = callback;
		return this;
	}

	/**
	 * @return the delivery callback, or {@code null}
	 */
	@Nullable
	public Consumer<Boolean> getAfterResponse() {
		return afterResponse;
	}

	/**
	 * @return the result in {@code tools/call} response format
	 */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.add("content", content);
		json.addProperty("isError", error);
		return json;
	}
}

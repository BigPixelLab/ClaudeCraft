package k4k.claudecraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import k4k.claudecraft.ClaudeCraftException;
import org.jetbrains.annotations.Nullable;

/**
 * Arguments of a {@code tools/call} request together with the calling session.
 *
 * @param session calling session
 * @param args    tool arguments
 */
public record ToolCall(McpSession session, JsonObject args) {
	/**
	 * @param name argument name
	 * @return the string argument
	 * @throws ClaudeCraftException if it is missing
	 */
	public String string(String name) {
		String value = optString(name, null);
		if (value == null) {
			throw new ClaudeCraftException("Missing required argument '" + name + "'.");
		}
		return value;
	}

	/**
	 * @param name     argument name
	 * @param fallback value when the argument is absent
	 * @return the string argument or {@code fallback}
	 */
	@Nullable
	public String optString(String name, @Nullable String fallback) {
		JsonElement value = args.get(name);
		return value == null || value.isJsonNull() ? fallback : value.getAsString();
	}

	/**
	 * @param name     argument name
	 * @param fallback value when the argument is absent
	 * @return the integer argument or {@code fallback}
	 */
	public int optInt(String name, int fallback) {
		JsonElement value = args.get(name);
		return value == null || value.isJsonNull() ? fallback : value.getAsInt();
	}

	/**
	 * @param name     argument name
	 * @param fallback value when the argument is absent
	 * @return the boolean argument or {@code fallback}
	 */
	public boolean optBool(String name, boolean fallback) {
		JsonElement value = args.get(name);
		return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
	}

	/**
	 * @param name argument name
	 * @return the number argument, or {@code null} when absent
	 */
	@Nullable
	public Double optDouble(String name) {
		JsonElement value = args.get(name);
		return value == null || value.isJsonNull() ? null : value.getAsDouble();
	}

	/**
	 * @param name argument name
	 * @return the number argument
	 * @throws ClaudeCraftException if it is missing
	 */
	public double number(String name) {
		Double value = optDouble(name);
		if (value == null) {
			throw new ClaudeCraftException("Missing required argument '" + name + "'.");
		}
		return value;
	}
}

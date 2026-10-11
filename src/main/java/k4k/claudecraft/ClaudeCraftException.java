package k4k.claudecraft;

/**
 * An expected failure whose message is meant to be shown to the MCP client as is
 * (unknown agent, no world hosted, blocked command, ...).
 */
public class ClaudeCraftException extends RuntimeException {
	/**
	 * @param message human-readable explanation of what went wrong and, ideally, how to fix it
	 */
	public ClaudeCraftException(String message) {
		super(message);
	}
}

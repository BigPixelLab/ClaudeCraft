package k4k.claudecraft.mcp;

import org.jetbrains.annotations.Nullable;

/**
 * State of one MCP client connection. Several Claude sessions can work in parallel,
 * each bound to its own agent.
 */
public final class McpSession {
	private final String id;
	@Nullable
	private volatile String attachedAgent;

	/**
	 * @param id value of the {@code Mcp-Session-Id} header
	 */
	public McpSession(String id) {
		this.id = id;
	}

	/**
	 * @return session id
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return name of the agent tools act on by default, or {@code null} if none is attached
	 */
	@Nullable
	public String getAttachedAgent() {
		return attachedAgent;
	}

	/**
	 * @param agentName agent to act on by default, or {@code null} to detach
	 */
	public void setAttachedAgent(@Nullable String agentName) {
		this.attachedAgent = agentName;
	}
}

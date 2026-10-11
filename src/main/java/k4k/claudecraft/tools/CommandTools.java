package k4k.claudecraft.tools;

import k4k.claudecraft.ClaudeCraftConfig;
import k4k.claudecraft.command.CommandRunner;
import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.mcp.McpTool;
import k4k.claudecraft.mcp.Schema;
import k4k.claudecraft.mcp.ToolCall;
import k4k.claudecraft.mcp.ToolResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tools for running commands and exploring command syntax.
 */
public final class CommandTools {
	private static final String AS_DESCRIPTION =
		"Who executes the command: 'agent' (default when an agent is selected; @s is the agent, ~ ~ ~ is its position) "
			+ "or 'player' (the host player).";
	private static final String AGENT_DESCRIPTION = "Agent to use instead of the session's attached agent.";

	private CommandTools() {
	}

	/**
	 * @param server MCP server to register the tools on
	 */
	public static void register(McpHttpServer server) {
		server.register(new McpTool(
			"execute_command",
			"Executes a Minecraft command and returns its feedback. "
				+ "Runs relative to the selected agent by default, so the host player can move freely. "
				+ "Uses the agent's permission level (see agent_request_permission); 'as player' is capped by it too. "
				+ "Move the agent with commands, e.g. 'tp @s ~ ~ ~5' or 'tp @s ~ ~ ~ facing 0 64 0'.",
			Schema.object()
				.string("command", "Command to run, with or without the leading slash.", true)
				.enumeration("as", AS_DESCRIPTION, false, "agent", "player")
				.string("agent", AGENT_DESCRIPTION, false)
				.build(),
			CommandTools::execute
		).needingRunningGame());
		server.register(new McpTool(
			"complete_command",
			"Returns Tab-completion suggestions for a partial command, including modded commands and registry ids. "
				+ "Use it when unsure about syntax or argument values; end the input with a space to list the next argument's options.",
			Schema.object()
				.string("command", "Partial command, with or without the leading slash.", true)
				.integer("limit", "Maximum number of suggestions (default 50).", false)
				.enumeration("as", AS_DESCRIPTION, false, "agent", "player")
				.string("agent", AGENT_DESCRIPTION, false)
				.build(),
			CommandTools::complete
		).needingRunningGame());
		server.register(new McpTool(
			"command_usage",
			"Shows the syntax branches that can follow a command prefix, like /help but for any depth "
				+ "(e.g. 'execute', 'give @s', 'execute as @s'). Empty input lists all root commands. "
				+ "Only commands allowed at the agent's permission level are shown.",
			Schema.object()
				.string("command", "Command prefix, with or without the leading slash. Empty lists all commands.", false)
				.string("agent", AGENT_DESCRIPTION, false)
				.build(),
			CommandTools::usage
		).needingRunningGame());
	}

	/**
	 * Implements {@code execute_command}.
	 */
	private static ToolResult execute(ToolCall call) throws Exception {
		String command = call.string("command");
		String as = call.optString("as", null);
		String agent = GameAccess.optionalAgentName(call);
		MinecraftServer server = GameAccess.server();
		GameAccess.prepareSource(server, as, agent);
		List<String> blocked = List.copyOf(ClaudeCraftConfig.get().blockedCommands);
		CommandRunner.Result result = GameAccess.onServer(server, () ->
			CommandRunner.execute(GameAccess.source(server, as, agent), command, blocked));

		StringBuilder text = new StringBuilder();
		if (as == null && agent == null) {
			// Without this hint '@s' silently meaning the host player is easy to miss
			text.append("note: no agent is attached, so the command ran as the host player\n");
		}
		text.append("success: ").append(result.success())
			.append("\nresult: ").append(result.returnValue());
		if (result.error() != null) {
			text.append("\nerror: ").append(result.error());
		}
		if (!result.output().isEmpty()) {
			text.append("\noutput:");
			result.output().forEach(line -> text.append("\n  ").append(line));
		}
		String warning = GameAccess.pauseWarning();
		if (warning != null) {
			text.append("\n").append(warning);
		}
		return result.error() != null ? ToolResult.error(text.toString()) : ToolResult.text(text.toString());
	}

	/**
	 * Implements {@code complete_command}.
	 */
	private static ToolResult complete(ToolCall call) throws Exception {
		String command = call.string("command");
		int limit = Math.max(1, call.optInt("limit", 50));
		String as = call.optString("as", null);
		String agent = GameAccess.optionalAgentName(call);
		MinecraftServer server = GameAccess.server();
		GameAccess.prepareSource(server, as, agent);
		// The future is awaited off the server thread: some suggestion providers complete it on the server thread
		CompletableFuture<List<CommandRunner.Completion>> future = GameAccess.onServer(server, () -> {
			ServerCommandSource source = GameAccess.source(server, as, agent);
			return CommandRunner.complete(source, command);
		});
		List<CommandRunner.Completion> completions = GameAccess.await(future);
		if (completions.isEmpty()) {
			return ToolResult.text("No suggestions. Check the syntax with command_usage.");
		}
		StringBuilder text = new StringBuilder();
		text.append(completions.size()).append(" suggestion(s)");
		if (completions.size() > limit) {
			text.append(", showing first ").append(limit);
		}
		text.append(":");
		for (CommandRunner.Completion completion : completions.subList(0, Math.min(limit, completions.size()))) {
			text.append("\n/").append(completion.applied());
			if (completion.tooltip() != null) {
				text.append("    -- ").append(completion.tooltip());
			}
		}
		return ToolResult.text(text.toString());
	}

	/**
	 * Implements {@code command_usage}.
	 */
	private static ToolResult usage(ToolCall call) throws Exception {
		String command = call.optString("command", "");
		String agent = GameAccess.optionalAgentName(call);
		MinecraftServer server = GameAccess.server();
		GameAccess.prepareSource(server, null, agent);
		// Usage is filtered by the caller's permission level, so it only shows what Claude may actually run
		List<String> lines = GameAccess.onServer(server, () ->
			CommandRunner.usage(GameAccess.source(server, null, agent), command));
		return ToolResult.text(lines.isEmpty() ? "No further arguments." : String.join("\n", lines));
	}
}

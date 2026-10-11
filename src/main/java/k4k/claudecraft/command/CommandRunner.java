package k4k.claudecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import k4k.claudecraft.ClaudeCraft;
import net.minecraft.command.CommandException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runs, completes and describes commands through the server's Brigadier dispatcher.
 * Every method must be called on the server thread.
 */
public final class CommandRunner {
	private CommandRunner() {
	}

	/**
	 * Outcome of a command execution.
	 *
	 * @param success      whether the command succeeded at least once and reported no errors
	 * @param returnValue  value returned by the dispatcher (e.g. affected entity count)
	 * @param successCount how many of the (possibly forked) executions succeeded
	 * @param output       feedback messages, including error messages sent by the command itself
	 * @param error        parse or execution error, if any
	 */
	public record Result(boolean success, int returnValue, int successCount, List<String> output, @Nullable String error) {
	}

	/**
	 * A single completion candidate.
	 *
	 * @param text    replacement text
	 * @param start   start of the replaced range in the input
	 * @param end     end of the replaced range in the input
	 * @param tooltip optional description of the candidate
	 * @param applied the whole input with the candidate applied
	 */
	public record Completion(String text, int start, int end, @Nullable String tooltip, String applied) {
	}

	/**
	 * Executes a command, capturing its output.
	 *
	 * @param source  base source (position, permissions, executing entity)
	 * @param command command with or without the leading slash
	 * @param blocked root literals that must not be executed
	 * @return execution outcome
	 */
	public static Result execute(ServerCommandSource source, String command, Collection<String> blocked) {
		String input = stripSlash(command);
		CapturingCommandOutput output = new CapturingCommandOutput();
		int[] successCount = {0};
		ServerCommandSource capturing = source.withOutput(output).withConsumer((context, success, result) -> {
			if (success) {
				successCount[0]++;
			}
		});
		CommandDispatcher<ServerCommandSource> dispatcher = source.getServer().getCommandManager().getDispatcher();
		ParseResults<ServerCommandSource> parse = dispatcher.parse(input, capturing);

		String blockedRoot = findBlockedRoot(parse, input, blocked);
		if (blockedRoot != null) {
			return new Result(false, 0, 0, List.of(), "Command '/" + blockedRoot + "' is blocked by blockedCommands in config/claudecraft.json.");
		}
		CommandSyntaxException parseError = CommandManager.getException(parse);
		if (parseError != null) {
			// Brigadier hides commands the source may not use, which reads as "unknown command"
			CommandNode<ServerCommandSource> root = dispatcher.getRoot().getChild(input.split(" ", 2)[0]);
			if (root != null && !root.canUse(capturing)) {
				return new Result(false, 0, 0, List.of(), "'/" + root.getName() + "' needs a higher permission level than this source has. "
					+ "Use agent_request_permission to ask the host for more.");
			}
			return new Result(false, 0, 0, List.of(), parseError.getMessage());
		}

		String error = null;
		int returnValue = 0;
		try {
			returnValue = dispatcher.execute(parse);
		} catch (CommandException e) {
			error = e.getTextMessage().getString();
		} catch (CommandSyntaxException e) {
			error = e.getMessage();
		} catch (RuntimeException e) {
			ClaudeCraft.LOGGER.error("Command '/{}' threw an exception", input, e);
			error = "Command threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		boolean success = error == null && output.getErrorCount() == 0 && successCount[0] > 0;
		return new Result(success, returnValue, successCount[0], output.getMessages(), error);
	}

	/**
	 * Requests completions for a partial command, exactly as pressing Tab in chat would.
	 *
	 * @param source source to complete for (affects permissions and relative coordinates)
	 * @param command partial command with or without the leading slash
	 * @return future completions; it may complete outside the server thread
	 */
	public static CompletableFuture<List<Completion>> complete(ServerCommandSource source, String command) {
		String input = stripSlash(command);
		CommandDispatcher<ServerCommandSource> dispatcher = source.getServer().getCommandManager().getDispatcher();
		ParseResults<ServerCommandSource> parse = dispatcher.parse(input, source);
		return dispatcher.getCompletionSuggestions(parse).thenApply(suggestions -> {
			List<Completion> result = new ArrayList<>();
			for (Suggestion s : suggestions.getList()) {
				String tooltip = s.getTooltip() == null ? null : s.getTooltip().getString();
				result.add(new Completion(s.getText(), s.getRange().getStart(), s.getRange().getEnd(), tooltip, s.apply(input)));
			}
			return result;
		});
	}

	/**
	 * Describes what may follow the given command prefix, like {@code /help} but for any node.
	 *
	 * @param source  source whose permissions filter the available branches
	 * @param command command prefix with or without the leading slash; empty lists root commands
	 * @return human-readable usage lines
	 */
	public static List<String> usage(ServerCommandSource source, String command) {
		String input = stripSlash(command).trim();
		CommandDispatcher<ServerCommandSource> dispatcher = source.getServer().getCommandManager().getDispatcher();
		List<String> lines = new ArrayList<>();
		if (input.isEmpty()) {
			for (CommandNode<ServerCommandSource> child : dispatcher.getRoot().getChildren()) {
				if (child.canUse(source)) {
					lines.add(child.getName());
				}
			}
			lines.sort(String::compareTo);
			return lines;
		}

		ParseResults<ServerCommandSource> parse = dispatcher.parse(input, source);
		CommandContextBuilder<ServerCommandSource> context = parse.getContext();
		while (context.getChild() != null && !context.getChild().getNodes().isEmpty()) {
			context = context.getChild();
		}
		List<ParsedCommandNode<ServerCommandSource>> nodes = context.getNodes();
		if (nodes.isEmpty()) {
			CommandSyntaxException error = CommandManager.getException(parse);
			lines.add("Unknown command" + (error == null ? "." : ": " + error.getMessage()));
			return lines;
		}
		ParsedCommandNode<ServerCommandSource> last = nodes.get(nodes.size() - 1);
		String prefix = "/" + input.substring(0, last.getRange().getEnd());
		if (parse.getReader().canRead()) {
			CommandSyntaxException error = CommandManager.getException(parse);
			lines.add("Note: only '" + prefix + "' was understood" + (error == null ? "." : ": " + error.getMessage()));
		}

		CommandNode<ServerCommandSource> node = last.getNode();
		if (node.getCommand() != null) {
			lines.add(prefix + "    (executable as is)");
		}
		CommandNode<ServerCommandSource> redirect = node.getRedirect();
		if (redirect != null) {
			lines.add(prefix + " " + (redirect == dispatcher.getRoot() ? "<any command>" : "-> " + redirect.getUsageText()));
			node = redirect == dispatcher.getRoot() ? node : redirect;
		}
		for (Map.Entry<CommandNode<ServerCommandSource>, String> usage : dispatcher.getSmartUsage(node, source).entrySet()) {
			lines.add(prefix + " " + usage.getValue());
		}
		return lines;
	}

	/**
	 * Looks at the root literal of every chained context ({@code execute ... run <command>} creates a child
	 * context per redirect), so blocked commands cannot be smuggled through {@code execute}.
	 *
	 * @return the blocked root literal, or {@code null} if the command is allowed
	 */
	@Nullable
	private static String findBlockedRoot(ParseResults<ServerCommandSource> parse, String input, Collection<String> blocked) {
		String firstWord = input.split(" ", 2)[0];
		if (blocked.contains(firstWord)) {
			return firstWord;
		}
		for (CommandContextBuilder<ServerCommandSource> context = parse.getContext(); context != null; context = context.getChild()) {
			if (!context.getNodes().isEmpty()) {
				String root = context.getNodes().get(0).getNode().getName();
				if (blocked.contains(root)) {
					return root;
				}
			}
		}
		return null;
	}

	/**
	 * @return the command without a leading slash
	 */
	private static String stripSlash(String command) {
		return command.startsWith("/") ? command.substring(1) : command;
	}
}

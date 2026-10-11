package k4k.claudecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.ServerHost;
import k4k.claudecraft.agent.AgentIndex;
import k4k.claudecraft.agent.AgentService;
import k4k.claudecraft.agent.PermissionRequests;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /claudecraft} — host-only management of agents:
 * <pre>
 * /claudecraft permission approve|deny &lt;request&gt;
 * /claudecraft permission set &lt;agent&gt; &lt;level&gt;
 * /claudecraft permission reset &lt;agent&gt;
 * </pre>
 * Only the host may run it, never an agent (see {@link ServerHost#isIssuedByHost}).
 */
public final class ClaudeCraftCommand {
	private ClaudeCraftCommand() {
	}

	/**
	 * @param dispatcher server command dispatcher
	 */
	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("claudecraft")
			.requires(ServerHost::isIssuedByHost)
			.then(literal("permission")
				.then(literal("approve")
					.then(argument("request", IntegerArgumentType.integer(1))
						.executes(context -> decide(context, true))))
				.then(literal("deny")
					.then(argument("request", IntegerArgumentType.integer(1))
						.executes(context -> decide(context, false))))
				.then(literal("set")
					.then(argument("agent", StringArgumentType.word())
						.suggests((context, builder) -> CommandSource.suggestMatching(
							AgentIndex.get(context.getSource().getServer()).all().keySet(), builder))
						.then(argument("level", IntegerArgumentType.integer(0, 4))
							.executes(context -> set(context, IntegerArgumentType.getInteger(context, "level"))))))
				.then(literal("reset")
					.then(argument("agent", StringArgumentType.word())
						.suggests((context, builder) -> CommandSource.suggestMatching(
							AgentIndex.get(context.getSource().getServer()).all().keySet(), builder))
						.executes(context -> set(context, null))))));
	}

	/**
	 * Approves or denies a pending request.
	 */
	private static int decide(CommandContext<ServerCommandSource> context, boolean approve) {
		int id = IntegerArgumentType.getInteger(context, "request");
		context.getSource().sendFeedback(() -> PermissionRequests.decide(context.getSource().getServer(), id, approve), false);
		return 1;
	}

	/**
	 * Sets or resets an agent's level directly.
	 */
	private static int set(CommandContext<ServerCommandSource> context, Integer level) {
		ServerCommandSource source = context.getSource();
		String agent = StringArgumentType.getString(context, "agent");
		try {
			AgentService.setPermissionLevel(source.getServer(), agent, level);
		} catch (ClaudeCraftException e) {
			source.sendError(Text.translatable("claudecraft.agent.unknown", agent));
			return 0;
		}
		int effective = AgentService.getPermissionLevel(source.getServer(), agent);
		source.sendFeedback(() -> Text.translatable("claudecraft.permission.set", agent, effective), false);
		return 1;
	}
}

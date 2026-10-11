package k4k.claudecraft.tools;

import k4k.claudecraft.ClaudeCraftConfig;
import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.ServerHost;
import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.agent.AgentService;
import k4k.claudecraft.mcp.ToolCall;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bridges MCP worker threads to the game: finds the hosted server, runs code on the server thread,
 * and resolves which agent a tool call acts on.
 */
final class GameAccess {
	/** Loading may include generating new terrain, so allow up to ~10 seconds. */
	private static final int AGENT_LOAD_ATTEMPTS = 100;
	private static final long AGENT_LOAD_DELAY_MS = 100;

	private GameAccess() {
	}

	/**
	 * @return the integrated server of the world this client hosts
	 * @throws ClaudeCraftException if no world is hosted (main menu, or connected to someone else's server)
	 */
	static IntegratedServer server() {
		IntegratedServer server = MinecraftClient.getInstance().getServer();
		if (server == null || !server.isRunning()) {
			throw new ClaudeCraftException("No world is hosted by this game client. Open a singleplayer world (or host it over LAN) first.");
		}
		return server;
	}

	/**
	 * Runs a task on the server thread and waits for it.
	 *
	 * @param server server to run on
	 * @param task   task to run
	 * @return the task's result
	 * @throws TimeoutException if the server does not run the task in time
	 */
	static <T> T onServer(MinecraftServer server, Supplier<T> task) throws TimeoutException, InterruptedException {
		return await(server.submit(task));
	}

	/**
	 * Waits for a future using the configured tool timeout, unwrapping execution failures.
	 *
	 * @param future future to wait for
	 * @return its value
	 * @throws TimeoutException if it does not complete in time
	 */
	static <T> T await(Future<T> future) throws TimeoutException, InterruptedException {
		try {
			return future.get(ClaudeCraftConfig.get().toolTimeoutSeconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new RuntimeException(e.getCause());
		}
	}

	/**
	 * @param call tool call
	 * @return the agent named in the {@code agent} argument, or the session's attached agent
	 * @throws ClaudeCraftException if neither is set
	 */
	static String agentName(ToolCall call) {
		String name = optionalAgentName(call);
		if (name == null) {
			throw new ClaudeCraftException("No agent selected. Pass 'agent', or call agent_attach / agent_spawn first (agent_list shows existing agents).");
		}
		return name;
	}

	/**
	 * @param call tool call
	 * @return the agent named in the {@code agent} argument, the session's attached agent, or {@code null}
	 */
	@Nullable
	static String optionalAgentName(ToolCall call) {
		return call.optString("agent", call.session().getAttachedAgent());
	}

	/**
	 * Waits until the named agent is loaded, loading its chunk if needed.
	 *
	 * @param server running server
	 * @param name   agent name
	 * @throws ClaudeCraftException if the agent does not exist or does not load in time
	 */
	static void ensureAgentLoaded(MinecraftServer server, String name) throws TimeoutException, InterruptedException {
		for (int i = 0; i < AGENT_LOAD_ATTEMPTS; i++) {
			if (onServer(server, () -> AgentService.find(server, name) != null)) {
				return;
			}
			Thread.sleep(AGENT_LOAD_DELAY_MS);
		}
		throw new AgentNotLoadedException(name);
	}

	/**
	 * Thrown when a known agent's entity does not show up after its chunk has been requested.
	 */
	static final class AgentNotLoadedException extends ClaudeCraftException {
		/**
		 * @param name agent name
		 */
		AgentNotLoadedException(String name) {
			super("Agent '" + name + "' is still loading. Try again in a moment; if this persists, agent_remove can forget it.");
		}
	}

	/**
	 * Server thread only.
	 *
	 * @param server running server
	 * @param name   agent name; the agent must already be loaded
	 * @return the agent
	 */
	static AgentEntity loadedAgent(MinecraftServer server, String name) {
		AgentEntity agent = AgentService.find(server, name);
		if (agent == null) {
			throw new ClaudeCraftException("Agent '" + name + "' was unloaded. Try again.");
		}
		return agent;
	}

	/**
	 * Server thread only.
	 *
	 * @param server running server
	 * @return the hosting player, or {@code null} if they are not in the world (yet)
	 */
	@Nullable
	static ServerPlayerEntity hostPlayer(MinecraftServer server) {
		return ServerHost.getPlayer(server);
	}

	/**
	 * Server thread only.
	 *
	 * @param agent agent to execute as
	 * @return a source positioned at the agent, with the agent as {@code @s} and the agent's permission level
	 */
	static ServerCommandSource agentSource(AgentEntity agent) {
		String name = agent.getAgentName() == null ? "agent" : agent.getAgentName();
		MinecraftServer server = agent.getServer();
		return new ServerCommandSource(
			CommandOutput.DUMMY,
			agent.getPos(),
			new Vec2f(agent.getPitch(), agent.getYaw()),
			(ServerWorld) agent.getWorld(),
			AgentService.getPermissionLevel(server, name),
			name,
			Text.literal(name),
			server,
			agent
		);
	}

	/**
	 * Server thread only.
	 *
	 * @param server running server
	 * @param agent  session's agent whose level caps the source, or {@code null} to cap at the default level
	 * @return a source positioned at the host player
	 */
	static ServerCommandSource playerSource(MinecraftServer server, @Nullable String agent) {
		ServerPlayerEntity player = hostPlayer(server);
		if (player == null) {
			throw new ClaudeCraftException("The host player is not in the world.");
		}
		// Claude acting as the host must not gain more rights than its agent has
		int cap = agent == null ? AgentService.defaultPermissionLevel() : AgentService.getPermissionLevel(server, agent);
		return player.getCommandSource().withLevel(Math.min(server.getPermissionLevel(player.getGameProfile()), cap));
	}

	/**
	 * Resolves the source for {@code execute_command} and friends.
	 * Server thread only; the agent must already be loaded if one is used.
	 *
	 * @param server running server
	 * @param as     "agent", "player" or {@code null} to pick the agent when one is selected
	 * @param agent  selected agent name, or {@code null}
	 * @return the command source
	 */
	static ServerCommandSource source(MinecraftServer server, @Nullable String as, @Nullable String agent) {
		boolean useAgent = as == null ? agent != null : as.equals("agent");
		if (!useAgent) {
			return playerSource(server, agent);
		}
		if (agent == null) {
			throw new ClaudeCraftException("No agent selected. Pass 'agent', call agent_attach / agent_spawn first, or use as='player'.");
		}
		return agentSource(loadedAgent(server, agent));
	}

	/**
	 * Prepares the agent for {@link #source} by loading it when it will be used.
	 */
	static void prepareSource(MinecraftServer server, @Nullable String as, @Nullable String agent) throws TimeoutException, InterruptedException {
		boolean useAgent = as == null ? agent != null : as.equals("agent");
		if (useAgent && agent != null) {
			ensureAgentLoaded(server, agent);
		}
	}

	/**
	 * @return a warning to append to results while singleplayer is paused, or {@code null}
	 */
	@Nullable
	static String pauseWarning() {
		return MinecraftClient.getInstance().isPaused()
			? "Warning: the game is still paused because a pausing screen is open on the host (e.g. options), so world changes are not sent to the client until it is closed."
			: null;
	}
}

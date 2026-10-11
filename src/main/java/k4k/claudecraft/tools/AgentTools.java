package k4k.claudecraft.tools;

import k4k.claudecraft.ClaudeCraftConfig;
import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.agent.AgentService;
import k4k.claudecraft.agent.PermissionRequests;
import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.mcp.McpTool;
import k4k.claudecraft.mcp.Schema;
import k4k.claudecraft.mcp.ToolCall;
import k4k.claudecraft.mcp.ToolResult;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tools for creating, selecting and removing agents.
 */
public final class AgentTools {
	private AgentTools() {
	}

	/**
	 * @param server MCP server to register the tools on
	 */
	public static void register(McpHttpServer server) {
		server.register(new McpTool(
			"agent_spawn",
			"Spawns a new agent and attaches this session to it. Without coordinates it appears at the host player's eyes, "
				+ "looking the same way. Several agents can work in one world; each session controls its own.",
			Schema.object()
				.string("name", "Unique agent name: 1-32 characters from A-Z, a-z, 0-9, '_' and '-'.", true)
				.number("x", "X coordinate (requires y and z).", false)
				.number("y", "Y coordinate of the agent's feet.", false)
				.number("z", "Z coordinate.", false)
				.number("yaw", "Horizontal rotation in degrees (0 = south, 90 = west, 180 = north, -90 = east).", false)
				.number("pitch", "Vertical rotation in degrees (-90 = up, 90 = down).", false)
				.string("dimension", "Dimension id, e.g. 'minecraft:the_nether' (default: the host player's).", false)
				.build(),
			AgentTools::spawn
		).needingRunningGame());
		server.register(new McpTool(
			"agent_attach",
			"Attaches this session to an existing agent, so tools act on it by default.",
			Schema.object().string("name", "Agent name.", true).build(),
			AgentTools::attach
		).needingRunningGame());
		server.register(new McpTool(
			"agent_list",
			"Lists the agents in the world with their positions, and which one this session is attached to.",
			Schema.object().build(),
			AgentTools::list
		));
		server.register(new McpTool(
			"agent_remove",
			"Removes an agent from the world.",
			Schema.object().string("name", "Agent name.", true).build(),
			AgentTools::remove
		).needingRunningGame());
		server.register(new McpTool(
			"agent_request_permission",
			"Changes the agent's permission level. Lowering applies immediately; raising asks the host in game chat "
				+ "and waits for their answer. Levels: 0 = no cheat commands, 1 = bypass spawn protection, "
				+ "2 = world editing (tp, give, fill, summon, gamerule...), 3 = player management (kick, ban, op), 4 = everything.",
			Schema.object()
				.integer("level", "Requested permission level, 0-4.", true)
				.string("reason", "Why the agent needs it; shown to the host. Required when raising.", false)
				.string("agent", "Agent to use instead of the session's attached agent.", false)
				.build(),
			AgentTools::requestPermission
		));
	}

	/**
	 * Implements {@code agent_spawn}.
	 */
	private static ToolResult spawn(ToolCall call) throws Exception {
		String name = call.string("name");
		Double x = call.optDouble("x");
		Double y = call.optDouble("y");
		Double z = call.optDouble("z");
		Double yaw = call.optDouble("yaw");
		Double pitch = call.optDouble("pitch");
		String dimension = call.optString("dimension", null);
		if ((x == null || y == null || z == null) && (x != null || y != null || z != null || dimension != null)) {
			throw new ClaudeCraftException("Pass all of x, y and z (and optionally dimension), or none of them.");
		}
		MinecraftServer server = GameAccess.server();
		AgentEntity agent = GameAccess.onServer(server, () -> {
			ServerPlayerEntity player = GameAccess.hostPlayer(server);
			ServerWorld world;
			Vec3d pos;
			if (x != null) {
				world = dimension == null ? (player != null ? player.getServerWorld() : server.getOverworld()) : world(server, dimension);
				pos = new Vec3d(x, y, z);
			} else {
				if (player == null) {
					throw new ClaudeCraftException("The host player is not in the world; pass coordinates.");
				}
				world = player.getServerWorld();
				// Put the agent's eyes where the player's eyes are
				pos = player.getEyePos().subtract(0, AgentEntity.EYE_HEIGHT, 0);
			}
			float agentYaw = yaw != null ? yaw.floatValue() : (player != null ? player.getYaw() : 0);
			float agentPitch = pitch != null ? pitch.floatValue() : (player != null ? player.getPitch() : 0);
			return AgentService.spawn(world, name, pos, agentYaw, agentPitch);
		});
		call.session().setAttachedAgent(name);
		return ToolResult.text("Spawned agent '" + name + "' at " + describe(agent) + " and attached this session to it.");
	}

	/**
	 * Implements {@code agent_attach}.
	 */
	private static ToolResult attach(ToolCall call) throws Exception {
		String name = call.string("name");
		MinecraftServer server = GameAccess.server();
		GameAccess.ensureAgentLoaded(server, name);
		String where = GameAccess.onServer(server, () -> describe(GameAccess.loadedAgent(server, name)));
		call.session().setAttachedAgent(name);
		return ToolResult.text("Attached to agent '" + name + "' at " + where + ".");
	}

	/**
	 * Implements {@code agent_list}.
	 */
	private static ToolResult list(ToolCall call) throws Exception {
		MinecraftServer server = GameAccess.server();
		List<AgentService.AgentInfo> agents = GameAccess.onServer(server, () -> AgentService.list(server));
		List<PermissionRequests.Request> pending = GameAccess.onServer(server, () -> PermissionRequests.pending(null));
		if (agents.isEmpty()) {
			return ToolResult.text("No agents in this world. Create one with agent_spawn.");
		}
		String attached = call.session().getAttachedAgent();
		StringBuilder text = new StringBuilder();
		for (AgentService.AgentInfo info : agents) {
			text.append(info.name()).append(" | ").append(info.entry().dimension().getValue()).append(" | ");
			if (info.position() != null) {
				text.append(String.format(Locale.ROOT, "%.1f %.1f %.1f", info.position().x, info.position().y, info.position().z));
			} else {
				text.append("chunk ").append(info.entry().chunk().x).append(" ").append(info.entry().chunk().z).append(" (not loaded)");
			}
			text.append(" | permission level ").append(info.permissionLevel());
			for (PermissionRequests.Request request : pending) {
				if (request.agentName().equals(info.name())) {
					text.append(" (level ").append(request.level()).append(" requested, waiting for the host)");
				}
			}
			if (info.name().equals(attached)) {
				text.append(" | attached to this session");
			}
			text.append("\n");
		}
		return ToolResult.text(text.toString().stripTrailing());
	}

	/**
	 * Implements {@code agent_remove}.
	 */
	private static ToolResult remove(ToolCall call) throws Exception {
		String name = call.string("name");
		MinecraftServer server = GameAccess.server();
		String message;
		try {
			GameAccess.ensureAgentLoaded(server, name);
			GameAccess.onServer(server, () -> {
				GameAccess.loadedAgent(server, name).discard();
				return null;
			});
			message = "Removed agent '" + name + "'.";
		} catch (GameAccess.AgentNotLoadedException e) {
			GameAccess.onServer(server, () -> AgentService.forget(server, name));
			message = "Agent '" + name + "' could not be loaded, so it was only forgotten; if its entity still exists, it will reappear when its chunk is loaded.";
		}
		if (name.equals(call.session().getAttachedAgent())) {
			call.session().setAttachedAgent(null);
		}
		return ToolResult.text(message);
	}

	/**
	 * Implements {@code agent_request_permission}.
	 */
	private static ToolResult requestPermission(ToolCall call) throws Exception {
		int level = call.optInt("level", -1);
		if (level < 0 || level > 4) {
			throw new ClaudeCraftException("level must be between 0 and 4.");
		}
		String reason = call.optString("reason", "");
		String name = GameAccess.agentName(call);
		MinecraftServer server = GameAccess.server();
		int current = GameAccess.onServer(server, () -> {
			AgentService.find(server, name);
			return AgentService.getPermissionLevel(server, name);
		});
		if (level == current) {
			return ToolResult.text("Agent '" + name + "' already has permission level " + level + ".");
		}
		if (level < current) {
			GameAccess.onServer(server, () -> {
				AgentService.setPermissionLevel(server, name, level);
				return null;
			});
			return ToolResult.text("Lowered agent '" + name + "' to permission level " + level + ".");
		}
		if (reason.isBlank()) {
			throw new ClaudeCraftException("Explain to the host why the agent needs level " + level + " in 'reason'.");
		}

		PermissionRequests.Request request = GameAccess.onServer(server, () -> PermissionRequests.create(server, name, level, reason));
		try {
			boolean approved = request.decision().get(ClaudeCraftConfig.get().permissionRequestTimeoutSeconds, TimeUnit.SECONDS);
			return approved
				? ToolResult.text("The host granted agent '" + name + "' permission level " + level + ".")
				: ToolResult.error("The host denied permission level " + level + " for agent '" + name + "'.");
		} catch (TimeoutException e) {
			return ToolResult.text("The host has not answered yet (request #" + request.id() + "). "
				+ "They can still approve it later; agent_list shows the agent's current level and pending requests.");
		}
	}

	/**
	 * @return the world with the given dimension id
	 */
	private static ServerWorld world(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		ServerWorld world = id == null ? null : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
		if (world == null) {
			throw new ClaudeCraftException("Unknown dimension '" + dimension + "'. Use list_registry_entries with registry 'minecraft:dimension'.");
		}
		return world;
	}

	/**
	 * @return "x y z in dimension, yaw/pitch" for an agent
	 */
	static String describe(AgentEntity agent) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f in %s (yaw %.0f, pitch %.0f)",
			agent.getX(), agent.getY(), agent.getZ(), agent.getWorld().getRegistryKey().getValue(), agent.getYaw(), agent.getPitch());
	}
}

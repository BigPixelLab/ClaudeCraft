package k4k.claudecraft.agent;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.ClaudeCraftConfig;
import k4k.claudecraft.ClaudeCraftException;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Server-side agent management: spawning, lookup by name, removal and chunk loading.
 * Every method must be called on the server thread.
 */
public final class AgentService {
	/** Ticket that keeps an agent's chunk entity-ticking; it expires unless the agent refreshes it. */
	public static final ChunkTicketType<ChunkPos> AGENT_TICKET =
		ChunkTicketType.create("claudecraft_agent", Comparator.comparingLong(ChunkPos::toLong), 100);
	/** Radius 2 gives the agent's own chunk the entity-ticking level, like {@code /forceload}. */
	private static final int TICKET_RADIUS = 2;
	private static final int TICKET_REFRESH_INTERVAL = 20;
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

	private AgentService() {
	}

	/**
	 * Snapshot of an agent for listings.
	 *
	 * @param name      agent name
	 * @param entry     last known location
	 * @param position        exact position if the agent is loaded, otherwise {@code null}
	 * @param permissionLevel effective permission level
	 */
	public record AgentInfo(String name, AgentIndex.Entry entry, @Nullable Vec3d position, int permissionLevel) {
	}

	/**
	 * Spawns a new agent.
	 *
	 * @param world target world
	 * @param name  unique agent name
	 * @param pos   feet position
	 * @param yaw   horizontal rotation in degrees
	 * @param pitch vertical rotation in degrees
	 * @return the spawned agent
	 */
	public static AgentEntity spawn(ServerWorld world, String name, Vec3d pos, float yaw, float pitch) {
		MinecraftServer server = world.getServer();
		AgentIndex index = AgentIndex.get(server);
		if (!NAME_PATTERN.matcher(name).matches()) {
			throw new ClaudeCraftException("Invalid agent name '" + name + "': use 1-32 characters from A-Z, a-z, 0-9, '_' and '-'.");
		}
		if (index.get(name) != null) {
			throw new ClaudeCraftException("An agent named '" + name + "' already exists. Use agent_attach to control it.");
		}
		int max = ClaudeCraftConfig.get().maxAgents;
		if (index.all().size() >= max) {
			throw new ClaudeCraftException("Agent limit reached (" + max + "). Remove an agent or raise maxAgents in config/claudecraft.json.");
		}
		AgentEntity agent = ClaudeCraft.AGENT.create(world);
		if (agent == null) {
			throw new ClaudeCraftException("Failed to create the agent entity.");
		}
		agent.setAgentName(name);
		agent.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, pitch);
		agent.setHeadYaw(yaw);
		world.spawnEntity(agent);
		register(agent, world, index);
		return agent;
	}

	/**
	 * Finds a loaded agent by name. If the agent is known but its chunk is unloaded, requests loading it
	 * and returns {@code null}; callers may retry a few ticks later.
	 *
	 * @param server running server
	 * @param name   agent name
	 * @return the agent, or {@code null} if it is still loading
	 * @throws ClaudeCraftException if no agent with this name exists
	 */
	@Nullable
	public static AgentEntity find(MinecraftServer server, String name) {
		AgentIndex.Entry entry = AgentIndex.get(server).get(name);
		if (entry == null) {
			throw new ClaudeCraftException("No agent named '" + name + "'. Known agents: " + describeNames(server) + ".");
		}
		ServerWorld world = server.getWorld(entry.dimension());
		if (world == null) {
			throw new ClaudeCraftException("Agent '" + name + "' is in a dimension that no longer exists: " + entry.dimension().getValue());
		}
		Entity entity = world.getEntity(entry.uuid());
		if (entity instanceof AgentEntity agent && !agent.isRemoved()) {
			// An agent in a loaded but non-ticking chunk never updates the index itself
			onAgentMoved(agent, world);
			return agent;
		}
		world.getChunkManager().addTicket(AGENT_TICKET, entry.chunk(), TICKET_RADIUS, entry.chunk());
		return null;
	}

	/**
	 * @param server running server
	 * @param name   agent name
	 * @return the agent's effective permission level (granted or default)
	 */
	public static int getPermissionLevel(MinecraftServer server, String name) {
		Integer granted = AgentIndex.get(server).getPermissionLevel(name);
		return granted != null ? granted : defaultPermissionLevel();
	}

	/**
	 * @return the configured default level, clamped to the valid range
	 */
	public static int defaultPermissionLevel() {
		return Math.max(0, Math.min(4, ClaudeCraftConfig.get().defaultPermissionLevel));
	}

	/**
	 * @param server running server
	 * @param name   agent name
	 * @param level  new level (0-4), or {@code null} to return to the default
	 * @throws ClaudeCraftException if the agent is unknown
	 */
	public static void setPermissionLevel(MinecraftServer server, String name, @Nullable Integer level) {
		AgentIndex index = AgentIndex.get(server);
		if (index.get(name) == null) {
			throw new ClaudeCraftException("No agent named '" + name + "'.");
		}
		index.setPermissionLevel(name, level);
	}

	/**
	 * Drops an agent from the index without touching its entity, for agents that cannot be loaded anymore.
	 * If the entity still exists somewhere, it registers itself again the next time it ticks.
	 *
	 * @param server running server
	 * @param name   agent name
	 * @return whether the agent was known
	 */
	public static boolean forget(MinecraftServer server, String name) {
		AgentIndex index = AgentIndex.get(server);
		boolean known = index.get(name) != null;
		index.remove(name);
		return known;
	}

	/**
	 * @param server running server
	 * @return all known agents
	 */
	public static List<AgentInfo> list(MinecraftServer server) {
		List<AgentInfo> result = new ArrayList<>();
		for (Map.Entry<String, AgentIndex.Entry> e : AgentIndex.get(server).all().entrySet()) {
			ServerWorld world = server.getWorld(e.getValue().dimension());
			Entity entity = world == null ? null : world.getEntity(e.getValue().uuid());
			result.add(new AgentInfo(e.getKey(), e.getValue(), entity == null ? null : entity.getPos(), getPermissionLevel(server, e.getKey())));
		}
		return result;
	}

	/**
	 * @param server running server
	 * @return comma-separated agent names, or "none"
	 */
	public static String describeNames(MinecraftServer server) {
		var names = AgentIndex.get(server).all().keySet();
		return names.isEmpty() ? "none" : String.join(", ", names);
	}

	/**
	 * Re-requests chunk loading for every known agent after a world is opened, since chunk tickets are not saved.
	 *
	 * @param server server that just started
	 */
	public static void onServerStarted(MinecraftServer server) {
		for (AgentIndex.Entry entry : AgentIndex.get(server).all().values()) {
			ServerWorld world = server.getWorld(entry.dimension());
			if (world != null) {
				world.getChunkManager().addTicket(AGENT_TICKET, entry.chunk(), TICKET_RADIUS, entry.chunk());
			}
		}
	}

	/**
	 * Called every server tick by each agent: assigns a name to agents created with {@code /summon},
	 * keeps the index up to date and refreshes the chunk ticket.
	 *
	 * @param agent ticking agent
	 * @param world its world
	 */
	static void onAgentTick(AgentEntity agent, ServerWorld world) {
		AgentIndex index = AgentIndex.get(world.getServer());
		String name = agent.getAgentName();
		AgentIndex.Entry entry = name == null ? null : index.get(name);
		if (name == null || (entry != null && !entry.uuid().equals(agent.getUuid()))) {
			// Summoned without a name, or a copy of an existing agent (e.g. /summon with its NBT): give it a fresh name
			String base = agent.hasCustomName() && name == null ? agent.getCustomName().getString() : (name == null ? "agent" : name);
			agent.setAgentName(uniqueName(index, NAME_PATTERN.matcher(base).matches() ? base : "agent"));
			entry = null;
		}
		ChunkPos chunk = agent.getChunkPos();
		boolean moved = entry == null || !entry.chunk().equals(chunk) || !entry.dimension().equals(world.getRegistryKey());
		if (moved) {
			register(agent, world, index);
		} else if (world.getTime() % TICKET_REFRESH_INTERVAL == 0) {
			world.getChunkManager().addTicket(AGENT_TICKET, chunk, TICKET_RADIUS, chunk);
		}
	}

	/**
	 * Records an agent's new location after a teleport and loads the chunk it landed in.
	 *
	 * @param agent teleported agent, possibly not added to its world yet (cross-dimension copy)
	 * @param world its new world
	 */
	static void onAgentMoved(AgentEntity agent, ServerWorld world) {
		AgentIndex index = AgentIndex.get(world.getServer());
		AgentIndex.Entry entry = index.get(agent.getAgentName());
		// Only the agent that owns the name may move the entry; a copy with a clashing name is renamed on its first tick
		if (entry != null && entry.uuid().equals(agent.getUuid())
			&& (!entry.chunk().equals(agent.getChunkPos()) || !entry.dimension().equals(world.getRegistryKey()))) {
			register(agent, world, index);
		}
	}

	/**
	 * Forgets an agent that was killed or discarded.
	 *
	 * @param agent  removed agent
	 * @param server running server
	 */
	static void onAgentDestroyed(AgentEntity agent, MinecraftServer server) {
		String name = agent.getAgentName();
		if (name == null) {
			return;
		}
		AgentIndex index = AgentIndex.get(server);
		AgentIndex.Entry entry = index.get(name);
		if (entry != null && entry.uuid().equals(agent.getUuid())) {
			index.remove(name);
		}
	}

	/**
	 * Records the agent's current location and loads its chunk.
	 */
	private static void register(AgentEntity agent, ServerWorld world, AgentIndex index) {
		ChunkPos chunk = agent.getChunkPos();
		index.put(agent.getAgentName(), new AgentIndex.Entry(agent.getUuid(), world.getRegistryKey(), chunk));
		world.getChunkManager().addTicket(AGENT_TICKET, chunk, TICKET_RADIUS, chunk);
	}

	/**
	 * @return {@code base} if it is free, otherwise {@code base-2}, {@code base-3}, ...
	 */
	private static String uniqueName(AgentIndex index, String base) {
		if (index.get(base) == null) {
			return base;
		}
		for (int i = 2; ; i++) {
			String candidate = base + "-" + i;
			if (index.get(candidate) == null) {
				return candidate;
			}
		}
	}
}

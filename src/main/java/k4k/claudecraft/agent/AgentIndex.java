package k4k.claudecraft.agent;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved index of agents by name. Entities in unloaded chunks cannot be found by iterating the world,
 * so the index remembers where each agent is to load its chunk back on demand. It also holds granted
 * permission levels: entity NBT is editable with {@code /data}, so an agent could raise its own level there.
 */
public final class AgentIndex extends PersistentState {
	private static final String STATE_ID = "claudecraft_agents";

	/**
	 * Last known location of an agent.
	 *
	 * @param uuid      entity UUID
	 * @param dimension world the agent is in
	 * @param chunk     chunk the agent is in
	 */
	public record Entry(UUID uuid, RegistryKey<World> dimension, ChunkPos chunk) {
	}

	private final Map<String, Entry> entries = new LinkedHashMap<>();
	/** Permission levels granted by the host; agents without an entry use the configured default. */
	private final Map<String, Integer> permissionLevels = new HashMap<>();

	/**
	 * @param server running server
	 * @return the index stored with the overworld
	 */
	public static AgentIndex get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(AgentIndex::fromNbt, AgentIndex::new, STATE_ID);
	}

	/**
	 * @param name agent name
	 * @return its entry, or {@code null} if no such agent is known
	 */
	@Nullable
	public Entry get(String name) {
		return entries.get(name);
	}

	/**
	 * @return all agents by name, in registration order
	 */
	public Map<String, Entry> all() {
		return Collections.unmodifiableMap(entries);
	}

	/**
	 * @param name  agent name
	 * @param entry new location
	 */
	public void put(String name, Entry entry) {
		entries.put(name, entry);
		markDirty();
	}

	/**
	 * @param name agent name to forget
	 */
	public void remove(String name) {
		permissionLevels.remove(name);
		if (entries.remove(name) != null) {
			markDirty();
		}
	}

	/**
	 * @param name agent name
	 * @return the level granted to the agent, or {@code null} if it uses the default
	 */
	@Nullable
	public Integer getPermissionLevel(String name) {
		return permissionLevels.get(name);
	}

	/**
	 * @param name  agent name
	 * @param level granted level, or {@code null} to fall back to the default
	 */
	public void setPermissionLevel(String name, @Nullable Integer level) {
		if (level == null) {
			permissionLevels.remove(name);
		} else {
			permissionLevels.put(name, level);
		}
		markDirty();
	}

	/**
	 * @param nbt compound to write into
	 * @return the same compound
	 */
	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtCompound agents = new NbtCompound();
		entries.forEach((name, entry) -> {
			NbtCompound tag = new NbtCompound();
			tag.putUuid("uuid", entry.uuid());
			tag.putString("dimension", entry.dimension().getValue().toString());
			tag.putLong("chunk", entry.chunk().toLong());
			Integer level = permissionLevels.get(name);
			if (level != null) {
				tag.putInt("permissionLevel", level);
			}
			agents.put(name, tag);
		});
		nbt.put("agents", agents);
		return nbt;
	}

	/**
	 * @param nbt compound previously produced by {@link #writeNbt}
	 * @return the restored index
	 */
	private static AgentIndex fromNbt(NbtCompound nbt) {
		AgentIndex index = new AgentIndex();
		NbtCompound agents = nbt.getCompound("agents");
		for (String name : agents.getKeys()) {
			NbtCompound tag = agents.getCompound(name);
			Identifier dimension = Identifier.tryParse(tag.getString("dimension"));
			if (dimension == null || !tag.containsUuid("uuid")) {
				continue;
			}
			index.entries.put(name, new Entry(
				tag.getUuid("uuid"),
				RegistryKey.of(RegistryKeys.WORLD, dimension),
				new ChunkPos(tag.getLong("chunk"))
			));
			if (tag.contains("permissionLevel", NbtElement.INT_TYPE)) {
				index.permissionLevels.put(name, tag.getInt("permissionLevel"));
			}
		}
		return index;
	}
}

package k4k.claudecraft.note;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.agent.AgentIndex;
import k4k.claudecraft.item.QuillData;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creating, deleting and handing out notes. Server thread only.
 */
public final class NoteService {
	/** Longest side of a selectable area. */
	public static final int MAX_AREA_SIDE = 128;
	private static final int MAX_NOTES = 500;
	/** Areas up to this volume get a block breakdown in {@code take_notes}. */
	private static final int MAX_DESCRIBED_VOLUME = 32768;
	private static final int MAX_LISTED_BLOCK_TYPES = 20;

	private NoteService() {
	}

	/**
	 * Creates a note from the selection of the quill the player holds and clears the selection.
	 *
	 * @param player author
	 * @param rawText note text
	 */
	public static void create(ServerPlayerEntity player, String rawText) {
		String text = rawText.strip();
		ItemStack quill = heldQuill(player);
		if (quill == null) {
			return;
		}
		MinecraftServer server = player.getServer();
		String agent = QuillData.getAgent(quill);
		RegistryKey<World> dimension = QuillData.getDimension(quill);
		if (agent == null) {
			player.sendMessage(Text.translatable("claudecraft.quill.need_agent").formatted(Formatting.RED), true);
			return;
		}
		if (AgentIndex.get(server).get(agent) == null) {
			player.sendMessage(Text.translatable("claudecraft.note.unknown_agent", agent).formatted(Formatting.RED), true);
			return;
		}
		if (dimension == null || !QuillData.hasSelection(quill)) {
			player.sendMessage(Text.translatable("claudecraft.quill.need_selection").formatted(Formatting.RED), true);
			return;
		}
		if (text.isEmpty()) {
			player.sendMessage(Text.translatable("claudecraft.note.empty").formatted(Formatting.RED), true);
			return;
		}
		NoteStore store = NoteStore.get(server);
		if (store.all().size() >= MAX_NOTES) {
			player.sendMessage(Text.translatable("claudecraft.note.limit").formatted(Formatting.RED), true);
			return;
		}

		BlockBox area = QuillData.getArea(quill);
		Note.EntityTarget target = null;
		if (area == null) {
			QuillData.SelectedEntity selected = QuillData.getEntity(quill);
			ServerWorld world = server.getWorld(dimension);
			Entity entity = world == null ? null : world.getEntity(selected.uuid());
			if (entity == null) {
				player.sendMessage(Text.translatable("claudecraft.note.entity_gone").formatted(Formatting.RED), true);
				return;
			}
			target = new Note.EntityTarget(entity.getUuid(), EntityType.getId(entity.getType()), entity.getName().getString(), entity.getPos());
		} else if (Math.max(area.getBlockCountX(), Math.max(area.getBlockCountY(), area.getBlockCountZ())) > MAX_AREA_SIDE) {
			player.sendMessage(Text.translatable("claudecraft.quill.too_large", MAX_AREA_SIDE).formatted(Formatting.RED), true);
			return;
		}

		Note note = new Note(store.nextId(), agent, player.getUuid(), player.getName().getString(), System.currentTimeMillis(),
			dimension, area, target, text);
		store.add(note);
		QuillData.clearSelection(quill);
		player.sendMessage(Text.translatable("claudecraft.note.created", note.id(), agent).formatted(Formatting.GREEN), true);
		NoteNetworking.syncAll(server);
	}

	/**
	 * Deletes the newest note whose area contains the block, if the player may delete it.
	 *
	 * @param player who asks
	 * @param pos    clicked block
	 * @return whether a note was found at the block
	 */
	public static boolean deleteAt(ServerPlayerEntity player, BlockPos pos) {
		RegistryKey<World> dimension = player.getWorld().getRegistryKey();
		Note found = null;
		for (Note note : NoteStore.get(player.getServer()).all()) {
			if (note.area() != null && note.dimension().equals(dimension) && note.area().contains(pos)) {
				found = note;
			}
		}
		return found != null && delete(player, found);
	}

	/**
	 * Deletes the newest note about the entity, if the player may delete it.
	 *
	 * @param player who asks
	 * @param entity clicked entity
	 * @return whether a note about the entity was found
	 */
	public static boolean deleteFor(ServerPlayerEntity player, Entity entity) {
		Note found = null;
		for (Note note : NoteStore.get(player.getServer()).all()) {
			if (note.entity() != null && note.entity().uuid().equals(entity.getUuid())) {
				found = note;
			}
		}
		return found != null && delete(player, found);
	}

	/**
	 * Reserves every note for the agent so it can be handed out; see {@link #commit} and {@link #release}.
	 *
	 * @param server running server
	 * @param agent  agent name
	 * @return reserved notes, oldest first
	 */
	public static List<Note> reserve(MinecraftServer server, String agent) {
		NoteStore store = NoteStore.get(server);
		List<Note> result = new ArrayList<>();
		for (Note note : store.all()) {
			if (note.agent().equals(agent) && !store.isReserved(note.id())) {
				store.setReserved(note.id(), true);
				result.add(note);
			}
		}
		return result;
	}

	/**
	 * Deletes notes that were delivered.
	 *
	 * @param server running server
	 * @param ids    delivered note ids
	 */
	public static void commit(MinecraftServer server, List<Integer> ids) {
		NoteStore store = NoteStore.get(server);
		ids.forEach(store::remove);
		NoteNetworking.syncAll(server);
	}

	/**
	 * Makes notes available again after a failed delivery.
	 *
	 * @param server running server
	 * @param ids    note ids
	 */
	public static void release(MinecraftServer server, List<Integer> ids) {
		NoteStore store = NoteStore.get(server);
		ids.forEach(id -> store.setReserved(id, false));
	}

	/**
	 * @param server running server
	 * @param agent  agent name
	 * @return notes for the agent that are not being handed out
	 */
	public static List<Note> pending(MinecraftServer server, String agent) {
		NoteStore store = NoteStore.get(server);
		return store.all().stream().filter(note -> note.agent().equals(agent) && !store.isReserved(note.id())).toList();
	}

	/**
	 * Describes a note for Claude, including the current state of its target.
	 *
	 * @param server running server
	 * @param note   note to describe
	 * @return multi-line description
	 */
	public static String describe(MinecraftServer server, Note note) {
		StringBuilder out = new StringBuilder();
		out.append("note #").append(note.id()).append(" from ").append(note.authorName())
			.append(" in ").append(note.dimension().getValue()).append("\n");
		ServerWorld world = server.getWorld(note.dimension());
		if (note.area() != null) {
			describeArea(out, world, note.area());
		} else {
			describeEntity(out, world, note.entity());
		}
		out.append("text: ").append(note.text().replace("\n", "\n  "));
		return out.toString();
	}

	/**
	 * Appends the area bounds and, for small loaded areas, what it consists of.
	 */
	private static void describeArea(StringBuilder out, @Nullable ServerWorld world, BlockBox area) {
		boolean single = area.getBlockCountX() == 1 && area.getBlockCountY() == 1 && area.getBlockCountZ() == 1;
		if (single) {
			out.append("target: block ").append(area.getMinX()).append(" ").append(area.getMinY()).append(" ").append(area.getMinZ());
		} else {
			out.append(String.format(Locale.ROOT, "target: area %d %d %d .. %d %d %d (%dx%dx%d)",
				area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ(),
				area.getBlockCountX(), area.getBlockCountY(), area.getBlockCountZ()));
		}
		out.append("\n");
		if (world == null || !isLoaded(world, area)) {
			out.append("blocks: not loaded right now\n");
			return;
		}
		if (single) {
			BlockPos pos = new BlockPos(area.getMinX(), area.getMinY(), area.getMinZ());
			out.append("block: ").append(BlockArgumentParser.stringifyBlockState(world.getBlockState(pos))).append("\n");
			return;
		}
		long volume = (long) area.getBlockCountX() * area.getBlockCountY() * area.getBlockCountZ();
		if (volume > MAX_DESCRIBED_VOLUME) {
			out.append("blocks: area too large to list\n");
			return;
		}
		Map<Identifier, Integer> counts = new HashMap<>();
		for (BlockPos pos : BlockPos.iterate(area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ())) {
			BlockState state = world.getBlockState(pos);
			counts.merge(Registries.BLOCK.getId(state.getBlock()), 1, Integer::sum);
		}
		out.append("blocks:");
		counts.entrySet().stream()
			.sorted(Map.Entry.<Identifier, Integer>comparingByValue(Comparator.reverseOrder()))
			.limit(MAX_LISTED_BLOCK_TYPES)
			.forEach(e -> out.append(" ").append(e.getKey()).append(" x").append(e.getValue()).append(","));
		out.setLength(out.length() - 1);
		if (counts.size() > MAX_LISTED_BLOCK_TYPES) {
			out.append(" and ").append(counts.size() - MAX_LISTED_BLOCK_TYPES).append(" more types");
		}
		out.append("\n");
	}

	/**
	 * Appends the entity and where it is now.
	 */
	private static void describeEntity(StringBuilder out, @Nullable ServerWorld world, Note.EntityTarget target) {
		out.append("target: entity ").append(target.type()).append(" '").append(target.name()).append("' uuid ").append(target.uuid()).append("\n");
		Entity entity = world == null ? null : world.getEntity(target.uuid());
		if (entity != null) {
			out.append(String.format(Locale.ROOT, "entity now at %.1f %.1f %.1f%n", entity.getX(), entity.getY(), entity.getZ()));
		} else {
			out.append(String.format(Locale.ROOT, "entity not loaded or gone; it was at %.1f %.1f %.1f%n",
				target.position().x, target.position().y, target.position().z));
		}
	}

	/**
	 * Removes a note if the player is its author or the host.
	 */
	private static boolean delete(ServerPlayerEntity player, Note note) {
		MinecraftServer server = player.getServer();
		if (!note.authorUuid().equals(player.getUuid()) && !server.isHost(player.getGameProfile())) {
			player.sendMessage(Text.translatable("claudecraft.note.not_author", note.id()).formatted(Formatting.RED), true);
			return true;
		}
		NoteStore.get(server).remove(note.id());
		player.sendMessage(Text.translatable("claudecraft.note.deleted", note.id()), true);
		NoteNetworking.syncAll(server);
		return true;
	}

	/**
	 * @return whether every chunk of the area is loaded, so reading it does not load or generate chunks
	 */
	private static boolean isLoaded(ServerWorld world, BlockBox area) {
		for (int x = ChunkSectionPos.getSectionCoord(area.getMinX()); x <= ChunkSectionPos.getSectionCoord(area.getMaxX()); x++) {
			for (int z = ChunkSectionPos.getSectionCoord(area.getMinZ()); z <= ChunkSectionPos.getSectionCoord(area.getMaxZ()); z++) {
				if (!world.isChunkLoaded(x, z)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * @return the quill in the player's main or off hand, or {@code null}
	 */
	@Nullable
	private static ItemStack heldQuill(PlayerEntity player) {
		for (Hand hand : Hand.values()) {
			ItemStack stack = player.getStackInHand(hand);
			if (stack.isOf(ClaudeCraft.QUILL)) {
				return stack;
			}
		}
		return null;
	}
}

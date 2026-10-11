package k4k.claudecraft.item;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Reads and writes the quill's state kept in its NBT: the bound agent and the current selection.
 * The server is the only writer; the client reads it to render the selection and to open the editor.
 */
public final class QuillData {
	private static final String AGENT = "Agent";
	private static final String SELECTION = "Selection";
	private static final String DIMENSION = "Dimension";
	private static final String FROM = "From";
	private static final String TO = "To";
	private static final String ENTITY = "Entity";
	private static final String ENTITY_NAME = "EntityName";

	private QuillData() {
	}

	/**
	 * A selected entity.
	 *
	 * @param uuid entity UUID
	 * @param name display name at selection time
	 */
	public record SelectedEntity(UUID uuid, String name) {
	}

	/**
	 * @param stack quill stack
	 * @return the bound agent's name, or {@code null}
	 */
	@Nullable
	public static String getAgent(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(AGENT, NbtElement.STRING_TYPE) ? nbt.getString(AGENT) : null;
	}

	/**
	 * @param stack quill stack
	 * @param agent agent to bind the quill to
	 */
	public static void setAgent(ItemStack stack, String agent) {
		stack.getOrCreateNbt().putString(AGENT, agent);
	}

	/**
	 * @param stack quill stack
	 * @return the selection's dimension, or {@code null} if nothing is selected
	 */
	@Nullable
	public static RegistryKey<World> getDimension(ItemStack stack) {
		NbtCompound selection = selection(stack);
		Identifier id = selection == null ? null : Identifier.tryParse(selection.getString(DIMENSION));
		return id == null ? null : RegistryKey.of(RegistryKeys.WORLD, id);
	}

	/**
	 * @param stack quill stack
	 * @return the selected blocks, or {@code null} if no blocks are selected
	 */
	@Nullable
	public static BlockBox getArea(ItemStack stack) {
		NbtCompound selection = selection(stack);
		if (selection == null || !selection.contains(FROM, NbtElement.COMPOUND_TYPE)) {
			return null;
		}
		BlockPos from = NbtHelper.toBlockPos(selection.getCompound(FROM));
		BlockPos to = selection.contains(TO, NbtElement.COMPOUND_TYPE) ? NbtHelper.toBlockPos(selection.getCompound(TO)) : from;
		return BlockBox.create(from, to);
	}

	/**
	 * @param stack quill stack
	 * @return the first corner, or {@code null}
	 */
	@Nullable
	public static BlockPos getFrom(ItemStack stack) {
		NbtCompound selection = selection(stack);
		return selection != null && selection.contains(FROM, NbtElement.COMPOUND_TYPE) ? NbtHelper.toBlockPos(selection.getCompound(FROM)) : null;
	}

	/**
	 * @param stack quill stack
	 * @return the selected entity, or {@code null}
	 */
	@Nullable
	public static SelectedEntity getEntity(ItemStack stack) {
		NbtCompound selection = selection(stack);
		if (selection == null || !selection.containsUuid(ENTITY)) {
			return null;
		}
		return new SelectedEntity(selection.getUuid(ENTITY), selection.getString(ENTITY_NAME));
	}

	/**
	 * @param stack quill stack
	 * @return whether blocks or an entity are selected
	 */
	public static boolean hasSelection(ItemStack stack) {
		return getArea(stack) != null || getEntity(stack) != null;
	}

	/**
	 * Starts a new block selection at {@code pos}.
	 *
	 * @param stack     quill stack
	 * @param dimension world of the block
	 * @param pos       first corner
	 */
	public static void setFrom(ItemStack stack, RegistryKey<World> dimension, BlockPos pos) {
		NbtCompound selection = newSelection(dimension);
		selection.put(FROM, NbtHelper.fromBlockPos(pos));
		stack.getOrCreateNbt().put(SELECTION, selection);
	}

	/**
	 * Sets the second corner; starts a new selection if there is no first corner in this dimension.
	 *
	 * @param stack     quill stack
	 * @param dimension world of the block
	 * @param pos       second corner
	 */
	public static void setTo(ItemStack stack, RegistryKey<World> dimension, BlockPos pos) {
		BlockPos from = getFrom(stack);
		if (from == null || !dimension.equals(getDimension(stack))) {
			setFrom(stack, dimension, pos);
			return;
		}
		NbtCompound selection = newSelection(dimension);
		selection.put(FROM, NbtHelper.fromBlockPos(from));
		selection.put(TO, NbtHelper.fromBlockPos(pos));
		stack.getOrCreateNbt().put(SELECTION, selection);
	}

	/**
	 * @param stack     quill stack
	 * @param dimension world of the entity
	 * @param entity    entity to select
	 */
	public static void setEntity(ItemStack stack, RegistryKey<World> dimension, SelectedEntity entity) {
		NbtCompound selection = newSelection(dimension);
		selection.putUuid(ENTITY, entity.uuid());
		selection.putString(ENTITY_NAME, entity.name());
		stack.getOrCreateNbt().put(SELECTION, selection);
	}

	/**
	 * @param stack quill stack whose selection to clear; the bound agent stays
	 */
	public static void clearSelection(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		if (nbt != null) {
			nbt.remove(SELECTION);
		}
	}

	/**
	 * @return the selection compound, or {@code null}
	 */
	@Nullable
	private static NbtCompound selection(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(SELECTION, NbtElement.COMPOUND_TYPE) ? nbt.getCompound(SELECTION) : null;
	}

	/**
	 * @return an empty selection compound for the dimension
	 */
	private static NbtCompound newSelection(RegistryKey<World> dimension) {
		NbtCompound selection = new NbtCompound();
		selection.putString(DIMENSION, dimension.getValue().toString());
		return selection;
	}
}

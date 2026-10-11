package k4k.claudecraft.note;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player's note for an agent: a text attached to a block area or an entity.
 * Exactly one of {@code area} and {@code entity} is set.
 *
 * @param id         world-unique number
 * @param agent      name of the agent the note is for
 * @param authorUuid author's UUID
 * @param authorName author's name when the note was written
 * @param createdAt  creation time, epoch milliseconds
 * @param dimension  world the target is in
 * @param area       selected blocks (inclusive bounds), or {@code null} for an entity note
 * @param entity     selected entity, or {@code null} for an area note
 * @param text       what the author wrote
 */
public record Note(
	int id,
	String agent,
	UUID authorUuid,
	String authorName,
	long createdAt,
	RegistryKey<World> dimension,
	@Nullable BlockBox area,
	@Nullable EntityTarget entity,
	String text
) {
	/**
	 * The entity a note points at, as it was when the note was written.
	 *
	 * @param uuid     entity UUID
	 * @param type     entity type id
	 * @param name     display name
	 * @param position position at creation time
	 */
	public record EntityTarget(UUID uuid, Identifier type, String name, Vec3d position) {
	}

	/**
	 * @return the note as NBT
	 */
	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("id", id);
		nbt.putString("agent", agent);
		nbt.putUuid("authorUuid", authorUuid);
		nbt.putString("authorName", authorName);
		nbt.putLong("createdAt", createdAt);
		nbt.putString("dimension", dimension.getValue().toString());
		if (area != null) {
			nbt.putIntArray("area", new int[]{area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ()});
		}
		if (entity != null) {
			NbtCompound target = new NbtCompound();
			target.putUuid("uuid", entity.uuid());
			target.putString("type", entity.type().toString());
			target.putString("name", entity.name());
			target.putDouble("x", entity.position().x);
			target.putDouble("y", entity.position().y);
			target.putDouble("z", entity.position().z);
			nbt.put("entity", target);
		}
		nbt.putString("text", text);
		return nbt;
	}

	/**
	 * @param nbt data produced by {@link #toNbt}
	 * @return the note, or {@code null} if the data is broken
	 */
	@Nullable
	public static Note fromNbt(NbtCompound nbt) {
		Identifier dimension = Identifier.tryParse(nbt.getString("dimension"));
		if (dimension == null || !nbt.containsUuid("authorUuid")) {
			return null;
		}
		BlockBox area = null;
		int[] bounds = nbt.getIntArray("area");
		if (bounds.length == 6) {
			area = new BlockBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
		}
		EntityTarget entity = null;
		if (nbt.contains("entity", NbtElement.COMPOUND_TYPE)) {
			NbtCompound target = nbt.getCompound("entity");
			Identifier type = Identifier.tryParse(target.getString("type"));
			if (type != null && target.containsUuid("uuid")) {
				entity = new EntityTarget(target.getUuid("uuid"), type, target.getString("name"),
					new Vec3d(target.getDouble("x"), target.getDouble("y"), target.getDouble("z")));
			}
		}
		if ((area == null) == (entity == null)) {
			return null;
		}
		return new Note(
			nbt.getInt("id"),
			nbt.getString("agent"),
			nbt.getUuid("authorUuid"),
			nbt.getString("authorName"),
			nbt.getLong("createdAt"),
			RegistryKey.of(RegistryKeys.WORLD, dimension),
			area,
			entity,
			nbt.getString("text")
		);
	}
}

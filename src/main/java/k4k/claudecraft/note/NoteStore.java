package k4k.claudecraft.note;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * World-saved notes. Notes handed out by {@code take_notes} are reserved until the MCP response is delivered,
 * so a parallel call cannot hand them out twice; reservations are not saved.
 */
public final class NoteStore extends PersistentState {
	private static final String STATE_ID = "claudecraft_notes";

	private final Map<Integer, Note> notes = new LinkedHashMap<>();
	private final Set<Integer> reserved = new HashSet<>();
	private int nextId = 1;

	/**
	 * @param server running server
	 * @return the store saved with the overworld
	 */
	public static NoteStore get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(NoteStore::fromNbt, NoteStore::new, STATE_ID);
	}

	/**
	 * @return a fresh note id
	 */
	public int nextId() {
		markDirty();
		return nextId++;
	}

	/**
	 * @param note note to store
	 */
	public void add(Note note) {
		notes.put(note.id(), note);
		markDirty();
	}

	/**
	 * @param id note id
	 * @return the removed note, or {@code null}
	 */
	@Nullable
	public Note remove(int id) {
		reserved.remove(id);
		Note removed = notes.remove(id);
		if (removed != null) {
			markDirty();
		}
		return removed;
	}

	/**
	 * @return all notes in creation order
	 */
	public Collection<Note> all() {
		return Collections.unmodifiableCollection(notes.values());
	}

	/**
	 * @param id note id
	 * @return whether the note is being handed out right now
	 */
	public boolean isReserved(int id) {
		return reserved.contains(id);
	}

	/**
	 * @param id note id to reserve or release
	 * @param value new reservation state
	 */
	public void setReserved(int id, boolean value) {
		if (value) {
			reserved.add(id);
		} else {
			reserved.remove(id);
		}
	}

	/**
	 * @param nbt compound to write into
	 * @return the same compound
	 */
	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		notes.values().forEach(note -> list.add(note.toNbt()));
		nbt.put("notes", list);
		nbt.putInt("nextId", nextId);
		return nbt;
	}

	/**
	 * @param nbt compound produced by {@link #writeNbt}
	 * @return the restored store
	 */
	private static NoteStore fromNbt(NbtCompound nbt) {
		NoteStore store = new NoteStore();
		NbtList list = nbt.getList("notes", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			Note note = Note.fromNbt(list.getCompound(i));
			if (note != null) {
				store.notes.put(note.id(), note);
			}
		}
		store.nextId = Math.max(1, nbt.getInt("nextId"));
		return store;
	}
}

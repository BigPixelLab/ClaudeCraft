package k4k.claudecraft.note;

import k4k.claudecraft.ClaudeCraft;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Packets for notes: the client sends the text of a new note, the server sends every player
 * the list of notes to draw.
 */
public final class NoteNetworking {
	/** C2S: text of a note for the quill's current selection. */
	public static final Identifier CREATE_NOTE = ClaudeCraft.id("create_note");
	/** S2C: all notes, replacing the client's list. */
	public static final Identifier SYNC_NOTES = ClaudeCraft.id("sync_notes");
	/** Longest note text accepted from clients. */
	public static final int MAX_TEXT_LENGTH = 2000;
	private static final int LABEL_LENGTH = 60;

	private NoteNetworking() {
	}

	/**
	 * What clients need to draw a note.
	 *
	 * @param id             note id
	 * @param agent          addressed agent
	 * @param dimension      world of the target
	 * @param label          shortened text
	 * @param area           selected blocks, or {@code null}
	 * @param entity         selected entity, or {@code null}
	 * @param entityPosition where the entity was when the note was written
	 */
	public record NoteView(int id, String agent, RegistryKey<World> dimension, String label,
						   @Nullable BlockBox area, @Nullable UUID entity, @Nullable Vec3d entityPosition) {
	}

	/**
	 * Registers the server-side receiver.
	 */
	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(CREATE_NOTE, (server, player, handler, buf, sender) -> {
			String text = buf.readString(MAX_TEXT_LENGTH);
			server.execute(() -> NoteService.create(player, text));
		});
	}

	/**
	 * Sends the full list of notes to one player.
	 *
	 * @param player receiver
	 */
	public static void sync(ServerPlayerEntity player) {
		ServerPlayNetworking.send(player, SYNC_NOTES, encode(NoteStore.get(player.getServer()).all()));
	}

	/**
	 * Sends the full list of notes to every player.
	 *
	 * @param server running server
	 */
	public static void syncAll(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sync(player);
		}
	}

	/**
	 * @param buf packet produced by {@link #encode}
	 * @return the decoded notes
	 */
	public static List<NoteView> decode(PacketByteBuf buf) {
		int count = buf.readVarInt();
		List<NoteView> notes = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int id = buf.readVarInt();
			String agent = buf.readString();
			RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, buf.readIdentifier());
			String label = buf.readString();
			if (buf.readBoolean()) {
				BlockBox area = BlockBox.create(buf.readBlockPos(), buf.readBlockPos());
				notes.add(new NoteView(id, agent, dimension, label, area, null, null));
			} else {
				UUID entity = buf.readUuid();
				Vec3d position = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
				notes.add(new NoteView(id, agent, dimension, label, null, entity, position));
			}
		}
		return notes;
	}

	/**
	 * @return the notes as a sync packet body
	 */
	private static PacketByteBuf encode(Collection<Note> notes) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(notes.size());
		for (Note note : notes) {
			buf.writeVarInt(note.id());
			buf.writeString(note.agent());
			buf.writeIdentifier(note.dimension().getValue());
			String firstLine = note.text().lines().findFirst().orElse("");
			buf.writeString(firstLine.length() > LABEL_LENGTH ? firstLine.substring(0, LABEL_LENGTH) + "..." : firstLine);
			if (note.area() != null) {
				buf.writeBoolean(true);
				buf.writeBlockPos(new BlockPos(note.area().getMinX(), note.area().getMinY(), note.area().getMinZ()));
				buf.writeBlockPos(new BlockPos(note.area().getMaxX(), note.area().getMaxY(), note.area().getMaxZ()));
			} else {
				buf.writeBoolean(false);
				buf.writeUuid(note.entity().uuid());
				buf.writeDouble(note.entity().position().x);
				buf.writeDouble(note.entity().position().y);
				buf.writeDouble(note.entity().position().z);
			}
		}
		return buf;
	}
}

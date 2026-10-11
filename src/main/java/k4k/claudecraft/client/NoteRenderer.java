package k4k.claudecraft.client;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.item.QuillData;
import k4k.claudecraft.note.NoteNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Draws notes in the world as orange boxes with a label, and the held quill's selection as a cyan box.
 */
public final class NoteRenderer {
	private static final double LABEL_DISTANCE = 48;
	private static final float LABEL_SCALE = 0.025f;
	private static final int LABEL_COLOR = 0xFFFFA040;
	/** A quarter of {@link #LABEL_COLOR}, like vanilla text shadows. */
	private static final int LABEL_SHADOW_COLOR = 0xFF3F2810;
	/** Distance from a label to its shadow, away from the camera, in label units (1/40 of a block). */
	private static final float SHADOW_DEPTH = 1f;

	private static List<NoteNetworking.NoteView> notes = List.of();

	private NoteRenderer() {
	}

	/**
	 * @param synced notes received from the server; replaces the previous list
	 */
	public static void setNotes(List<NoteNetworking.NoteView> synced) {
		notes = List.copyOf(synced);
	}

	/**
	 * Renders boxes and labels; registered for {@code WorldRenderEvents.AFTER_ENTITIES}.
	 *
	 * @param context world render context
	 */
	public static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		VertexConsumerProvider consumers = context.consumers();
		if (client.world == null || client.player == null || consumers == null) {
			return;
		}
		MatrixStack matrices = context.matrixStack();
		Vec3d camera = context.camera().getPos();
		VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
		Map<UUID, Entity> entities = null;
		List<Label> labels = new ArrayList<>();

		for (NoteNetworking.NoteView note : notes) {
			if (!note.dimension().equals(client.world.getRegistryKey())) {
				continue;
			}
			Box box;
			if (note.area() != null) {
				box = Box.from(note.area());
			} else {
				if (entities == null) {
					entities = new HashMap<>();
					for (Entity entity : client.world.getEntities()) {
						entities.put(entity.getUuid(), entity);
					}
				}
				Entity entity = entities.get(note.entity());
				box = entity != null ? entity.getBoundingBox().expand(0.1) : Box.of(note.entityPosition(), 0.5, 0.5, 0.5);
			}
			box = box.expand(0.005);
			WorldRenderer.drawBox(matrices, lines, box.offset(camera.negate()), 1f, 0.55f, 0.1f, 1f);
			labels.add(new Label(new Vec3d(box.getCenter().x, box.maxY + 0.35, box.getCenter().z),
				Text.literal("#" + note.id() + " → " + note.agent() + ": " + note.label())));
		}

		Box selection = heldSelection(client);
		if (selection != null) {
			WorldRenderer.drawBox(matrices, lines, selection.expand(0.01).offset(camera.negate()), 0.2f, 0.9f, 1f, 1f);
		}

		// Labels go last: requesting the text layers flushes the line buffer
		for (Label label : labels) {
			if (label.position().squaredDistanceTo(camera) < LABEL_DISTANCE * LABEL_DISTANCE) {
				drawLabel(client.textRenderer, context, matrices, consumers, camera, label);
			}
		}
	}

	/**
	 * @return the box of the held quill's selection in the current dimension, or {@code null}
	 */
	@Nullable
	private static Box heldSelection(MinecraftClient client) {
		for (Hand hand : Hand.values()) {
			ItemStack stack = client.player.getStackInHand(hand);
			if (!stack.isOf(ClaudeCraft.QUILL) || !client.world.getRegistryKey().equals(QuillData.getDimension(stack))) {
				continue;
			}
			BlockBox area = QuillData.getArea(stack);
			if (area != null) {
				return Box.from(area);
			}
			QuillData.SelectedEntity selected = QuillData.getEntity(stack);
			if (selected != null) {
				for (Entity entity : client.world.getEntities()) {
					if (entity.getUuid().equals(selected.uuid())) {
						return entity.getBoundingBox().expand(0.1);
					}
				}
			}
		}
		return null;
	}

	/**
	 * Draws a camera-facing label with a text shadow in a single normal text pass.
	 * <p>
	 * A name-tag style label (see-through pass with a background, then a normal pass) breaks under shader packs:
	 * Iris reorders the text layers, so the background covers the text. One depth-tested pass renders correctly.
	 */
	private static void drawLabel(TextRenderer textRenderer, WorldRenderContext context, MatrixStack matrices,
								  VertexConsumerProvider consumers, Vec3d camera, Label label) {
		matrices.push();
		matrices.translate(label.position().x - camera.x, label.position().y - camera.y, label.position().z - camera.z);
		matrices.multiply(context.camera().getRotation());
		matrices.scale(-LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		float x = -textRenderer.getWidth(label.text()) / 2f;
		// The built-in shadow shifts the text by +z, which is towards the viewer only in GUI space; in the world +z points
		// away from the camera, so the shadow ended up in front of the text. Draw our own shadow behind it instead
		Matrix4f shadowMatrix = new Matrix4f(matrix).translate(0, 0, SHADOW_DEPTH);
		textRenderer.draw(label.text(), x + 1, 1, LABEL_SHADOW_COLOR, false, shadowMatrix, consumers,
			TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
		textRenderer.draw(label.text(), x, 0, LABEL_COLOR, false, matrix, consumers,
			TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
		matrices.pop();
	}

	/**
	 * A label to draw.
	 *
	 * @param position world position of the label's center
	 * @param text     label text
	 */
	private record Label(Vec3d position, Text text) {
	}
}

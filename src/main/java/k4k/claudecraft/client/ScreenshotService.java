package k4k.claudecraft.client;

import k4k.claudecraft.ClaudeCraft;
import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.mixin.client.CameraAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Takes screenshots from an agent's point of view by temporarily making a stand-in entity the client camera.
 * <p>
 * Rendering into a separate framebuffer would avoid the visible flicker, but shader packs (Iris) and Sodium
 * hook the main render path, so the capture reuses it: for a few frames the world is rendered from the agent,
 * then the main framebuffer is read right after the world pass, before HUD and screens are drawn.
 * All methods except {@link #request} run on the render thread.
 */
public final class ScreenshotService {
	/** Orange of the mod icon, used for the "camera taken" vignette. */
	private static final int INDICATOR_RGB = 0xEE8A3C;
	private static final float INDICATOR_MAX_ALPHA = 0.6f;
	private static final long INDICATOR_FADE_IN_MILLIS = 150;
	/** Sodium and vanilla rebuild the visible chunk list over a few frames after a camera jump. */
	private static final int MIN_WARMUP_FRAMES = 3;
	private static final Queue<Request> QUEUE = new ConcurrentLinkedQueue<>();

	@Nullable
	private static Request active;
	private static int framesRendered;
	private static long activeSince;
	private static long captureAfter;
	@Nullable
	private static Entity savedCamera;
	private static boolean savedHudHidden;
	private static Perspective savedPerspective = Perspective.FIRST_PERSON;
	private static float savedCameraY;
	private static float savedLastCameraY;
	@Nullable
	private static AgentEntity stand;

	private ScreenshotService() {
	}

	/**
	 * Where to take a screenshot from.
	 *
	 * @param agentUuid agent whose model must be hidden during the capture
	 * @param dimension world the agent is in
	 * @param position  agent feet position
	 * @param yaw       agent yaw
	 * @param pitch     agent pitch
	 */
	public record Pose(UUID agentUuid, RegistryKey<World> dimension, Vec3d position, float yaw, float pitch) {
	}

	/**
	 * A pending screenshot.
	 *
	 * @param pose          agent pose, or {@code null} to capture the player's own view
	 * @param fov           vertical field of view override in degrees, or {@code null} for the player's setting
	 * @param warmupMillis  how long to render from the pose before capturing
	 * @param withHud       capture the finished frame with HUD and screens instead of the bare world
	 * @param result        completed with the captured image, which the receiver must close
	 */
	private record Request(@Nullable Pose pose, @Nullable Double fov, int warmupMillis, boolean withHud, CompletableFuture<NativeImage> result) {
	}

	/**
	 * Queues a screenshot; it is taken during one of the next frames. Safe to call from any thread.
	 *
	 * @param pose         agent pose, or {@code null} for the player's view
	 * @param fov          field of view override, or {@code null}
	 * @param warmupMillis how long to render from the pose before capturing (at least {@value #MIN_WARMUP_FRAMES} frames)
	 * @param withHud      capture what the host sees, HUD and open screens included
	 * @return future image in the window's resolution; the receiver must close it
	 */
	public static CompletableFuture<NativeImage> request(@Nullable Pose pose, @Nullable Double fov, int warmupMillis, boolean withHud) {
		Request request = new Request(pose, fov, Math.max(0, warmupMillis), withHud, new CompletableFuture<>());
		QUEUE.add(request);
		return request.result();
	}

	/**
	 * Called at the start of every frame: starts the next queued capture by switching the camera to the agent.
	 *
	 * @param client game client
	 */
	public static void onFrameStart(MinecraftClient client) {
		if (active != null) {
			if (client.world == null) {
				finish(client, null, new ClaudeCraftException("The world was closed during the screenshot."));
			}
			return;
		}
		Request next = QUEUE.poll();
		if (next == null) {
			return;
		}
		try {
			begin(client, next);
		} catch (RuntimeException e) {
			next.result().completeExceptionally(e);
		}
	}

	/**
	 * Called right after the world has been rendered into the main framebuffer: captures it once warm-up is over.
	 *
	 * @param client game client
	 */
	public static void onWorldRendered(MinecraftClient client) {
		if (active != null && !active.withHud()) {
			capture(client);
		}
	}

	/**
	 * Called at the end of every frame, once HUD and screens are drawn: captures HUD screenshots.
	 *
	 * @param client game client
	 */
	public static void onFrameEnd(MinecraftClient client) {
		if (active != null && active.withHud()) {
			capture(client);
		}
	}

	/**
	 * Reads the main framebuffer once warm-up is over.
	 */
	private static void capture(MinecraftClient client) {
		if (++framesRendered < MIN_WARMUP_FRAMES || Util.getMeasuringTimeMs() < captureAfter) {
			return;
		}
		try {
			NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
			finish(client, image, null);
		} catch (RuntimeException e) {
			finish(client, null, e);
		}
	}

	/**
	 * @return the field of view to use instead of the regular one while capturing, or {@code null}
	 */
	@Nullable
	public static Double getFovOverride() {
		return active == null ? null : active.fov();
	}

	/**
	 * @return whether the camera is currently attached to an agent rather than the player
	 */
	public static boolean isCapturingFromAgent() {
		return active != null && active.pose() != null;
	}

	/**
	 * Draws a square orange vignette while the camera belongs to an agent, so the host knows why their view changed.
	 * It is drawn with the GUI, after the frame for the screenshot has already been captured, so it never shows up
	 * in screenshots.
	 *
	 * @param context GUI draw context of the current frame
	 */
	public static void renderIndicator(DrawContext context) {
		if (!isCapturingFromAgent()) {
			return;
		}
		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		int thickness = Math.max(12, Math.min(width, height) / 7);
		float fade = Math.min(1f, (Util.getMeasuringTimeMs() - activeSince) / (float) INDICATOR_FADE_IN_MILLIS);
		for (int i = 0; i < thickness; i++) {
			float falloff = 1f - (float) i / thickness;
			int alpha = Math.round(INDICATOR_MAX_ALPHA * falloff * falloff * fade * 255);
			if (alpha == 0) {
				continue;
			}
			int color = alpha << 24 | INDICATOR_RGB;
			// One-pixel frame at inset i; stacked frames with decreasing alpha form a square gradient
			context.fill(i, i, width - i, i + 1, color);
			context.fill(i, height - i - 1, width - i, height - i, color);
			context.fill(i, i + 1, i + 1, height - i - 1, color);
			context.fill(width - i - 1, i + 1, width - i, height - i - 1, color);
		}
	}

	/**
	 * Draws the host player while the camera belongs to an agent; registered for
	 * {@code WorldRenderEvents.AFTER_ENTITIES}. Vanilla never renders the local player unless the camera is
	 * attached to it, which would make the host invisible from the agent's point of view.
	 *
	 * @param context world render context
	 */
	public static void renderHostPlayer(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		VertexConsumerProvider consumers = context.consumers();
		if (!isCapturingFromAgent() || player == null || player.isSpectator() || consumers == null) {
			return;
		}
		float tickDelta = context.tickDelta();
		Vec3d camera = context.camera().getPos();
		EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
		dispatcher.render(
			player,
			MathHelper.lerp(tickDelta, player.lastRenderX, player.getX()) - camera.x,
			MathHelper.lerp(tickDelta, player.lastRenderY, player.getY()) - camera.y,
			MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ()) - camera.z,
			MathHelper.lerp(tickDelta, player.prevYaw, player.getYaw()),
			tickDelta,
			context.matrixStack(),
			consumers,
			dispatcher.getLight(player, tickDelta)
		);
	}

	/**
	 * @param uuid entity UUID
	 * @return whether the entity must not be rendered because the camera is inside it
	 */
	public static boolean isHidden(UUID uuid) {
		return active != null && active.pose() != null && active.pose().agentUuid().equals(uuid);
	}

	/**
	 * Validates the request and moves the camera to the agent.
	 */
	private static void begin(MinecraftClient client, Request request) {
		if (client.world == null || client.player == null) {
			throw new ClaudeCraftException("No world is loaded on the client.");
		}
		if (GLFW.glfwGetWindowAttrib(client.getWindow().getHandle(), GLFW.GLFW_ICONIFIED) != 0) {
			throw new ClaudeCraftException("The game window is minimized, so nothing is rendered. Restore the window and try again.");
		}
		Pose pose = request.pose();
		if (pose != null) {
			if (!client.world.getRegistryKey().equals(pose.dimension())) {
				throw new ClaudeCraftException("The agent is in " + pose.dimension().getValue() + " but the host player is in "
					+ client.world.getRegistryKey().getValue() + "; the host can only render its own dimension.");
			}
			int chunkX = ChunkSectionPos.getSectionCoord(pose.position().x);
			int chunkZ = ChunkSectionPos.getSectionCoord(pose.position().z);
			if (!client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
				double distance = Math.sqrt(client.player.squaredDistanceTo(pose.position()));
				throw new ClaudeCraftException(String.format(Locale.ROOT,
					"The agent is %.0f blocks from the host player, outside the area the host client has loaded "
						+ "(render distance %d chunks). Move the agent closer to the host player.",
					distance, client.options.getClampedViewDistance()));
			}
		}

		savedCamera = client.getCameraEntity();
		savedHudHidden = client.options.hudHidden;
		savedPerspective = client.options.getPerspective();
		CameraAccessor camera = (CameraAccessor) client.gameRenderer.getCamera();
		savedCameraY = camera.getCameraY();
		savedLastCameraY = camera.getLastCameraY();

		if (pose != null) {
			if (stand == null || stand.getWorld() != client.world) {
				stand = new AgentEntity(ClaudeCraft.AGENT, client.world);
			}
			stand.refreshPositionAndAngles(pose.position().x, pose.position().y, pose.position().z, pose.yaw(), pose.pitch());
			client.setCameraEntity(stand);
			// The camera eases its height towards the focused entity's eye height over several ticks; skip that
			camera.setCameraY(stand.getStandingEyeHeight());
			camera.setLastCameraY(stand.getStandingEyeHeight());
		}
		client.options.hudHidden = !request.withHud();
		client.options.setPerspective(Perspective.FIRST_PERSON);
		active = request;
		framesRendered = 0;
		activeSince = Util.getMeasuringTimeMs();
		captureAfter = Util.getMeasuringTimeMs() + request.warmupMillis();
	}

	/**
	 * Restores the player's camera and completes the active request.
	 */
	private static void finish(MinecraftClient client, @Nullable NativeImage image, @Nullable Throwable error) {
		Request request = active;
		active = null;
		if (request == null) {
			return;
		}
		if (request.pose() != null) {
			Entity restore = savedCamera != null && !savedCamera.isRemoved() ? savedCamera : client.player;
			client.setCameraEntity(restore);
			CameraAccessor camera = (CameraAccessor) client.gameRenderer.getCamera();
			camera.setCameraY(savedCameraY);
			camera.setLastCameraY(savedLastCameraY);
		}
		savedCamera = null;
		client.options.hudHidden = savedHudHidden;
		client.options.setPerspective(savedPerspective);
		if (image != null) {
			request.result().complete(image);
		} else {
			request.result().completeExceptionally(error);
		}
	}
}

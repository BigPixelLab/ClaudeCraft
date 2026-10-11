package k4k.claudecraft.tools;

import k4k.claudecraft.ClaudeCraftConfig;
import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.agent.AgentService;
import k4k.claudecraft.client.ImageEncoder;
import k4k.claudecraft.client.ScreenshotService;
import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.mcp.McpTool;
import k4k.claudecraft.mcp.Schema;
import k4k.claudecraft.mcp.ToolCall;
import k4k.claudecraft.mcp.ToolResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Locale;
import java.util.Base64;

/**
 * Tools for seeing the world: screenshots and a textual state summary.
 */
public final class ViewTools {
	private static final double LOOK_DISTANCE = 64;

	private ViewTools() {
	}

	/**
	 * @param server MCP server to register the tools on
	 */
	public static void register(McpHttpServer server) {
		server.register(new McpTool(
			"screenshot",
			"Takes a screenshot of what the agent sees (or the host player's view with from='player'), without HUD. "
				+ "The agent must be in the host player's dimension and within their render distance. "
				+ "The host sees the agent's view for about a second while it is captured.",
			Schema.object()
				.enumeration("from", "Whose view: 'agent' (default) or 'player'.", false, "agent", "player")
				.string("agent", "Agent to use instead of the session's attached agent.", false)
				.integer("width", "Image width in pixels; height follows the window aspect ratio (default from config).", false)
				.number("fov", "Vertical field of view in degrees (default: the player's FOV setting).", false)
				.enumeration("format", "Image format (default 'jpeg', smaller).", false, "jpeg", "png")
				.integer("warmup_ms", "How long to render from the agent before capturing (default from config, 1000); raise it if parts of the world are missing.", false)
				.build(),
			ViewTools::screenshot
		).needingRunningGame());
		server.register(new McpTool(
			"get_state",
			"Describes the current situation: the agent's position, rotation and the block it looks at, "
				+ "the host player's position, time, weather and whether the game is paused.",
			Schema.object().string("agent", "Agent to use instead of the session's attached agent.", false).build(),
			ViewTools::state
		).needingRunningGame());
	}

	/**
	 * Implements {@code screenshot}.
	 */
	private static ToolResult screenshot(ToolCall call) throws Exception {
		ClaudeCraftConfig config = ClaudeCraftConfig.get();
		boolean fromPlayer = "player".equals(call.optString("from", "agent"));
		int width = call.optInt("width", config.screenshotWidth);
		Double fov = call.optDouble("fov");
		boolean png = "png".equals(call.optString("format", "jpeg"));
		int warmup = call.optInt("warmup_ms", config.screenshotWarmupMillis);
		if (fov != null && (fov < 10 || fov > 170)) {
			throw new ClaudeCraftException("fov must be between 10 and 170 degrees.");
		}

		ScreenshotService.Pose pose = null;
		String caption = "host player's view";
		if (!fromPlayer) {
			String name = GameAccess.agentName(call);
			MinecraftServer server = GameAccess.server();
			GameAccess.ensureAgentLoaded(server, name);
			pose = GameAccess.onServer(server, () -> {
				AgentEntity agent = GameAccess.loadedAgent(server, name);
				return new ScreenshotService.Pose(agent.getUuid(), agent.getWorld().getRegistryKey(), agent.getPos(), agent.getYaw(), agent.getPitch());
			});
			caption = String.format(Locale.ROOT, "agent '%s' at %.1f %.1f %.1f (yaw %.0f, pitch %.0f)",
				name, pose.position().x, pose.position().y, pose.position().z, pose.yaw(), pose.pitch());
		} else {
			GameAccess.server();
		}

		NativeImage image = GameAccess.await(ScreenshotService.request(pose, fov, warmup));
		ImageEncoder.Encoded encoded = ImageEncoder.encode(image, width, png, config.jpegQuality);
		ToolResult result = ToolResult.image(Base64.getEncoder().encodeToString(encoded.data()), encoded.mimeType())
			.addText(caption + ", " + encoded.width() + "x" + encoded.height());
		String warning = GameAccess.pauseWarning();
		if (warning != null) {
			result.addText(warning);
		}
		return result;
	}

	/**
	 * Implements {@code get_state}.
	 */
	private static ToolResult state(ToolCall call) throws Exception {
		String name = GameAccess.optionalAgentName(call);
		MinecraftServer server = GameAccess.server();
		if (name != null) {
			GameAccess.ensureAgentLoaded(server, name);
		}
		String text = GameAccess.onServer(server, () -> {
			StringBuilder out = new StringBuilder();
			if (name != null) {
				AgentEntity agent = GameAccess.loadedAgent(server, name);
				out.append("agent: ").append(name).append(" at ").append(AgentTools.describe(agent))
					.append(", permission level ").append(AgentService.getPermissionLevel(server, name))
					.append("\n  looking at: ").append(describeTarget(agent));
			} else {
				out.append("agent: none attached");
			}
			ServerPlayerEntity player = GameAccess.hostPlayer(server);
			if (player != null) {
				out.append(String.format(Locale.ROOT, "%nhost player: %s at %.1f %.1f %.1f in %s (yaw %.0f, pitch %.0f), %s",
					player.getName().getString(), player.getX(), player.getY(), player.getZ(),
					player.getWorld().getRegistryKey().getValue(), player.getYaw(), player.getPitch(),
					player.interactionManager.getGameMode().getName()));
			}
			ServerWorld overworld = server.getOverworld();
			long dayTime = overworld.getTimeOfDay() % 24000;
			String weather = overworld.isThundering() ? "thunder" : overworld.isRaining() ? "rain" : "clear";
			out.append("\nworld: day ").append(overworld.getTimeOfDay() / 24000).append(", time ").append(dayTime)
				.append(", weather ").append(weather)
				.append(", players online ").append(server.getCurrentPlayerCount())
				.append(server.isRemote() ? ", open to LAN" : "");
			return out.toString();
		});
		MinecraftClient client = MinecraftClient.getInstance();
		String screen = GameAccess.await(client.submit(() -> client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()));
		return ToolResult.text(text + "\ngame paused: " + client.isPaused() + ", open screen on host: " + screen);
	}

	/**
	 * Server thread only.
	 *
	 * @return the block the agent looks at, or a note that there is none nearby
	 */
	private static String describeTarget(AgentEntity agent) {
		Vec3d start = agent.getEyePos();
		Vec3d end = start.add(agent.getRotationVector().multiply(LOOK_DISTANCE));
		BlockHitResult hit = agent.getWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, agent));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return "nothing within " + (int) LOOK_DISTANCE + " blocks";
		}
		var pos = hit.getBlockPos();
		return String.format(Locale.ROOT, "%s at %d %d %d (%s face, %.1f blocks away)",
			Registries.BLOCK.getId(agent.getWorld().getBlockState(pos).getBlock()),
			pos.getX(), pos.getY(), pos.getZ(), hit.getSide().asString(), Math.sqrt(hit.getPos().squaredDistanceTo(start)));
	}
}

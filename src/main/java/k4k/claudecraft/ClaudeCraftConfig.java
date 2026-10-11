package k4k.claudecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod configuration stored in {@code config/claudecraft.json}.
 * Missing fields keep their defaults, and the file is rewritten on load so new options appear in it.
 */
public final class ClaudeCraftConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClaudeCraftConfig instance = new ClaudeCraftConfig();

	/** Port of the MCP HTTP endpoint; the server always binds to the loopback interface. */
	public int port = 8765;
	/** When non-empty, MCP requests must carry {@code Authorization: Bearer <token>}. */
	public String token = "";
	/** Root command literals that MCP clients may not run, even through {@code execute ... run}. */
	public List<String> blockedCommands = new ArrayList<>(List.of("stop"));
	/**
	 * Permission level (0-4) of agents unless the host grants them more. Commands run "as player"
	 * are capped by it as well, so the player's own rights cannot be used to bypass it.
	 */
	public int defaultPermissionLevel = 2;
	/** How long {@code agent_request_permission} waits for the host's answer before reporting it as pending. */
	public int permissionRequestTimeoutSeconds = 120;
	/** Maximum number of agents per world. */
	public int maxAgents = 8;
	/** Whether operators see "[Agent: ...]" chat feedback for commands executed through MCP. */
	public boolean broadcastFeedbackToOps = true;
	/** Default width of returned screenshots; height follows the window aspect ratio. */
	public int screenshotWidth = 1024;
	/**
	 * How long the world is rendered from the agent's view before capturing, so chunk culling can catch up
	 * and the switch reads as a deliberate view change rather than a glitch.
	 */
	public int screenshotWarmupMillis = 1000;
	/** JPEG quality in the 0..1 range. */
	public float jpegQuality = 0.85f;
	/** How long a tool waits for the server or render thread before giving up. */
	public int toolTimeoutSeconds = 15;

	/**
	 * @return the currently loaded configuration
	 */
	public static ClaudeCraftConfig get() {
		return instance;
	}

	/**
	 * Loads the configuration from disk, falling back to defaults on any error, and writes it back.
	 */
	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("claudecraft.json");
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				ClaudeCraftConfig loaded = GSON.fromJson(reader, ClaudeCraftConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				ClaudeCraft.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			ClaudeCraft.LOGGER.error("Failed to write {}", path, e);
		}
	}
}

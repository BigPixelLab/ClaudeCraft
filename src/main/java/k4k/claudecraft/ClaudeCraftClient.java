package k4k.claudecraft;

import k4k.claudecraft.client.AgentEntityRenderer;
import k4k.claudecraft.client.NoteEditScreen;
import k4k.claudecraft.client.NoteRenderer;
import k4k.claudecraft.client.PauseControl;
import k4k.claudecraft.client.ScreenshotService;
import k4k.claudecraft.item.QuillInteractions;
import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.note.NoteNetworking;
import k4k.claudecraft.tools.AgentTools;
import k4k.claudecraft.tools.CommandTools;
import k4k.claudecraft.tools.NoteTools;
import k4k.claudecraft.tools.RegistryTools;
import k4k.claudecraft.tools.ViewTools;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.util.List;

/**
 * Client entrypoint: registers the agent renderer, the note editor and renderer, and starts the MCP server for the lifetime of the game,
 * so MCP clients stay connected while worlds are opened and closed.
 */
public class ClaudeCraftClient implements ClientModInitializer {
	/**
	 * Registers client-side rendering and networking and starts the MCP server.
	 */
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ClaudeCraft.AGENT, AgentEntityRenderer::new);
		QuillInteractions.setEditorOpener(NoteEditScreen::open);
		ClientPlayNetworking.registerGlobalReceiver(NoteNetworking.SYNC_NOTES, (client, handler, buf, sender) -> {
			var notes = NoteNetworking.decode(buf);
			client.execute(() -> NoteRenderer.setNotes(notes));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> NoteRenderer.setNotes(List.of())));
		WorldRenderEvents.AFTER_ENTITIES.register(NoteRenderer::render);
		WorldRenderEvents.AFTER_ENTITIES.register(ScreenshotService::renderHostPlayer);

		ClaudeCraftConfig config = ClaudeCraftConfig.get();
		String version = FabricLoader.getInstance().getModContainer(ClaudeCraft.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
		McpHttpServer server = new McpHttpServer(version, config.token, PauseControl::acquire);
		CommandTools.register(server);
		RegistryTools.register(server);
		AgentTools.register(server);
		ViewTools.register(server);
		NoteTools.register(server);
		try {
			server.start(config.port);
		} catch (IOException e) {
			ClaudeCraft.LOGGER.error("Failed to start the MCP server on port {}; change 'port' in config/claudecraft.json", config.port, e);
			return;
		}
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> server.stop());
	}
}

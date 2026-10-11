package k4k.claudecraft;

import k4k.claudecraft.agent.AgentEntity;
import k4k.claudecraft.agent.AgentService;
import k4k.claudecraft.agent.PermissionRequests;
import k4k.claudecraft.command.ClaudeCraftCommand;
import k4k.claudecraft.item.QuillInteractions;
import k4k.claudecraft.item.QuillItem;
import k4k.claudecraft.note.NoteNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint: registers the agent entity, the quill, notes networking and the {@code /claudecraft} command,
 * and keeps agents' chunks loaded across restarts.
 * The MCP server itself lives on the client, see {@link ClaudeCraftClient}.
 */
public class ClaudeCraft implements ModInitializer {
	public static final String MOD_ID = "claudecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final EntityType<AgentEntity> AGENT = Registry.register(
		Registries.ENTITY_TYPE,
		id("agent"),
		FabricEntityTypeBuilder.<AgentEntity>create(SpawnGroup.MISC, AgentEntity::new)
			.dimensions(EntityDimensions.fixed(0.5f, 0.5f))
			.trackRangeChunks(16)
			.trackedUpdateRate(1)
			.fireImmune()
			.build()
	);

	public static final Item QUILL = Registry.register(Registries.ITEM, id("quill"), new QuillItem(new Item.Settings().maxCount(1)));

	/**
	 * @param path path inside the mod namespace
	 * @return identifier in the {@value MOD_ID} namespace
	 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	/**
	 * Loads the config, registers the command and hooks agent state into the server lifecycle.
	 */
	@Override
	public void onInitialize() {
		ClaudeCraftConfig.load();
		ServerLifecycleEvents.SERVER_STARTED.register(AgentService::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> PermissionRequests.clear());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ClaudeCraftCommand.register(dispatcher));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(QUILL));
		QuillInteractions.register();
		NoteNetworking.registerServer();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NoteNetworking.sync(handler.getPlayer()));
	}
}

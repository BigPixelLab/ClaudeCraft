package k4k.claudecraft.tools;

import k4k.claudecraft.ClaudeCraftException;
import k4k.claudecraft.mcp.McpHttpServer;
import k4k.claudecraft.mcp.McpTool;
import k4k.claudecraft.mcp.Schema;
import k4k.claudecraft.mcp.ToolCall;
import k4k.claudecraft.mcp.ToolResult;
import k4k.claudecraft.registry.RegistryInspector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Tools for browsing game registries and their tags.
 */
public final class RegistryTools {
	private static final String FILTER_DESCRIPTION = "Case-insensitive substring to match against ids, e.g. 'oak' or 'create:'.";
	private static final int DEFAULT_LIMIT = 200;

	private RegistryTools() {
	}

	/**
	 * @param server MCP server to register the tools on
	 */
	public static void register(McpHttpServer server) {
		server.register(new McpTool(
			"list_registries",
			"Lists all game registries (static ones like minecraft:block and data-driven ones like minecraft:worldgen/biome) "
				+ "with entry and tag counts. Registries are themselves entries of the root registry.",
			Schema.object().string("filter", FILTER_DESCRIPTION, false).build(),
			RegistryTools::listRegistries
		));
		server.register(new McpTool(
			"list_registry_entries",
			"Lists entry ids of a registry, e.g. all blocks or all biomes, with optional filtering and paging.",
			pagedSchema().string("registry", "Registry id, e.g. 'minecraft:item'.", true).build(),
			RegistryTools::listEntries
		));
		server.register(new McpTool(
			"list_registry_tags",
			"Lists tag ids of a registry (usable as #tag in commands), with optional filtering and paging.",
			pagedSchema().string("registry", "Registry id, e.g. 'minecraft:block'.", true).build(),
			RegistryTools::listTags
		));
		server.register(new McpTool(
			"get_tag_entries",
			"Lists the entries of a registry tag, with nested tags flattened.",
			Schema.object()
				.string("registry", "Registry id, e.g. 'minecraft:block'.", true)
				.string("tag", "Tag id, with or without '#', e.g. 'minecraft:logs'.", true)
				.build(),
			RegistryTools::tagEntries
		));
	}

	/**
	 * @return a schema builder with the shared filter/offset/limit properties
	 */
	private static Schema pagedSchema() {
		return Schema.object()
			.string("filter", FILTER_DESCRIPTION, false)
			.integer("offset", "Number of matching ids to skip (default 0).", false)
			.integer("limit", "Maximum number of ids to return (default " + DEFAULT_LIMIT + ").", false);
	}

	/**
	 * Implements {@code list_registries}.
	 */
	private static ToolResult listRegistries(ToolCall call) throws Exception {
		String filter = normalizeFilter(call.optString("filter", null));
		MinecraftServer server = GameAccess.server();
		List<RegistryInspector.RegistryInfo> registries = GameAccess.onServer(server, () ->
			RegistryInspector.registries(server.getRegistryManager()));
		StringBuilder text = new StringBuilder("registry | entries | tags | kind");
		int shown = 0;
		for (RegistryInspector.RegistryInfo info : registries) {
			if (filter == null || info.id().toString().contains(filter)) {
				text.append("\n").append(info.id()).append(" | ").append(info.size()).append(" | ").append(info.tagCount())
					.append(" | ").append(info.dynamic() ? "dynamic" : "static");
				shown++;
			}
		}
		text.append("\n(").append(shown).append(" registries)");
		return ToolResult.text(text.toString());
	}

	/**
	 * Implements {@code list_registry_entries}.
	 */
	private static ToolResult listEntries(ToolCall call) throws Exception {
		Identifier registry = parseId(call.string("registry"), "registry");
		MinecraftServer server = GameAccess.server();
		List<Identifier> ids = GameAccess.onServer(server, () -> RegistryInspector.entries(server.getRegistryManager(), registry));
		return page(call, ids, "entries", "");
	}

	/**
	 * Implements {@code list_registry_tags}.
	 */
	private static ToolResult listTags(ToolCall call) throws Exception {
		Identifier registry = parseId(call.string("registry"), "registry");
		MinecraftServer server = GameAccess.server();
		List<Identifier> ids = GameAccess.onServer(server, () -> RegistryInspector.tags(server.getRegistryManager(), registry));
		return page(call, ids, "tags", "#");
	}

	/**
	 * Implements {@code get_tag_entries}.
	 */
	private static ToolResult tagEntries(ToolCall call) throws Exception {
		Identifier registry = parseId(call.string("registry"), "registry");
		String rawTag = call.string("tag");
		Identifier tag = parseId(rawTag.startsWith("#") ? rawTag.substring(1) : rawTag, "tag");
		MinecraftServer server = GameAccess.server();
		List<Identifier> ids = GameAccess.onServer(server, () -> RegistryInspector.tagEntries(server.getRegistryManager(), registry, tag));
		StringBuilder text = new StringBuilder("#").append(tag).append(" (").append(ids.size()).append(" entries):");
		ids.forEach(id -> text.append("\n").append(id));
		return ToolResult.text(text.toString());
	}

	/**
	 * Filters and pages a sorted id list.
	 */
	private static ToolResult page(ToolCall call, List<Identifier> ids, String noun, String prefix) {
		String filter = normalizeFilter(call.optString("filter", null));
		int offset = Math.max(0, call.optInt("offset", 0));
		int limit = Math.max(1, call.optInt("limit", DEFAULT_LIMIT));
		List<Identifier> matching = filter == null ? ids : ids.stream().filter(id -> id.toString().contains(filter)).toList();
		int end = Math.min(matching.size(), offset + limit);
		StringBuilder text = new StringBuilder();
		text.append(matching.size()).append(" matching ").append(noun);
		if (offset < end) {
			text.append(", showing ").append(offset).append("..").append(end - 1).append(":");
			for (Identifier id : matching.subList(offset, end)) {
				text.append("\n").append(prefix).append(id);
			}
		}
		if (end < matching.size()) {
			text.append("\n(more: use offset=").append(end).append(")");
		}
		return ToolResult.text(text.toString());
	}

	/**
	 * @return the lower-cased filter, or {@code null} if it is empty
	 */
	@Nullable
	private static String normalizeFilter(@Nullable String filter) {
		return filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
	}

	/**
	 * @return the parsed identifier
	 * @throws ClaudeCraftException if it is malformed
	 */
	private static Identifier parseId(String raw, String what) {
		Identifier id = Identifier.tryParse(raw);
		if (id == null) {
			throw new ClaudeCraftException("Invalid " + what + " id '" + raw + "'.");
		}
		return id;
	}
}

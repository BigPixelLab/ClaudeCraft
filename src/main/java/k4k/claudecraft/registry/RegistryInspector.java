package k4k.claudecraft.registry;

import k4k.claudecraft.ClaudeCraftException;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the server's registries, covering both the static ones (blocks, items, ...)
 * and the data-driven dynamic ones (biomes, damage types, ...). Must be called on the server thread.
 */
public final class RegistryInspector {
	private RegistryInspector() {
	}

	/**
	 * Summary of one registry.
	 *
	 * @param id       registry id (its key in the root registry)
	 * @param size     number of entries
	 * @param tagCount number of tags
	 * @param dynamic  whether the registry is loaded from data packs rather than built into the game
	 */
	public record RegistryInfo(Identifier id, int size, int tagCount, boolean dynamic) {
	}

	/**
	 * @param registries the server's combined registry manager
	 * @return all registries sorted by id
	 */
	public static List<RegistryInfo> registries(DynamicRegistryManager registries) {
		List<RegistryInfo> result = new ArrayList<>();
		registries.streamAllRegistries().forEach(entry -> {
			Registry<?> registry = entry.value();
			Identifier id = entry.key().getValue();
			result.add(new RegistryInfo(id, registry.size(), (int) registry.streamTags().count(), !Registries.REGISTRIES.containsId(id)));
		});
		result.sort(Comparator.comparing(RegistryInfo::id));
		return result;
	}

	/**
	 * @param registries the server's combined registry manager
	 * @param registryId registry id, e.g. {@code minecraft:block}
	 * @return sorted entry ids
	 */
	public static List<Identifier> entries(DynamicRegistryManager registries, Identifier registryId) {
		return registry(registries, registryId).getIds().stream().sorted().toList();
	}

	/**
	 * @param registries the server's combined registry manager
	 * @param registryId registry id
	 * @return sorted tag ids
	 */
	public static List<Identifier> tags(DynamicRegistryManager registries, Identifier registryId) {
		return registry(registries, registryId).streamTags().map(TagKey::id).sorted().toList();
	}

	/**
	 * @param registries the server's combined registry manager
	 * @param registryId registry id
	 * @param tagId      tag id without the leading '#'
	 * @return sorted ids of the entries in the tag, with nested tags already flattened
	 */
	public static List<Identifier> tagEntries(DynamicRegistryManager registries, Identifier registryId, Identifier tagId) {
		return tagEntries(registry(registries, registryId), tagId);
	}

	/**
	 * Typed helper for {@link #tagEntries(DynamicRegistryManager, Identifier, Identifier)}.
	 */
	private static <T> List<Identifier> tagEntries(Registry<T> registry, Identifier tagId) {
		Optional<RegistryEntryList.Named<T>> list = registry.getEntryList(TagKey.of(registry.getKey(), tagId));
		if (list.isEmpty()) {
			throw new ClaudeCraftException("Registry " + registry.getKey().getValue() + " has no tag #" + tagId + ".");
		}
		return list.get().stream()
			.map(RegistryEntry::getKey)
			.flatMap(Optional::stream)
			.map(RegistryKey::getValue)
			.sorted()
			.toList();
	}

	/**
	 * @return the registry with the given id
	 * @throws ClaudeCraftException if there is no such registry
	 */
	private static Registry<Object> registry(DynamicRegistryManager registries, Identifier registryId) {
		RegistryKey<Registry<Object>> key = RegistryKey.ofRegistry(registryId);
		return registries.getOptional(key)
			.orElseThrow(() -> new ClaudeCraftException("Unknown registry '" + registryId + "'. Use list_registries to see available ones."));
	}
}

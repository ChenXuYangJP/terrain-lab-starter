package io.github.terrainlab;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.BiomeTagKeys;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Reads the running server's registry and dimension tags instead of a fixed biome enum. */
public final class BiomeCatalog {
    private static final List<String> DIMENSIONS = List.of("overworld", "nether", "end");
    private static final String THE_VOID = "minecraft:the_void";

    private BiomeCatalog() {
    }

    public record Target(String dimension, String biome) {
    }

    /**
     * Returns unique targets ordered by dimension, then by namespaced biome key.
     * Dimension tags describe the server's configured groups; they do not prove
     * that every member is reachable in a particular world's biome generator.
     */
    public static List<Target> targets(List<String> dimensions, List<String> excluded) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(excluded, "excluded");
        Registry<Biome> registry = registry();
        Set<String> excludedKeys = new HashSet<>();
        excludedKeys.add(THE_VOID);
        for (String value : excluded) {
            excludedKeys.add(parseKey(value).toString());
        }

        Set<String> selectedDimensions = new TreeSet<>();
        for (String dimension : dimensions) {
            selectedDimensions.add(normalizeDimension(dimension));
        }

        List<Target> result = new ArrayList<>();
        for (String dimension : selectedDimensions) {
            for (String biome : keys(registry, registry.getTagValues(dimensionTag(dimension)))) {
                if (!excludedKeys.contains(biome)) {
                    result.add(new Target(dimension, biome));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Exports the actual registry and unfiltered dimension tags for the manifest.
     * The registered list may include THE_VOID and custom datapack biomes.
     */
    public static Map<String, Object> export() {
        Registry<Biome> registry = registry();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", registry.keyStream().map(Object::toString).sorted().toList());
        for (String dimension : DIMENSIONS) {
            result.put(dimension, keys(registry, registry.getTagValues(dimensionTag(dimension))));
        }
        return result;
    }

    public static String key(Biome biome) {
        return registry().getKeyOrThrow(Objects.requireNonNull(biome, "biome")).toString();
    }

    /** Accepts namespaced keys or unqualified Minecraft names; rejects unknown entries. */
    public static Biome resolve(String biomeKey) {
        NamespacedKey parsed = parseKey(biomeKey);
        Biome biome = registry().get(parsed);
        if (biome == null) {
            throw new IllegalArgumentException("Biome is not registered: " + parsed);
        }
        return biome;
    }

    public static World.Environment environment(String dimension) {
        return switch (normalizeDimension(dimension)) {
            case "overworld" -> World.Environment.NORMAL;
            case "nether" -> World.Environment.NETHER;
            case "end" -> World.Environment.THE_END;
            default -> throw new AssertionError("Validated dimension was not handled");
        };
    }

    private static Registry<Biome> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    }

    private static List<String> keys(Registry<Biome> registry, Collection<Biome> biomes) {
        return biomes.stream()
                .map(biome -> registry.getKeyOrThrow(biome).toString())
                .distinct()
                .sorted()
                .toList();
    }

    private static TagKey<Biome> dimensionTag(String dimension) {
        return switch (dimension) {
            case "overworld" -> BiomeTagKeys.IS_OVERWORLD;
            case "nether" -> BiomeTagKeys.IS_NETHER;
            case "end" -> BiomeTagKeys.IS_END;
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
    }

    private static String normalizeDimension(String value) {
        String dimension = Objects.requireNonNull(value, "dimension").trim().toLowerCase(Locale.ROOT);
        if (!DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("Unknown dimension: " + value
                    + "; expected overworld, nether, or end.");
        }
        return dimension;
    }

    private static NamespacedKey parseKey(String value) {
        String normalized = Objects.requireNonNull(value, "biomeKey").trim().toLowerCase(Locale.ROOT);
        NamespacedKey parsed = NamespacedKey.fromString(normalized);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid biome key: " + value);
        }
        return parsed;
    }
}

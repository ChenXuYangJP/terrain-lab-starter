package io.github.terrainlab;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable-by-value terrain data. This class deliberately has no Bukkit dependency.
 * All offsets are relative to metadata.source_origin_xyz, the source center block.
 */
public record TerrainSnapshot(
        int schemaVersion,
        String id,
        Map<String, Object> metadata,
        Grid core,
        Grid context) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final Pattern ID_PATTERN = Pattern.compile("sample_[a-z0-9_]{1,100}");
    private static final Pattern SEED_PATTERN = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern BIOME_PATTERN = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public TerrainSnapshot {
        Objects.requireNonNull(metadata, "metadata");
        metadata = freezeMetadata(metadata);
        Objects.requireNonNull(core, "core");
    }

    /** Rejects unsupported schema, unsafe identifiers, invalid metadata and inconsistent crops. */
    public void validate() {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema_version: " + schemaVersion);
        }
        validateId(id);
        validateMetadata(metadata);
        core.validate();
        if (core.sizeX != 25 || core.sizeY != 11 || core.sizeZ != 25
                || core.minDx != -12 || core.minDy != -5 || core.minDz != -12) {
            throw new IllegalArgumentException("core must be 25 x 11 x 25 with min_offset_xyz [-12,-5,-12]");
        }
        if (context == null) {
            return;
        }
        context.validate();
        if (!contains(context, core)) {
            throw new IllegalArgumentException("context does not contain the complete core");
        }
        for (int ry = 0; ry < core.sizeY; ry++) {
            int dy = core.minDy + ry;
            for (int rz = 0; rz < core.sizeZ; rz++) {
                int dz = core.minDz + rz;
                for (int rx = 0; rx < core.sizeX; rx++) {
                    int dx = core.minDx + rx;
                    int a = ((ry * core.sizeZ) + rz) * core.sizeX + rx;
                    int b = context.indexAt(dx, dy, dz);
                    if (!core.palette.get(core.blocks[a]).equals(context.palette.get(context.blocks[b]))) {
                        throw new IllegalArgumentException("core/context block mismatch at offset "
                                + dx + "," + dy + "," + dz);
                    }
                    if (!core.biomePalette.get(core.biomes[a]).equals(context.biomePalette.get(context.biomes[b]))) {
                        throw new IllegalArgumentException("core/context biome mismatch at offset "
                                + dx + "," + dy + "," + dz);
                    }
                }
            }
        }
    }

    public static void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid snapshot id; expected sample_[a-z0-9_]{1,100}");
        }
    }

    private static boolean contains(Grid outer, Grid inner) {
        return outer.minDx <= inner.minDx && outer.minDy <= inner.minDy && outer.minDz <= inner.minDz
                && (long) outer.minDx + outer.sizeX >= (long) inner.minDx + inner.sizeX
                && (long) outer.minDy + outer.sizeY >= (long) inner.minDy + inner.sizeY
                && (long) outer.minDz + outer.sizeZ >= (long) inner.minDz + inner.sizeZ;
    }

    private static void validateMetadata(Map<String, Object> metadata) {
        Object seed = metadata.get("seed");
        if (!(seed instanceof String text) || !SEED_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException("metadata.seed must be a decimal string, never a JSON number");
        }
        try {
            Long.parseLong((String) seed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("metadata.seed must fit Minecraft's signed 64-bit seed", ex);
        }
        Object origin = metadata.get("source_origin_xyz");
        if (!(origin instanceof List<?> coords) || coords.size() != 3) {
            throw new IllegalArgumentException("metadata.source_origin_xyz must contain exactly three integers");
        }
        for (Object coordinate : (List<?>) origin) {
            requireInt(coordinate, "metadata.source_origin_xyz coordinate");
        }
        for (String flag : List.of("entities_omitted", "block_entity_nbt_omitted", "scheduled_ticks_omitted")) {
            if (!Boolean.TRUE.equals(metadata.get(flag))) {
                throw new IllegalArgumentException("metadata." + flag + " must be true for this schema");
            }
        }
        if (!"blockdata_only".equals(metadata.get("fidelity"))) {
            throw new IllegalArgumentException("metadata.fidelity must be blockdata_only");
        }
    }

    private static void requireInt(Object value, String name) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return;
        }
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return;
        }
        if (value instanceof BigInteger number
                && number.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
                && number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
            return;
        }
        throw new IllegalArgumentException(name + " must be a signed 32-bit integer");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> freezeMetadata(Map<String, Object> value) {
        return (Map<String, Object>) freeze(value, new IdentityHashMap<>(), 0);
    }

    /** Deep copies JSON-like values and rejects non-finite numbers and mutable non-JSON objects. */
    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> active, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("Metadata nesting exceeds 64 levels");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("Metadata contains a non-finite number");
            return number;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Metadata contains a non-finite number");
            return number;
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            throw new IllegalArgumentException("Unsupported metadata value type: " + value.getClass().getName());
        }
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Metadata contains a cyclic collection");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Metadata object keys must be strings");
                    }
                    copy.put(key, freeze(entry.getValue(), active, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<?>) value) {
                copy.add(freeze(element, active, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            active.remove(value);
        }
    }

    /**
     * A dense palette grid. Index order is y,z,x (x fastest).
     * biome indices store the sampled biome label at each block coordinate; they are not entities.
     */
    public record Grid(
            int sizeX, int sizeY, int sizeZ,
            int minDx, int minDy, int minDz,
            List<String> palette, int[] blocks,
            List<String> biomePalette, int[] biomes) {

        public Grid {
            palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
            blocks = Objects.requireNonNull(blocks, "blocks").clone();
            biomePalette = List.copyOf(Objects.requireNonNull(biomePalette, "biomePalette"));
            biomes = Objects.requireNonNull(biomes, "biomes").clone();
        }

        @Override
        public int[] blocks() {
            return blocks.clone();
        }

        @Override
        public int[] biomes() {
            return biomes.clone();
        }

        public int volume() {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("Grid dimensions must be positive");
            }
            try {
                return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Grid volume exceeds the signed 32-bit array limit", ex);
            }
        }

        public int indexAt(int dx, int dy, int dz) {
            long x = (long) dx - minDx;
            long y = (long) dy - minDy;
            long z = (long) dz - minDz;
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                throw new IndexOutOfBoundsException("Offset outside grid: " + dx + "," + dy + "," + dz);
            }
            long index = (y * sizeZ + z) * sizeX + x;
            if (index > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Grid index exceeds the signed 32-bit array limit");
            }
            return (int) index;
        }

        public String blockAt(int dx, int dy, int dz) {
            return palette.get(blocks[indexAt(dx, dy, dz)]);
        }

        public String biomeAt(int dx, int dy, int dz) {
            return biomePalette.get(biomes[indexAt(dx, dy, dz)]);
        }

        public void validate() {
            int expected = volume();
            checkMaxOffset(minDx, sizeX, "x");
            checkMaxOffset(minDy, sizeY, "y");
            checkMaxOffset(minDz, sizeZ, "z");
            if (blocks.length != expected || biomes.length != expected) {
                throw new IllegalArgumentException("Grid arrays must each contain exactly " + expected + " entries");
            }
            if (palette.isEmpty() || biomePalette.isEmpty()) {
                throw new IllegalArgumentException("Grid palettes must not be empty");
            }
            for (String block : palette) {
                if (block == null || block.isBlank()) {
                    throw new IllegalArgumentException("Block palette entries must be nonblank BlockData strings");
                }
            }
            for (String biome : biomePalette) {
                if (biome == null || !BIOME_PATTERN.matcher(biome).matches()) {
                    throw new IllegalArgumentException("Biome palette entries must be namespaced keys");
                }
            }
            for (int index : blocks) {
                if (index < 0 || index >= palette.size()) {
                    throw new IllegalArgumentException("Block palette index out of bounds: " + index);
                }
            }
            for (int index : biomes) {
                if (index < 0 || index >= biomePalette.size()) {
                    throw new IllegalArgumentException("Biome palette index out of bounds: " + index);
                }
            }
        }

        private static void checkMaxOffset(int min, int size, String axis) {
            if ((long) min + size - 1 > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Grid maximum " + axis + " offset overflows a signed integer");
            }
        }
    }
}

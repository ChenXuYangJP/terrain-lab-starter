package io.github.terrainlab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** UTF-8 gzip JSON storage; accepts only detached data and never accesses a Minecraft world. */
public final class SnapshotIO {
    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setStrictness(Strictness.STRICT)
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private static final Type METADATA_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final String SUFFIX = ".json.gz";
    private static final byte[] HASH_DOMAIN = "terrain-lab:blockdata:v1\n".getBytes(StandardCharsets.UTF_8);
    private static final long MAX_JSON_CHARACTERS = 64L * 1024 * 1024;
    private static final Set<String> SNAPSHOT_KEYS = Set.of("schema_version", "id", "metadata", "core", "context");
    private static final Set<String> GRID_KEYS = Set.of("shape_yzx", "min_offset_xyz", "axis_order",
            "palette", "indices", "biome_palette", "biome_indices");
    private final Path root;

    public SnapshotIO(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (!Files.isDirectory(this.root)) {
            throw new IOException("Snapshot root is not a directory: " + this.root);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Writes a closed, complete temporary gzip, then publishes it using an atomic hard link.
     * Unlike ATOMIC_MOVE, link creation cannot replace an existing destination. Filesystems
     * without hard-link support fail explicitly; no weaker overwrite-prone fallback is used.
     * This guarantees visibility of a complete file, not persistence after a power failure.
     */
    public Path save(TerrainSnapshot snapshot) throws IOException {
        snapshot.validate();
        verifyDeclaredHash(snapshot);
        Path target = pathFor(snapshot.id());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        Path temporary = Files.createTempFile(root, "." + snapshot.id() + "-", ".tmp");
        try {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)), StandardCharsets.UTF_8))) {
                GSON.toJson(toJson(snapshot), writer);
                writer.write('\n');
            }
            try {
                Files.createLink(target, temporary);
            } catch (UnsupportedOperationException ex) {
                throw new IOException("Snapshot filesystem must support hard links for atomic no-overwrite publication", ex);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public TerrainSnapshot load(String id) throws IOException {
        Path source = pathFor(id);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Snapshot is missing or is not a regular file: " + source);
        }
        try (Reader decoded = new InputStreamReader(new GZIPInputStream(Files.newInputStream(source)),
                     StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT));
             JsonReader reader = new JsonReader(new BufferedReader(new LimitedReader(decoded, MAX_JSON_CHARACTERS)))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement tree = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing data after snapshot JSON");
            }
            TerrainSnapshot snapshot = fromJson(tree);
            if (!id.equals(snapshot.id())) {
                throw new IOException("Snapshot id does not match its filename");
            }
            snapshot.validate();
            verifyDeclaredHash(snapshot);
            return snapshot;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new IOException("Invalid snapshot " + id + ": " + ex.getMessage(), ex);
        }
    }

    public List<String> listIds() throws IOException {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + SUFFIX)) {
            for (Path path : stream) {
                String filename = path.getFileName().toString();
                String id = filename.substring(0, filename.length() - SUFFIX.length());
                if (TerrainSnapshot.ID_PATTERN.matcher(id).matches()
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    ids.add(id);
                }
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    /** Hashes geometry and decoded BlockData; palette order, biome labels and metadata do not enter the hash. */
    public static String terrainHash(TerrainSnapshot.Grid grid) {
        grid.validate();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM does not provide SHA-256", ex);
        }
        digest.update(HASH_DOMAIN);
        for (int value : new int[] {grid.sizeX(), grid.sizeY(), grid.sizeZ(), grid.minDx(), grid.minDy(), grid.minDz()}) {
            updateInt(digest, value);
        }
        // Encode each palette entry only once, but feed the decoded sequence to the digest.
        List<byte[]> encoded = grid.palette().stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
        for (int index : grid.blocks()) {
            byte[] bytes = encoded.get(index);
            updateInt(digest, bytes.length);
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void verifyDeclaredHash(TerrainSnapshot snapshot) {
        if (!snapshot.metadata().containsKey("terrain_sha256")) return;
        Object declared = snapshot.metadata().get("terrain_sha256");
        TerrainSnapshot.Grid hashed = snapshot.context() == null ? snapshot.core() : snapshot.context();
        if (!(declared instanceof String hash) || !hash.matches("[a-f0-9]{64}")
                || !hash.equals(terrainHash(hashed))) {
            throw new IllegalArgumentException("metadata.terrain_sha256 does not match decoded context (or core if context is absent)");
        }
    }

    private Path pathFor(String id) {
        TerrainSnapshot.validateId(id);
        return root.resolve(id + SUFFIX);
    }

    private static JsonObject toJson(TerrainSnapshot snapshot) {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", snapshot.schemaVersion());
        object.addProperty("id", snapshot.id());
        object.add("metadata", GSON.toJsonTree(snapshot.metadata()));
        object.add("core", gridToJson(snapshot.core()));
        object.add("context", snapshot.context() == null ? JsonNull.INSTANCE : gridToJson(snapshot.context()));
        return object;
    }

    private static JsonObject gridToJson(TerrainSnapshot.Grid grid) {
        JsonObject object = new JsonObject();
        object.add("shape_yzx", GSON.toJsonTree(new int[] {grid.sizeY(), grid.sizeZ(), grid.sizeX()}));
        object.add("min_offset_xyz", GSON.toJsonTree(new int[] {grid.minDx(), grid.minDy(), grid.minDz()}));
        object.addProperty("axis_order", "y,z,x");
        object.add("palette", GSON.toJsonTree(grid.palette()));
        object.add("indices", GSON.toJsonTree(grid.blocks()));
        object.add("biome_palette", GSON.toJsonTree(grid.biomePalette()));
        object.add("biome_indices", GSON.toJsonTree(grid.biomes()));
        return object;
    }

    private static TerrainSnapshot fromJson(JsonElement tree) {
        JsonObject object = requireObject(tree, "snapshot");
        requireKeys(object, SNAPSHOT_KEYS, "snapshot");
        int version = requireInt(object.get("schema_version"), "schema_version");
        String id = requireString(object.get("id"), "id");
        JsonObject metadataObject = requireObject(object.get("metadata"), "metadata");
        Map<String, Object> metadata = GSON.fromJson(metadataObject, METADATA_TYPE);
        TerrainSnapshot.Grid core = gridFromJson(object.get("core"), "core");
        JsonElement context = object.get("context");
        return new TerrainSnapshot(version, id, metadata, core,
                context.isJsonNull() ? null : gridFromJson(context, "context"));
    }

    private static TerrainSnapshot.Grid gridFromJson(JsonElement tree, String name) {
        JsonObject object = requireObject(tree, name);
        requireKeys(object, GRID_KEYS, name);
        if (!"y,z,x".equals(requireString(object.get("axis_order"), name + ".axis_order"))) {
            throw new IllegalArgumentException(name + ".axis_order must be y,z,x");
        }
        int[] shape = intArray(object.get("shape_yzx"), name + ".shape_yzx");
        int[] offsets = intArray(object.get("min_offset_xyz"), name + ".min_offset_xyz");
        if (shape.length != 3 || offsets.length != 3) {
            throw new IllegalArgumentException(name + " shape and offsets must each have three integers");
        }
        return new TerrainSnapshot.Grid(shape[2], shape[0], shape[1], offsets[0], offsets[1], offsets[2],
                stringArray(object.get("palette"), name + ".palette"),
                intArray(object.get("indices"), name + ".indices"),
                stringArray(object.get("biome_palette"), name + ".biome_palette"),
                intArray(object.get("biome_indices"), name + ".biome_indices"));
    }

    private static JsonObject requireObject(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireKeys(JsonObject object, Set<String> keys, String name) {
        if (!object.keySet().equals(keys)) {
            throw new IllegalArgumentException(name + " must have exactly these keys: " + keys);
        }
    }

    private static String requireString(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int requireInt(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(name + " must be a signed 32-bit integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a signed 32-bit integer", ex);
        }
    }

    private static int[] intArray(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        int[] output = new int[array.size()];
        for (int i = 0; i < output.length; i++) output[i] = requireInt(array.get(i), name + "[" + i + "]");
        return output;
    }

    private static List<String> stringArray(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<String> output = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) output.add(requireString(element, name + " entry"));
        return output;
    }

    /** Strict tree reader also rejects duplicate JSON keys instead of silently accepting the last value. */
    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("JSON nesting exceeds 64 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IOException("Duplicate JSON key: " + name);
                    object.add(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected JSON token: " + reader.peek());
        };
    }

    private static final class LimitedReader extends FilterReader {
        private long remaining;

        private LimitedReader(Reader in, long maximum) {
            super(in);
            remaining = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && --remaining < 0) throw new IOException("Snapshot JSON exceeds the 64 Mi-character limit");
            return value;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, (int) Math.min(length, Math.max(1L, remaining + 1)));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Snapshot JSON exceeds the 64 Mi-character limit");
            return count;
        }
    }
}

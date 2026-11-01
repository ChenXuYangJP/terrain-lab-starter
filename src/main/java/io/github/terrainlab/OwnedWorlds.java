package io.github.terrainlab;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

/** Owns separate natural source dimensions and one empty inspection dimension. */
public final class OwnedWorlds {
    private static final String NAMESPACE = "terrainlab";
    private static final String SOURCE_PREFIX = "terrainlab_src_";
    private static final String PREVIEW_NAME = "terrainlab_preview";
    private static final long PREVIEW_SEED = 0L;
    private static final int MAX_NBT_BYTES = 1_048_576;

    private final JavaPlugin plugin;
    private final Path owners;
    private final Map<UUID, Source> sources = new HashMap<>();
    private UUID previewId;

    public OwnedWorlds(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.owners = plugin.getDataFolder().toPath().resolve("owners");
    }

    public World source(String jobId, long seed, String dimension) throws IOException {
        requireMainThread();
        String token = Objects.requireNonNull(jobId, "jobId").toLowerCase(Locale.ROOT);
        if (!token.matches("[a-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("jobId must contain 1-80 letters, digits, underscores, or hyphens.");
        }
        World.Environment environment = BiomeCatalog.environment(dimension);
        String suffix = switch (environment) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> throw new IllegalArgumentException("Unsupported source environment.");
        };
        String name = SOURCE_PREFIX + token + "_" + Long.toHexString(seed) + "_" + suffix;
        Opened opened = open(name, seed, environment, false);
        Source previous = sources.get(opened.world().getUID());
        boolean mayUnload = opened.loadedHere() || (previous != null && previous.mayUnload());
        sources.put(opened.world().getUID(), new Source(name, seed, environment, mayUnload));
        return opened.world();
    }

    public World preview() throws IOException {
        requireMainThread();
        World world = open(PREVIEW_NAME, PREVIEW_SEED, World.Environment.NORMAL, true).world();
        previewId = world.getUID();
        return world;
    }

    /**
     * Live-physics rules for the preview play sandbox. Off restores the preview
     * values from applyRules, which also reapplies them on every open.
     */
    public void setPreviewSandbox(World world, boolean on) {
        requireMainThread();
        if (!isPreview(world)) throw new IllegalArgumentException("Not the TerrainLab preview world.");
        world.setGameRule(GameRule.MOB_GRIEFING, on);
        world.setGameRule(GameRule.DO_FIRE_TICK, on);
        world.setGameRule(GameRule.DO_TILE_DROPS, on);
        world.setGameRule(GameRule.DO_ENTITY_DROPS, on);
        world.setGameRule(GameRule.DO_MOB_LOOT, on);
    }

    public boolean isOwnedSource(World world) {
        if (world == null) return false;
        Source source = sources.get(world.getUID());
        return source != null && world.getKey().equals(worldKey(source.name()));
    }

    public boolean isPreview(World world) {
        return world != null && previewId != null && previewId.equals(world.getUID())
                && world.getKey().equals(worldKey(PREVIEW_NAME));
    }

    /** Never removes tickets itself or unloads a world borrowed from another loader. */
    public void releaseSourceWorld(World world) {
        requireMainThread();
        if (!isOwnedSource(world)) return;
        Source source = sources.get(world.getUID());
        if (!source.mayUnload() || plugin.getServer().getWorld(world.getUID()) != world
                || !world.getPlayers().isEmpty()) return;
        // All our keys use a separate namespace; explicitly protect vanilla primary dimensions too.
        if (world.getKey().getNamespace().equals("minecraft")) return;
        // Be conservative about other plugins as well as our own pending capture tickets.
        if (world.getPluginChunkTickets().values().stream().anyMatch(tickets -> !tickets.isEmpty())) return;
        try {
            Owner owner = readOwner(source.name());
            validateOwner(owner, source.name(), source.seed(), source.environment(), false);
            verifyWorld(world, source.name(), source.seed(), source.environment(), false);
            if (!owner.uuid().equals(world.getUID())) {
                throw new IOException("Ownership UUID changed; refusing to unload " + world.getKey());
            }
            if (plugin.getServer().unloadWorld(world, true)) {
                sources.remove(world.getUID());
            }
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Source world was retained because ownership could not be verified.", error);
        }
    }

    private Opened open(String name, long seed, World.Environment environment, boolean preview) throws IOException {
        NamespacedKey key = worldKey(name);
        WorldCreator creator = WorldCreator.ofKey(key).seed(seed).environment(environment)
                .type(WorldType.NORMAL).generateStructures(!preview)
                .forcedSpawnPosition(Position.block(0, 80, 0), 0.0f, 0.0f);
        if (preview) creator.generator(new EmptyGenerator());

        // Since Paper 26.1 these are dimension directories inside the main level,
        // not getWorldContainer()/name folders, and UUIDs are no longer in uid.dat.
        Path folder = dimensionPath(name);
        World loaded = plugin.getServer().getWorld(key);
        World byName = plugin.getServer().getWorld(creator.name());
        if (byName != null && byName != loaded) {
            throw new IOException("World name is already used by another key: " + creator.name());
        }
        // Do not let WorldCreator's legacy migration touch an unrelated old-style world.
        for (String legacyName : List.of(name, creator.name())) {
            Path legacy = plugin.getServer().getWorldContainer().toPath().resolve(legacyName);
            if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Legacy world path already exists; refusing automatic migration: " + legacy);
            }
        }

        boolean exists = loaded != null || Files.exists(folder, LinkOption.NOFOLLOW_LINKS);
        Owner owner = null;
        if (exists) {
            owner = readOwner(name); // Missing records fail before any world loading.
            validateOwner(owner, name, seed, environment, preview);
            UUID actualUuid;
            if (loaded != null) {
                verifyWorld(loaded, name, seed, environment, preview);
                actualUuid = loaded.getUID();
            } else {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Owned dimension path is not a regular directory: " + folder);
                }
                actualUuid = readDiskUuid(folder);
            }
            if (!owner.uuid().equals(actualUuid)) {
                throw new IOException("World UUID does not match its ownership record: " + key);
            }
        } else if (Files.exists(ownerPath(name), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ownership record exists but its world is missing: " + key);
        }

        World world = loaded == null ? creator.createWorld() : loaded;
        if (world == null) throw new IOException("Paper did not create or load " + key);
        verifyWorld(world, name, seed, environment, preview);
        if (owner != null && !owner.uuid().equals(world.getUID())) {
            throw new IOException("World UUID changed during loading: " + key);
        }
        applyRules(world, preview);
        if (!exists) {
            // Persist Paper's own UUID before recording ownership, so subsequent opens
            // can verify disk identity without first loading the dimension.
            world.save(true);
            if (!world.getUID().equals(readDiskUuid(folder))) {
                throw new IOException("New world metadata UUID was not persisted correctly: " + key);
            }
            writeOwner(new Owner(name, key.toString(), world.getUID(), seed, environment, preview));
        }
        return new Opened(world, loaded == null);
    }

    private void verifyWorld(World world, String name, long seed,
                             World.Environment environment, boolean preview) throws IOException {
        if (!world.getKey().equals(worldKey(name))
                || !world.getWorldPath().toAbsolutePath().normalize().equals(dimensionPath(name))) {
            throw new IOException("World key or storage path does not match the requested owned dimension.");
        }
        if (world.getSeed() != seed || world.getEnvironment() != environment) {
            throw new IOException("World seed/environment mismatch for " + world.getKey());
        }
        if (preview) {
            if (!(world.getGenerator() instanceof EmptyGenerator)) {
                throw new IOException("Preview world does not use this plugin's empty generator.");
            }
        } else if (world.getGenerator() != null || world.getBiomeProvider() != null) {
            throw new IOException("Source world has a custom Bukkit generator/biome provider: " + world.getKey());
        } else if (!world.canGenerateStructures()) {
            throw new IOException("Source world has structure generation disabled: " + world.getKey());
        }
    }

    private static void applyRules(World world, boolean preview) {
        // These reduce later terrain changes; they do not freeze all physics,
        // scheduled fluid ticks, block entities, world generation, or other plugins.
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        // Vanilla Nether/End have no default world clock in 26.3; setTime would
        // throw there. Their lighting is fixed already. Our NORMAL worlds have
        // the Overworld clock and can be held at noon.
        if (world.getEnvironment() == World.Environment.NORMAL) world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        if (preview) {
            world.setGameRule(GameRule.DO_TILE_DROPS, false);
            world.setGameRule(GameRule.DO_ENTITY_DROPS, false);
            world.setGameRule(GameRule.DO_MOB_LOOT, false);
        }
    }

    private Path dimensionPath(String name) {
        return plugin.getServer().getLevelDirectory().resolve("dimensions")
                .resolve(NAMESPACE).resolve(name).toAbsolutePath().normalize();
    }

    private static NamespacedKey worldKey(String name) {
        return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + name));
    }

    private Path ownerPath(String name) {
        return owners.resolve(name + ".txt");
    }

    private Owner readOwner(String name) throws IOException {
        Path file = ownerPath(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16_384) {
            throw new IOException("Missing or invalid ownership record; refusing to touch world: " + file);
        }
        Properties data = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data.load(reader);
        }
        try {
            if (!"1".equals(data.getProperty("format"))) throw new IllegalArgumentException("Unknown owner format");
            String kind = data.getProperty("kind");
            if (!"source".equals(kind) && !"preview".equals(kind)) throw new IllegalArgumentException("Unknown owner kind");
            return new Owner(data.getProperty("name"), data.getProperty("key"),
                    UUID.fromString(data.getProperty("uuid")), Long.parseLong(data.getProperty("seed")),
                    World.Environment.valueOf(data.getProperty("environment")), kind.equals("preview"));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Malformed ownership record: " + file, error);
        }
    }

    private static void validateOwner(Owner owner, String name, long seed,
                                      World.Environment environment, boolean preview) throws IOException {
        if (!name.equals(owner.name()) || !worldKey(name).toString().equals(owner.key())
                || owner.seed() != seed || owner.environment() != environment || owner.preview() != preview) {
            throw new IOException("Ownership seed/environment/key/kind mismatch for " + name);
        }
    }

    private void writeOwner(Owner owner) throws IOException {
        Files.createDirectories(owners);
        String text = "format=1\nname=" + owner.name() + "\nkey=" + owner.key()
                + "\nuuid=" + owner.uuid() + "\nseed=" + owner.seed()
                + "\nenvironment=" + owner.environment().name()
                + "\nkind=" + (owner.preview() ? "preview" : "source") + "\n";
        Files.writeString(ownerPath(owner.name()), text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Paper 26.3 PaperWorldMetadata.CODEC stores UUIDUtil.CODEC in data.uuid. */
    private static UUID readDiskUuid(Path folder) throws IOException {
        Path metadata = folder.resolve("data/paper/metadata.dat");
        Map<?, ?> root = readNbt(metadata);
        if (!(root.get("data") instanceof Map<?, ?> data) || !(data.get("uuid") instanceof int[] uuid)
                || uuid.length != 4) {
            throw new IOException("Unsupported Paper UUID metadata; refusing to load: " + metadata);
        }
        return new UUID(((long) uuid[0] << 32) | Integer.toUnsignedLong(uuid[1]),
                ((long) uuid[2] << 32) | Integer.toUnsignedLong(uuid[3]));
    }

    /** Small bounded reader: no NMS linkage, mutation, or silent identity fallback. */
    private static Map<?, ?> readNbt(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_NBT_BYTES) {
            throw new IOException("Missing or oversized Paper metadata: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        InputStream raw = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && (bytes[0] & 255) == 0x1f && (bytes[1] & 255) == 0x8b) {
            raw = new GZIPInputStream(raw);
        }
        byte[] decoded;
        try (InputStream stream = raw) {
            decoded = stream.readNBytes(MAX_NBT_BYTES + 1);
        }
        if (decoded.length > MAX_NBT_BYTES) throw new IOException("Paper metadata exceeds decoded size limit.");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decoded))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Paper metadata root is not an NBT compound.");
            input.readUTF();
            return (Map<?, ?>) readTag(input, 10, 0);
        }
    }

    private static Object readTag(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 32) throw new IOException("Paper metadata NBT is too deeply nested.");
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {
                byte[] values = new byte[arrayLength(input)];
                input.readFully(values);
                yield values;
            }
            case 8 -> input.readUTF();
            case 9 -> {
                int itemType = input.readUnsignedByte();
                int length = arrayLength(input);
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) values.add(readTag(input, itemType, depth + 1));
                yield values;
            }
            case 10 -> {
                Map<String, Object> values = new LinkedHashMap<>();
                int itemType;
                while ((itemType = input.readUnsignedByte()) != 0) {
                    if (values.size() >= 16_384) throw new IOException("Paper metadata compound is too large.");
                    String name = input.readUTF();
                    if (values.containsKey(name)) throw new IOException("Duplicate Paper metadata field: " + name);
                    values.put(name, readTag(input, itemType, depth + 1));
                }
                yield values;
            }
            case 11 -> {
                int[] values = new int[arrayLength(input)];
                for (int i = 0; i < values.length; i++) values[i] = input.readInt();
                yield values;
            }
            case 12 -> {
                long[] values = new long[arrayLength(input)];
                for (int i = 0; i < values.length; i++) values[i] = input.readLong();
                yield values;
            }
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        };
    }

    private static int arrayLength(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 65_536) throw new IOException("Invalid Paper metadata array length.");
        return length;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World management must run on the server main thread.");
    }

    private record Owner(String name, String key, UUID uuid, long seed,
                         World.Environment environment, boolean preview) {
    }

    private record Source(String name, long seed, World.Environment environment, boolean mayUnload) {
    }

    private record Opened(World world, boolean loadedHere) {
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateBedrock() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }
}

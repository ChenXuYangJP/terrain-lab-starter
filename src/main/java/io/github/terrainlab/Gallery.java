package io.github.terrainlab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * A paginated block preview, read-only unless /terrain play opens a shared
 * sandbox inside bedrock shells. Sandbox edits stay in the preview world and are
 * cleared by the next render; sample files are only ever read. This deliberately
 * is not an experiment replay: all source dimensions are displayed in one normal
 * world, original absolute height and scheduled updates are not reproduced.
 */
public final class Gallery implements Listener {
    private static final int PAGE_SIZE = 9;
    private static final int STRIDE = 64;
    private static final int PLAY_HEADROOM = 16;
    private static final int DEFAULT_FUSE_TICKS = 80;
    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData BEDROCK = Material.BEDROCK.createBlockData();
    private final TerrainLabPlugin plugin;
    private final SnapshotIO store;
    private final NamespacedKey returnKey;
    private final NamespacedKey labelKey;
    private final NamespacedKey hpKey;
    private final Path stateFile;
    private final BukkitTask ticker;
    private final Map<Integer, Location> anchors = new LinkedHashMap<>();
    private List<Region> previousRegions = new ArrayList<>();
    private List<Region> shown = List.of();
    private List<Region> editBoxes = List.of();
    private List<String> lastIds;
    private String lastMode;
    private World world;
    private UUID managedWorldId;
    private boolean stateLoaded;
    private boolean busy;
    private boolean sandbox;
    private boolean villagerAi;
    private boolean villagerHp;
    private volatile boolean closed;
    private Render render;

    public Gallery(TerrainLabPlugin plugin, SnapshotIO store) {
        this.plugin = plugin;
        this.store = store;
        this.returnKey = new NamespacedKey(plugin, "preview_return");
        this.labelKey = new NamespacedKey(plugin, "preview_label");
        this.hpKey = new NamespacedKey(plugin, "villager_hp_label");
        this.stateFile = plugin.getDataFolder().toPath().resolve("gallery-state.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** After /terrain clearsamples: reset has no sample files to reload; pasted blocks stay until the next render. */
    public void forgetShownSamples() {
        lastIds = null;
    }

    /** Called on the server thread; the first argument is the subcommand. */
    public boolean command(Player player, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "show" -> {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("Usage: /terrain show <sample_id> [core|context]");
                }
                String mode = mode(args, 2);
                String id = args[1];
                beginRead(player, () -> List.of(loadSnapshot(id)), mode, "sample " + id);
                return true;
            }
            case "gallery" -> {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("Usage: /terrain gallery <page> [core|context]");
                }
                int page;
                try { page = Integer.parseInt(args[1]); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Page must be a positive integer."); }
                if (page < 1) throw new IllegalArgumentException("Page starts at 1.");
                String mode = mode(args, 2);
                beginRead(player, () -> {
                    try {
                        List<String> ids = store.listIds();
                        long offset = (long) (page - 1) * PAGE_SIZE;
                        if (offset >= ids.size()) {
                            throw new IllegalArgumentException("No such page; saved samples=" + ids.size()
                                    + ", pages=" + ((ids.size() + PAGE_SIZE - 1) / PAGE_SIZE));
                        }
                        List<TerrainSnapshot> snapshots = new ArrayList<>();
                        for (int i = (int) offset; i < Math.min(ids.size(), offset + PAGE_SIZE); i++) {
                            snapshots.add(store.load(ids.get(i)));
                        }
                        return snapshots;
                    } catch (IOException error) { throw new CompletionException(error); }
                }, mode, "page " + page);
                return true;
            }
            case "goto" -> {
                if (args.length != 2) throw new IllegalArgumentException("Usage: /terrain goto <slot 1..9>");
                int slot;
                try { slot = Integer.parseInt(args[1]); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Slot must be 1..9."); }
                Location anchor = anchors.get(slot);
                if (anchor == null) throw new IllegalArgumentException("This preview has no such slot. Open a gallery first.");
                enter(player, camera(anchor), GameMode.SPECTATOR);
                return true;
            }
            case "play" -> {
                if (args.length > 2) throw new IllegalArgumentException("Usage: /terrain play [slot 1..9]");
                int slot = args.length == 2 ? integer(args[1], 1, PAGE_SIZE, "Slot") : 1;
                if (closed) throw new IllegalArgumentException("Gallery is shutting down.");
                if (busy) throw new IllegalArgumentException("A preview is being prepared. Please wait.");
                if (shown.isEmpty() || anchors.get(slot) == null) {
                    throw new IllegalArgumentException("No finished preview has that slot. Use /terrain show or /terrain gallery first.");
                }
                if (sandbox) enterPlay(player, slot);
                else startSandbox(player, slot);
                return true;
            }
            case "tnt" -> {
                if (args.length > 3) throw new IllegalArgumentException("Usage: /terrain tnt [slot 1..9] [fuse_ticks 0..600]");
                int slot = args.length >= 2 ? integer(args[1], 1, PAGE_SIZE, "Slot") : 1;
                int fuse = args.length == 3 ? integer(args[2], 0, 600, "Fuse ticks") : DEFAULT_FUSE_TICKS;
                if (!sandbox || render != null) throw new IllegalArgumentException("TNT needs the play sandbox; use /terrain play first.");
                Location anchor = anchors.get(slot);
                if (anchor == null) throw new IllegalArgumentException("This preview has no such slot.");
                Location point = anchor.clone().add(0.5, 0.0, 0.5);
                world.spawn(point, TNTPrimed.class, tnt -> {
                    tnt.setFuseTicks(fuse);
                    tnt.setSource(player);
                });
                player.sendMessage("TerrainLab: primed TNT at sample center (" + point.getBlockX() + "," + point.getBlockY()
                        + "," + point.getBlockZ() + "), fuse " + fuse + " ticks. Sandbox results are not saved.");
                return true;
            }
            case "villagerai" -> {
                villagerAi = flag(args, villagerAi, "villagerai");
                int updated = 0;
                if (world != null) {
                    for (Villager villager : world.getEntitiesByClass(Villager.class)) { villager.setAware(villagerAi); updated++; }
                }
                player.sendMessage("TerrainLab: preview villager AI " + (villagerAi
                        ? "on: vanilla behaviour."
                        : "off: villagers act on nothing themselves; gravity, pushing, damage and knockback still apply.")
                        + " Updated " + updated + " villager(s).");
                return true;
            }
            case "villagerhp" -> {
                villagerHp = flag(args, villagerHp, "villagerhp");
                int updated = 0;
                if (world != null) {
                    for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                        if (villagerHp) showHealth(villager);
                        else hideHealth(villager);
                        updated++;
                    }
                }
                player.sendMessage("TerrainLab: preview villager health labels " + (villagerHp
                        ? "on; they replace name-tag names while on." : "off.") + " Updated " + updated + " villager(s).");
                return true;
            }
            case "reset" -> {
                if (args.length != 1) throw new IllegalArgumentException("Usage: /terrain reset");
                if (lastIds == null) throw new IllegalArgumentException("Nothing to reset; use /terrain show or /terrain gallery first.");
                List<String> ids = lastIds;
                beginRead(player, () -> ids.stream().map(this::loadSnapshot).toList(), lastMode, "reset from sample files");
                return true;
            }
            case "leave" -> {
                if (args.length != 1) throw new IllegalArgumentException("Usage: /terrain leave");
                if (!hasReturn(player)) throw new IllegalArgumentException("No saved preview return point.");
                restore(player);
                return true;
            }
            default -> { return false; }
        }
    }

    private static String mode(String[] args, int index) {
        String value = args.length > index ? args[index].toLowerCase(java.util.Locale.ROOT) : "core";
        if (!value.equals("core") && !value.equals("context")) {
            throw new IllegalArgumentException("Preview mode must be core or context.");
        }
        return value;
    }

    private static int integer(String value, int min, int max, String name) {
        int result;
        try { result = Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be " + min + ".." + max + "."); }
        if (result < min || result > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max + ".");
        return result;
    }

    /** No argument toggles; on/off set the flag directly. */
    private static boolean flag(String[] args, boolean current, String command) {
        if (args.length > 2) throw new IllegalArgumentException("Usage: /terrain " + command + " [on|off]");
        if (args.length == 1) return !current;
        return switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("Usage: /terrain " + command + " [on|off]");
        };
    }

    private void showHealth(Villager villager) {
        double health = Math.max(0.0, villager.getHealth());
        AttributeInstance attribute = villager.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? 20.0 : attribute.getValue();
        double ratio = max > 0 ? health / max : 0.0;
        NamedTextColor color = ratio > 0.5 ? NamedTextColor.GREEN : ratio > 0.25 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        villager.customName(Component.text(String.format(java.util.Locale.ROOT, "❤ %.1f / %.0f", health, max), color));
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(hpKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Clears only names this plugin set. */
    private void hideHealth(Villager villager) {
        if (!villager.getPersistentDataContainer().has(hpKey, PersistentDataType.BYTE)) return;
        villager.customName(null);
        villager.setCustomNameVisible(false);
        villager.getPersistentDataContainer().remove(hpKey);
    }

    private void refreshHealthLater(Entity entity) {
        if (!villagerHp || !(entity instanceof Villager villager) || !protectedWorld(entity.getWorld())) return;
        // Damage and healing are applied after their events; read health on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> { if (villagerHp) showHealth(villager); });
    }

    private TerrainSnapshot loadSnapshot(String id) {
        try { return store.load(id); }
        catch (IOException error) { throw new CompletionException(error); }
    }

    private void beginRead(Player player, Supplier<List<TerrainSnapshot>> read,
                           String mode, String description) {
        if (closed) throw new IllegalArgumentException("Gallery is shutting down.");
        if (busy) throw new IllegalArgumentException("A preview is being prepared. Please wait.");
        busy = true;
        UUID requester = player.getUniqueId();
        player.sendMessage("TerrainLab: loading " + description + " (" + mode + ").");
        try {
            plugin.ioExecutor().execute(() -> {
                try {
                    List<TerrainSnapshot> snapshots = read.get();
                    dispatch(() -> prepare(requester, snapshots, mode, description));
                } catch (Throwable error) {
                    dispatch(() -> fail(requester, "Could not read preview", error));
                }
            });
        } catch (RuntimeException error) {
            busy = false;
            throw error;
        }
    }

    private void prepare(UUID requester, List<TerrainSnapshot> snapshots,
                         String mode, String description) {
        if (closed) return;
        try {
            Player player = Bukkit.getPlayer(requester);
            if (player == null || !player.isOnline()) { busy = false; return; }
            world = plugin.previewWorld();
            loadState();
            List<Paste> pastes = new ArrayList<>();
            Map<Integer, Location> nextAnchors = new LinkedHashMap<>();
            for (int i = 0; i < snapshots.size(); i++) {
                TerrainSnapshot snapshot = snapshots.get(i);
                // SnapshotIO.load already validated all arrays and crop consistency
                // on the I/O worker, before any server-thread world access.
                TerrainSnapshot.Grid grid = mode.equals("context") ? snapshot.context() : snapshot.core();
                if (grid == null) throw new IllegalArgumentException(snapshot.id() + " has no " + mode + " grid.");
                if (grid.sizeX() > 59 || grid.sizeY() > 63 || grid.sizeZ() > 59
                        || grid.minDx() < -29 || grid.minDz() < -29
                        || grid.minDx() + grid.sizeX() > 30 || grid.minDz() + grid.sizeZ() > 30) {
                    throw new IllegalArgumentException("Grid is too large for the 64-block preview spacing: " + snapshot.id());
                }
                List<?> source = (List<?>) snapshot.metadata().get("source_origin_xyz");
                int sourceX = ((Number) source.get(0)).intValue();
                int sourceY = ((Number) source.get(1)).intValue();
                int sourceZ = ((Number) source.get(2)).intValue();
                Location anchor = new Location(world, (i % 3) * STRIDE + Math.floorMod(sourceX, 4),
                        96 + Math.floorMod(sourceY, 4), (i / 3) * STRIDE + Math.floorMod(sourceZ, 4));
                Region region = new Region(i + 1, anchor.getBlockX() + grid.minDx(),
                        anchor.getBlockY() + grid.minDy(), anchor.getBlockZ() + grid.minDz(),
                        grid.sizeX(), grid.sizeY(), grid.sizeZ());
                region.validate(world);
                BlockData[] blocks = grid.palette().stream().map(Bukkit::createBlockData).toArray(BlockData[]::new);
                Biome[] biomes = grid.biomePalette().stream().map(key -> {
                    Biome biome = BiomeCatalog.resolve(key);
                    if (biome == null) throw new IllegalArgumentException("Missing biome in this server: " + key);
                    return biome;
                }).toArray(Biome[]::new);
                pastes.add(new Paste(snapshot, grid, region, anchor, blocks, biomes,
                        grid.blocks(), grid.biomes()));
                nextAnchors.put(i + 1, anchor);
            }
            if (pastes.isEmpty()) throw new IllegalArgumentException("No samples to display.");
            // Persist a conservative union before touching blocks. If a render
            // is interrupted, its next replacement still clears every touched region.
            List<Region> union = union(previousRegions, pastes.stream().map(Paste::region).toList());
            Render next = new Render(requester, pastes, union, AIR, description + " / " + mode, null);
            endSandbox();
            render = next;
            persistState(union, () -> {
                if (render != next || closed) return;
                try {
                    Player current = Bukkit.getPlayer(requester);
                    if (current == null || !current.isOnline()) { render = null; busy = false; return; }
                    previousRegions = union;
                    shown = List.of();
                    lastIds = pastes.stream().map(paste -> paste.snapshot().id()).toList();
                    lastMode = mode;
                    anchors.clear();
                    anchors.putAll(nextAnchors);
                    enter(current, camera(pastes.get(0).anchor()), GameMode.SPECTATOR);
                    for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                        if (display.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) display.remove();
                    }
                    next.started = true;
                    current.sendMessage("TerrainLab: building block previews; /terrain goto 1..9, /terrain leave.");
                    current.sendMessage("Visual preview only: source dimension, altitude, biome blending, block-entity data and scheduled updates may differ.");
                } catch (Throwable error) { fail(requester, "Could not enter preview", error); }
            }, error -> fail(requester, "Could not checkpoint preview regions", error));
        } catch (Throwable error) { fail(requester, "Could not prepare preview", error); }
    }

    private void tick() {
        Render current = render;
        if (current == null || !current.started || current.waiting || current.finishing || closed) return;
        try {
            if (!current.ready) { loadCurrentRegion(current); return; }
            int budget = Math.max(1, Math.min(plugin.previewBlocksPerTick(), 20_000));
            Region region = current.region();
            int count = Math.min(budget, region.volume() - current.cursor);
            for (int i = 0; i < count; i++, current.cursor++) {
                int index = current.cursor;
                int dx = index % region.sx();
                int dz = (index / region.sx()) % region.sz();
                int dy = index / (region.sx() * region.sz());
                int x = region.x() + dx, y = region.y() + dy, z = region.z() + dz;
                if (current.filling) {
                    world.getBlockAt(x, y, z).setBlockData(current.fill, false);
                } else {
                    Paste paste = current.pastes.get(current.regionIndex);
                    world.getBlockAt(x, y, z).setBlockData(paste.blocks()[paste.blockIndices()[index]], false);
                    world.setBiome(x, y, z, paste.biomes()[paste.biomeIndices()[index]]);
                }
            }
            if (current.cursor == region.volume()) {
                if (!current.filling) addLabel(current.pastes.get(current.regionIndex));
                releaseTickets(current);
                current.ready = false;
                current.cursor = 0;
                current.regionIndex++;
                if (current.filling && current.regionIndex == current.fillRegions.size()) {
                    current.filling = false;
                    current.regionIndex = 0;
                    // A shell build is fill-only and hands over to its own completion.
                    if (current.pastes.isEmpty()) { current.finishing = true; current.done.run(); }
                } else if (!current.filling && current.regionIndex == current.pastes.size()) {
                    finish(current);
                }
            }
        } catch (Throwable error) { fail(current.requester, "Preview stopped", error); }
    }

    private void loadCurrentRegion(Render current) {
        Region region = current.region();
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = region.x() >> 4; cx <= (region.x() + region.sx() - 1) >> 4; cx++) {
            for (int cz = region.z() >> 4; cz <= (region.z() + region.sz() - 1) >> 4; cz++) chunks.add(new ChunkPos(cx, cz));
        }
        current.waiting = true;
        current.pending = chunks.size();
        for (ChunkPos pos : chunks) {
            world.getChunkAtAsync(pos.x(), pos.z(), true).whenComplete((chunk, error) -> dispatch(() -> {
                if (closed || render != current) return;
                if (error != null) { fail(current.requester, "Could not load preview chunk", error); return; }
                world.addPluginChunkTicket(pos.x(), pos.z(), plugin);
                current.tickets.add(pos);
                // Old labels and sandbox leftovers may sit in chunks that were unloaded
                // when the render began. A page render resets those chunks too; a shell
                // build must keep the labels it was started under.
                if (!current.pastes.isEmpty()) {
                    for (Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof Player)) entity.remove();
                    }
                }
                current.pending--;
                if (current.pending == 0) { current.waiting = false; current.ready = true; }
            }));
        }
    }

    private void addLabel(Paste paste) {
        Location point = paste.anchor().clone().add(0.5, paste.grid().minDy() + paste.grid().sizeY() + 2.0, 0.5);
        String text = "[" + paste.region().slot() + "] " + paste.snapshot().id()
                + "\n" + paste.snapshot().metadata().get("dimension") + " / "
                + paste.snapshot().metadata().get("center_computed_biome")
                + "\nseed=" + paste.snapshot().metadata().get("seed")
                + "\norigin at (" + paste.anchor().getBlockX() + "," + paste.anchor().getBlockY()
                + "," + paste.anchor().getBlockZ() + ")";
        world.spawn(point, TextDisplay.class, display -> {
            display.text(Component.text(text));
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(true);
            display.setLineWidth(300);
            display.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void finish(Render current) {
        current.finishing = true;
        List<Region> regions = current.pastes.stream().map(Paste::region).toList();
        persistState(regions, () -> {
            if (render != current || closed) return;
            previousRegions = regions;
            shown = regions;
            render = null;
            busy = false;
            message(current.requester, "TerrainLab: preview ready (" + current.description + "), "
                    + current.pastes.size() + " sample(s). /terrain goto <slot>, /terrain play [slot], /terrain leave.");
        }, error -> fail(current.requester, "Preview is visible but its final checkpoint failed", error));
    }

    private void startSandbox(Player player, int slot) {
        UUID requester = player.getUniqueId();
        List<Region> edits = shown.stream().map(region -> region.editBox(world)).toList();
        List<Region> clears = shown.stream().map(region -> region.clearBox(world)).toList();
        for (Region region : clears) region.validate(world);
        List<Region> shells = shown.stream().flatMap(region -> region.shell().stream()).toList();
        List<Region> union = union(previousRegions, clears);
        busy = true;
        Render next = new Render(requester, List.of(), shells, BEDROCK, "bedrock shell",
                () -> openSandbox(requester, slot, edits));
        render = next;
        player.sendMessage("TerrainLab: building bedrock shells for the sandbox.");
        // Persist shell and headroom before placing bedrock, so the next render
        // still clears them after a crash during play.
        persistState(union, () -> {
            if (render != next || closed) return;
            previousRegions = union;
            next.started = true;
        }, error -> fail(requester, "Could not checkpoint sandbox regions", error));
    }

    private void openSandbox(UUID requester, int slot, List<Region> edits) {
        render = null;
        plugin.previewSandbox(world, true);
        editBoxes = edits;
        sandbox = true;
        busy = false;
        Player player = Bukkit.getPlayer(requester);
        if (player == null || !player.isOnline()) return;
        try { enterPlay(player, slot); }
        catch (RuntimeException error) { player.sendMessage("TerrainLab: " + error.getMessage()); }
    }

    private void enterPlay(Player player, int slot) {
        enter(player, anchors.get(slot).clone().add(0.5, 0.0, 0.5), GameMode.CREATIVE);
        player.sendMessage("TerrainLab: sandbox open with live physics; edits stay in the preview world and never reach sample files.");
        player.sendMessage("/terrain tnt [slot] [fuse], /terrain villagerai [on|off], /terrain villagerhp [on|off], /terrain reset, /terrain goto <slot>, /terrain leave.");
    }

    /** Called before any re-render: the scene is rebuilt from sample files. */
    private void endSandbox() {
        if (world == null) return;
        if (sandbox) plugin.previewSandbox(world, false);
        sandbox = false;
        editBoxes = List.of();
        for (Player player : world.getPlayers()) {
            if (hasReturn(player) && player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
        }
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) entity.remove();
        }
    }

    private void fail(UUID requester, String message, Throwable error) {
        if (render != null) releaseTickets(render);
        render = null;
        busy = false;
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        message(requester, "TerrainLab: " + message + ": " + cause.getMessage());
        plugin.getLogger().log(Level.WARNING, message, cause);
    }

    private void releaseTickets(Render current) {
        if (world != null) for (ChunkPos pos : current.tickets) world.removePluginChunkTicket(pos.x(), pos.z(), plugin);
        current.tickets.clear();
    }

    private void dispatch(Runnable action) {
        if (closed || !plugin.isEnabled()) return;
        plugin.onMain(() -> { if (!closed) action.run(); });
    }

    private static void message(UUID id, String message) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) player.sendMessage(message);
    }

    private void loadState() throws IOException {
        if (stateLoaded) {
            if (!world.getUID().equals(managedWorldId)) throw new IOException("Preview world UUID changed.");
            return;
        }
        managedWorldId = world.getUID();
        if (Files.isRegularFile(stateFile)) {
            YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile.toFile());
            String expected = state.getString("world_uuid");
            if (expected == null || !managedWorldId.toString().equals(expected)) {
                throw new IOException("Saved preview regions belong to another world UUID; refusing to clear blocks.");
            }
            List<Region> regions = new ArrayList<>();
            for (Map<?, ?> map : state.getMapList("regions")) {
                Region region = new Region(number(map, "slot"), number(map, "x"), number(map, "y"),
                        number(map, "z"), number(map, "size_x"), number(map, "size_y"), number(map, "size_z"));
                region.validate(world);
                regions.add(region);
            }
            if (regions.size() > PAGE_SIZE) throw new IOException("Malformed gallery region count.");
            previousRegions = regions;
        }
        stateLoaded = true;
    }

    private static int number(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IOException("Malformed gallery state: " + key);
        return number.intValue();
    }

    private void persistState(List<Region> regions, Runnable success,
                              java.util.function.Consumer<Throwable> failure) {
        YamlConfiguration state = new YamlConfiguration();
        state.set("world_uuid", world.getUID().toString());
        state.set("regions", regions.stream().map(Region::map).toList());
        String content = state.saveToString();
        try {
            plugin.ioExecutor().execute(() -> {
                Path temp = null;
                try {
                    Files.createDirectories(stateFile.getParent());
                    temp = Files.createTempFile(stateFile.getParent(), ".gallery-", ".tmp");
                    Files.writeString(temp, content, StandardCharsets.UTF_8);
                    try { Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    dispatch(success);
                } catch (Throwable error) { dispatch(() -> failure.accept(error)); }
                finally {
                    if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
                }
            });
        } catch (RuntimeException error) { failure.accept(error); }
    }

    private static List<Region> union(List<Region> old, List<Region> next) {
        Map<Integer, Region> result = new LinkedHashMap<>();
        for (Region region : old) result.put(region.slot(), region);
        for (Region region : next) result.merge(region.slot(), region, Region::union);
        return new ArrayList<>(result.values());
    }

    private static Location camera(Location anchor) {
        return new Location(anchor.getWorld(), anchor.getX() + 26.5, anchor.getY() + 24.0,
                anchor.getZ() + 26.5, 135f, 33f);
    }

    private boolean hasReturn(Player player) {
        return player.getPersistentDataContainer().has(returnKey, PersistentDataType.STRING);
    }

    private void enter(Player player, Location target, GameMode mode) {
        if (!hasReturn(player)) {
            if (protectedWorld(player.getWorld())) {
                throw new IllegalArgumentException("Join the preview from your normal world so a return point can be saved.");
            }
            Location point = player.getLocation();
            String value = String.join(";", point.getWorld().getUID().toString(), Double.toString(point.getX()),
                    Double.toString(point.getY()), Double.toString(point.getZ()), Float.toString(point.getYaw()),
                    Float.toString(point.getPitch()), player.getGameMode().name(),
                    Boolean.toString(player.getAllowFlight()), Boolean.toString(player.isFlying()));
            player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, value);
            // The return record must survive a server crash after changing world or mode.
            player.saveData();
        }
        player.setGameMode(mode);
        if (!player.teleport(target)) {
            restore(player);
            throw new IllegalArgumentException("Preview teleport was cancelled.");
        }
    }

    private void restore(Player player) {
        String value = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        if (value == null) return;
        String[] fields = value.split(";", -1);
        if (fields.length != 9) throw new IllegalArgumentException("Saved preview return point is malformed.");
        World original = Bukkit.getWorld(UUID.fromString(fields[0]));
        if (original == null) {
            player.sendMessage("TerrainLab: original world is not loaded; saved return point retained. Load it and use /terrain leave.");
            return;
        }
        Location point = new Location(original, Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]), Float.parseFloat(fields[4]), Float.parseFloat(fields[5]));
        if (!Double.isFinite(point.getX()) || !Double.isFinite(point.getY()) || !Double.isFinite(point.getZ())) {
            throw new IllegalArgumentException("Saved return coordinates are invalid.");
        }
        GameMode mode = GameMode.valueOf(fields[6]);
        boolean allowFlight = Boolean.parseBoolean(fields[7]);
        boolean flying = Boolean.parseBoolean(fields[8]);
        if (!player.teleport(point)) {
            player.sendMessage("TerrainLab: return teleport cancelled; saved return point retained.");
            return;
        }
        player.setGameMode(mode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.getPersistentDataContainer().remove(returnKey);
        player.saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (hasReturn(player)) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) try { restore(player); }
            catch (RuntimeException error) { player.sendMessage("TerrainLab: " + error.getMessage()); }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (hasReturn(player)) try { restore(player); }
        catch (RuntimeException error) { plugin.getLogger().warning("Preview return retained: " + error.getMessage()); }
    }

    public void close() {
        closed = true;
        ticker.cancel();
        if (render != null) releaseTickets(render);
        render = null;
        busy = false;
        if (sandbox) try { plugin.previewSandbox(world, false); }
        catch (RuntimeException error) { plugin.getLogger().warning("Preview sandbox rules retained until reopen: " + error.getMessage()); }
        sandbox = false;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (hasReturn(player)) try { restore(player); }
            catch (RuntimeException error) { plugin.getLogger().warning("Preview return retained: " + error.getMessage()); }
        }
    }

    private boolean protectedWorld(World candidate) {
        return managedWorldId != null && managedWorldId.equals(candidate.getUID());
    }

    /** True when a preview-world block may not change: always, except inside an edit box of an open sandbox. */
    private boolean locked(Block block) {
        if (!protectedWorld(block.getWorld())) return false;
        if (!sandbox || render != null) return true;
        for (Region box : editBoxes) if (box.contains(block)) return false;
        return true;
    }

    private boolean frozen(World candidate) {
        return protectedWorld(candidate) && (!sandbox || render != null);
    }

    private boolean pistonLocked(BlockPistonEvent event, List<Block> moved) {
        Block piston = event.getBlock();
        if (locked(piston) || locked(piston.getRelative(event.getDirection()))) return true;
        for (Block block : moved) {
            if (locked(block) || locked(block.getRelative(event.getDirection()))) return true;
        }
        return false;
    }

    // These reduce mutation during visual inspection. They do not serialize or
    // freeze the complete Minecraft simulation, and are scoped to our world. An
    // open sandbox allows changes inside its edit boxes only, so nothing leaks into
    // the void, neighbouring slots or the bedrock shells.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fluid(BlockFromToEvent event) { if (locked(event.getBlock()) || locked(event.getToBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void form(BlockFormEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grow(BlockGrowEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spread(BlockSpreadEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void leaves(LeavesDecayEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityBlock(EntityChangeBlockEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        if (frozen(event.getLocation().getWorld())) event.setCancelled(true);
        else event.blockList().removeIf(this::locked);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explodeBlock(BlockExplodeEvent event) {
        if (frozen(event.getBlock().getWorld())) event.setCancelled(true);
        else event.blockList().removeIf(this::locked);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (locked(event.getLocation().getBlock())) { event.setCancelled(true); return; }
        if (!(event.getEntity() instanceof Villager villager) || !protectedWorld(event.getLocation().getWorld())) return;
        // Unaware villagers keep physics but not their own brain, unless /terrain villagerai is on.
        if (!villagerAi) villager.setAware(false);
        if (villagerHp) showHealth(villager);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void villagerDamaged(EntityDamageEvent event) { refreshHealthLater(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void villagerHealed(EntityRegainHealthEvent event) { refreshHealthLater(event.getEntity()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) { if (pistonLocked(event, event.getBlocks())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) { if (pistonLocked(event, event.getBlocks())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        boolean blocked = locked(event.getBlock());
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState state : multi.getReplacedBlockStates()) blocked |= locked(state.getBlock());
        }
        if (blocked) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void destroy(BlockBreakEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void structure(StructureGrowEvent event) {
        if (frozen(event.getLocation().getWorld())) event.setCancelled(true);
        else event.getBlocks().removeIf(state -> locked(state.getBlock()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) {
        if (frozen(event.getBlock().getWorld())) event.setCancelled(true);
        else event.getBlocks().removeIf(state -> locked(state.getBlock()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) { if (protectedWorld(event.getFrom().getWorld())) event.setCancelled(true); }

    private record ChunkPos(int x, int z) { }
    private record Paste(TerrainSnapshot snapshot, TerrainSnapshot.Grid grid, Region region,
                         Location anchor, BlockData[] blocks, Biome[] biomes,
                         int[] blockIndices, int[] biomeIndices) { }
    private record Region(int slot, int x, int y, int z, int sx, int sy, int sz) {
        int volume() { return Math.multiplyExact(Math.multiplyExact(sx, sy), sz); }
        void validate(World world) {
            if (slot < 1 || slot > PAGE_SIZE || sx < 1 || sy < 1 || sz < 1 || sx > 67 || sy > 96 || sz > 67
                    || Math.abs(x) > 512 || Math.abs(z) > 512 || y < world.getMinHeight() || y + sy > world.getMaxHeight()) {
                throw new IllegalArgumentException("Invalid persisted preview region.");
            }
        }
        Map<String, Object> map() {
            return Map.of("slot", slot, "x", x, "y", y, "z", z, "size_x", sx, "size_y", sy, "size_z", sz);
        }
        Region union(Region other) {
            int nx = Math.min(x, other.x), ny = Math.min(y, other.y), nz = Math.min(z, other.z);
            return new Region(slot, nx, ny, nz, Math.max(x + sx, other.x + other.sx) - nx,
                    Math.max(y + sy, other.y + other.sy) - ny, Math.max(z + sz, other.z + other.sz) - nz);
        }
        boolean contains(Block block) {
            int bx = block.getX(), by = block.getY(), bz = block.getZ();
            return bx >= x && bx < x + sx && by >= y && by < y + sy && bz >= z && bz < z + sz;
        }
        /** The pasted sample plus building headroom; sandbox edits are confined here. */
        Region editBox(World world) {
            return new Region(slot, x, y, z, sx, Math.min(sy + PLAY_HEADROOM, world.getMaxHeight() - y), sz);
        }
        /** The edit box plus its bedrock shell, which a later render must clear. */
        Region clearBox(World world) {
            return new Region(slot, x - 1, y - 1, z - 1, sx + 2, editBox(world).sy + 1, sz + 2);
        }
        /** One-block bedrock floor under the sample and walls to 2 above its top; the top stays open. */
        List<Region> shell() {
            int height = sy + 2;
            return List.of(new Region(slot, x - 1, y - 1, z - 1, sx + 2, 1, sz + 2),
                    new Region(slot, x - 1, y, z - 1, 1, height, sz + 2),
                    new Region(slot, x + sx, y, z - 1, 1, height, sz + 2),
                    new Region(slot, x, y, z - 1, sx, height, 1),
                    new Region(slot, x, y, z + sz, sx, height, 1));
        }
    }
    private static final class Render {
        final UUID requester;
        final List<Paste> pastes;
        final List<Region> fillRegions;
        final BlockData fill;
        final String description;
        final Runnable done;
        final Set<ChunkPos> tickets = new HashSet<>();
        boolean started, waiting, ready, finishing;
        boolean filling = true;
        int regionIndex, cursor, pending;
        /** Fills fillRegions with fill, then pastes; with no pastes, runs done instead of finish. */
        Render(UUID requester, List<Paste> pastes, List<Region> fillRegions, BlockData fill,
               String description, Runnable done) {
            this.requester = requester; this.pastes = pastes; this.fillRegions = fillRegions; this.fill = fill;
            this.description = description; this.done = done;
        }
        Region region() { return filling ? fillRegions.get(regionIndex) : pastes.get(regionIndex).region(); }
    }
}

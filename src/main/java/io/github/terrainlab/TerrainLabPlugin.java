package io.github.terrainlab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BiomeSearchResult;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Terrain-only collection. No NPC, TNT, animal, or monster is spawned in source
 * worlds or serialized. Only the preview sandbox primes TNT, on an explicit
 * /terrain tnt, and nothing from it is saved.
 */
public final class TerrainLabPlugin extends JavaPlugin {
    private final Gson gson = new GsonBuilder().setPrettyPrinting()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).disableHtmlEscaping().create();
    private ExecutorService io;
    private SnapshotIO snapshots;
    private OwnedWorlds worlds;
    private Gallery gallery;
    private BukkitTask ticker;
    private JobState job;
    private World sourceWorld;
    private ChunkBatch chunkBatch;
    private Capture capture;
    private boolean running;
    private boolean busy;
    private boolean pauseRequested;
    private volatile boolean closing;
    private long tick;
    private long nextStepTick;
    private long epoch;
    private String phase = "idle";
    private CommandSender observer;
    // Samples of every job checkpoint, by job id. Written on the io thread; -continue reads a
    // snapshot on the main thread, so every access holds its monitor.
    private final Map<String, List<JobState.SampleRef>> samplingIndex = new TreeMap<>();
    private volatile boolean samplingIndexReady;
    private static final String CLEAR_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long CLEAR_CODE_MILLIS = 60_000L;
    private static final Pattern SAMPLE_TEMP = Pattern.compile("\\.sample_[a-z0-9_]{1,100}-[0-9]+\\.tmp");
    private static final Pattern JOB_FILE = Pattern.compile("job_[a-z0-9_]{1,80}(\\.json\\.gz|-report\\.csv)");
    // Pending /terrain clearsamples confirmations by sender; main thread only.
    private final Map<String, PendingClear> pendingClears = new HashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            Files.createDirectories(getDataFolder().toPath().resolve("jobs"));
            snapshots = new SnapshotIO(getDataFolder().toPath().resolve("samples"));
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TerrainLab-file-io"); t.setDaemon(true); return t;
            });
            if (!Files.exists(getDataFolder().toPath().resolve(SeedFiles.WORLD_SEEDS))) saveResource(SeedFiles.WORLD_SEEDS, false);
            io.execute(this::rebuildSamplingIndex);
            worlds = new OwnedWorlds(this);
            gallery = new Gallery(this, snapshots);
            Objects.requireNonNull(getCommand("terrain")).setExecutor(this);
            ticker = getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
            getLogger().info("TerrainLab ready. /terrain catalog, then /terrain start 101 1 overworld minecraft:plains");
        } catch (Exception error) {
            getLogger().severe("Cannot enable TerrainLab: " + error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        closing = true;
        running = false;
        epoch++;
        if (ticker != null) ticker.cancel();
        if (chunkBatch != null) chunkBatch.release();
        capture = null;
        if (gallery != null) gallery.close();
        if (io != null) {
            if (job != null) checkpoint();
            io.shutdown();
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) getLogger().warning("File writes still pending; resume will recover completed pending snapshots.");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        getServer().allowPausing(this, true);
    }

    public ExecutorService ioExecutor() { return io; }
    public int previewBlocksPerTick() { return Math.max(100, Math.min(10000, getConfig().getInt("preview.blocks-per-tick", 2000))); }
    public World previewWorld() {
        try { return worlds.preview(); }
        catch (IOException error) { throw new IllegalStateException("Cannot open preview world: " + error.getMessage(), error); }
    }
    public void previewSandbox(World world, boolean on) { worlds.setPreviewSandbox(world, on); }
    public void onMain(Runnable action) {
        if (closing) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else {
            try { getServer().getScheduler().runTask(this, () -> { if (!closing) action.run(); }); }
            catch (IllegalStateException ignored) { /* The server is shutting down. */ }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("terrainlab.admin")) { sender.sendMessage("Need terrainlab.admin permission."); return true; }
        try {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
            switch (action) {
                case "catalog" -> catalog(sender);
                case "start" -> start(sender, args);
                case "startyml" -> startFromFile(sender, args);
                case "pause", "stop" -> {
                    pauseRequested = true; running = false; getServer().allowPausing(this, !busy); checkpoint();
                    sender.sendMessage("TerrainLab: paused. An in-flight sample finishes saving; use /terrain resume.");
                }
                case "resume" -> resume(sender, args);
                case "status" -> status(sender);
                case "list" -> list(sender, args);
                case "clearsamples" -> clearSamples(sender, args);
                case "show", "gallery", "goto", "play", "tnt", "villagerai", "villagerhp", "reset", "leave" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("Preview commands must be used in-game.");
                    gallery.command(player, args);
                }
                default -> help(sender);
            }
        } catch (Exception error) {
            sender.sendMessage("TerrainLab: " + error.getMessage());
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("/terrain catalog | status | pause | resume [job_id] | list [page]");
        s.sendMessage("/terrain clearsamples [confirm <code>|cancel]  (permanently deletes all samples and job records)");
        s.sendMessage("/terrain start <seed1,seed2,...> <per-biome> [all|overworld|nether|end] [biome_key] [-continue]");
        s.sendMessage("/terrain startyml <per-biome> [all|overworld|nether|end] [biome_key] [-continue]  (seeds from worldseeds.yml)");
        s.sendMessage("-continue: avoid earlier centres of these seeds and top each seed+biome up to <per-biome> in total");
        s.sendMessage("/terrain show <sample_id> [core|context] | gallery <page> [core|context] | goto <1..9> | leave");
        s.sendMessage("/terrain play [slot] | tnt [slot] [fuse_ticks] | villagerai [on|off] | villagerhp [on|off] | reset  (sandbox; never saved to samples)");
        s.sendMessage("Quick check: /terrain start 101 1 overworld minecraft:plains");
    }

    private void catalog(CommandSender sender) {
        Map<String, Object> data = BiomeCatalog.export();
        List<BiomeCatalog.Target> targets = BiomeCatalog.targets(
                getConfig().getStringList("collection.dimensions"), getConfig().getStringList("collection.excluded-biomes"));
        sender.sendMessage("TerrainLab target biome tags (not a guarantee of world-generation reachability):");
        for (String dimension : List.of("overworld", "nether", "end")) {
            long count = targets.stream().filter(t -> t.dimension().equals(dimension)).count();
            sender.sendMessage(dimension + ": " + count);
        }
        sender.sendMessage("Total " + targets.size() + "; catalog saved to plugins/TerrainLab/catalog.json");
        String json = gson.toJson(data);
        io.execute(() -> {
            try { atomicText(getDataFolder().toPath().resolve("catalog.json"), json); }
            catch (IOException error) { onMain(() -> sender.sendMessage("Catalog save failed: " + error.getMessage())); }
        });
    }

    private void start(CommandSender sender, String[] args) throws IOException {
        if (busy || running) throw new IllegalArgumentException("Collection is busy. /terrain pause and wait before starting a new job.");
        String[] positional = withoutContinueFlag(args);
        if (positional.length < 3 || positional.length > 5) {
            throw new IllegalArgumentException("Usage: /terrain start <seeds> <per-biome> [dimension] [biome] [-continue]");
        }
        LinkedHashSet<Long> seeds = new LinkedHashSet<>();
        for (String raw : positional[1].split(",")) seeds.add(Long.parseLong(raw));
        startJob(sender, seeds, Arrays.copyOfRange(positional, 2, positional.length), "the command line", positional.length != args.length);
    }

    private void startFromFile(CommandSender sender, String[] args) throws IOException {
        if (busy || running) throw new IllegalArgumentException("Collection is busy. /terrain pause and wait before starting a new job.");
        String[] positional = withoutContinueFlag(args);
        if (positional.length < 2 || positional.length > 4) {
            throw new IllegalArgumentException("Usage: /terrain startyml <per-biome> [dimension] [biome] [-continue]");
        }
        Path file = getDataFolder().toPath().resolve(SeedFiles.WORLD_SEEDS);
        if (!Files.isRegularFile(file)) {
            saveResource(SeedFiles.WORLD_SEEDS, false);
            throw new IllegalArgumentException("Created plugins/TerrainLab/" + SeedFiles.WORLD_SEEDS + "; uncomment and edit its seeds list, then retry.");
        }
        startJob(sender, SeedFiles.readWorldSeeds(file), Arrays.copyOfRange(positional, 1, positional.length),
                SeedFiles.WORLD_SEEDS, positional.length != args.length);
    }

    private static String[] withoutContinueFlag(String[] args) {
        return Arrays.stream(args).filter(arg -> !arg.equalsIgnoreCase("-continue")).toArray(String[]::new);
    }

    /** Earlier samples for these seeds: every indexed job plus the loaded job, whose index update may still be queued. */
    private List<JobState.SampleRef> earlierSamples(Set<String> seeds) {
        List<JobState.SampleRef> earlier = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        synchronized (samplingIndex) {
            for (List<JobState.SampleRef> samples : samplingIndex.values()) {
                for (JobState.SampleRef s : samples) if (seeds.contains(s.seed()) && ids.add(s.id())) earlier.add(s);
            }
        }
        if (job != null) for (JobState.SampleRef s : job.samples) if (seeds.contains(s.seed()) && ids.add(s.id())) earlier.add(s);
        return earlier;
    }

    /**
     * rest is {per-biome, [dimension], [biome]}; seedSource only labels the start message. With continuation,
     * earlier centres for these seeds are avoided and each bucket is topped up to per-biome in total.
     */
    private void startJob(CommandSender sender, LinkedHashSet<Long> seeds, String[] rest, String seedSource,
                          boolean continuation) throws IOException {
        if (seeds.isEmpty() || seeds.size() > 20) throw new IllegalArgumentException("Provide 1..20 distinct signed 64-bit seeds.");
        int quota = Integer.parseInt(rest[0]);
        if (quota < 1 || quota > 100) throw new IllegalArgumentException("per-biome must be 1..100.");
        List<String> dimensions = rest.length >= 2 && !rest[1].equalsIgnoreCase("all")
                ? List.of(rest[1].toLowerCase(java.util.Locale.ROOT)) : getConfig().getStringList("collection.dimensions");
        List<BiomeCatalog.Target> targets = BiomeCatalog.targets(dimensions, getConfig().getStringList("collection.excluded-biomes"));
        if (rest.length == 3) {
            String wanted = BiomeCatalog.key(BiomeCatalog.resolve(rest[2]));
            targets = targets.stream().filter(t -> t.biome().equals(wanted)).toList();
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("No target biomes match the chosen dimension/tag/filter.");
        if (continuation && !samplingIndexReady) throw new IllegalArgumentException("Still indexing earlier jobs for -continue; retry in a few seconds.");
        JobState created = new JobState();
        created.id = "job_" + Long.toString(System.currentTimeMillis(), 36) + "_" + UUID.randomUUID().toString().substring(0, 8);
        created.createdAt = Instant.now().toString();
        created.minecraftVersion = getServer().getMinecraftVersion();
        created.settings = CollectionSettings.from(getConfig());
        created.perBiome = quota;
        if (quota > created.settings.maxAttempts()) throw new IllegalArgumentException("Quota exceeds per-biome candidate budget.");
        created.catalog = BiomeCatalog.export();
        for (long seed : seeds) for (BiomeCatalog.Target target : targets) {
            JobState.Bucket b = new JobState.Bucket();
            b.seed = Long.toString(seed); b.dimension = target.dimension(); b.biome = target.biome(); created.buckets.add(b);
        }
        if (continuation) {
            created.continuation = true;
            created.avoid = earlierSamples(new java.util.HashSet<>(seeds.stream().map(String::valueOf).toList()));
            // A fresh search order; salt 0 would retrace the earlier jobs' candidates step by step.
            do { created.samplingSalt = secureRandom.nextLong(); } while (created.samplingSalt == 0);
            for (JobState.Bucket b : created.buckets) {
                b.quota = Math.max(0, quota - JobState.existing(created.avoid, b.seed, b.dimension, b.biome));
            }
            if (created.target() == 0) {
                throw new IllegalArgumentException("Every seed+biome already has " + quota + " or more samples; nothing to collect.");
            }
        }
        created.validate();
        releaseSource();
        job = created; observer = sender; epoch++; running = true; pauseRequested = false; nextStepTick = tick + 1;
        getServer().allowPausing(this, false);
        checkpoint();
        sender.sendMessage("TerrainLab: started " + job.id + ", target " + job.target() + " (" + seeds.size()
                + " seeds x " + targets.size() + " biome tags x " + (continuation ? "top-up to " : "") + quota + "). Shortfalls are reported.");
        if (continuation) {
            long full = job.buckets.stream().filter(b -> job.quota(b) == 0).count();
            sender.sendMessage("Continuing: avoiding " + job.avoid.size() + " earlier centre(s) for these seeds; " + full + " of "
                    + job.buckets.size() + " seed+biome bucket(s) already have " + quota + "; collecting " + job.target() + " more.");
        }
        sender.sendMessage("Seeds from " + seedSource + ": " + String.join(", ", seeds.stream().map(String::valueOf).toList()));
        sender.sendMessage("Source worlds/chunks are generated on disk. Large jobs can take substantial time and storage.");
    }

    private void resume(CommandSender sender, String[] args) {
        if (busy || running) throw new IllegalArgumentException("Collection is busy; pause and wait first.");
        if (args.length > 2) throw new IllegalArgumentException("Usage: /terrain resume [job_id]");
        busy = true; pauseRequested = false; phase = "loading checkpoint"; observer = sender;
        getServer().allowPausing(this, false);
        String explicit = args.length == 2 ? args[1] : null;
        long requestEpoch = ++epoch;
        io.execute(() -> {
            try {
                String id = explicit != null ? explicit : Files.readString(getDataFolder().toPath().resolve("latest-job.txt")).trim();
                if (!id.matches("job_[a-z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid job id.");
                JobState loaded = readJob(jobPath(id));
                TerrainSnapshot pending = null;
                if (loaded.pendingSampleId != null && Files.isRegularFile(snapshots.root().resolve(loaded.pendingSampleId + ".json.gz"))) {
                    pending = snapshots.load(loaded.pendingSampleId);
                }
                TerrainSnapshot recovered = pending;
                onMain(() -> {
                    if (requestEpoch != epoch) return;
                    try {
                        if (!Objects.equals(loaded.minecraftVersion, getServer().getMinecraftVersion())) {
                            throw new IllegalArgumentException("Minecraft version changed; start a new job instead of mixing versions.");
                        }
                        for (JobState.Bucket b : loaded.buckets) BiomeCatalog.resolve(b.biome);
                        releaseSource(); job = loaded;
                        if (recovered != null) recoverPending(recovered);
                        job.pendingSampleId = null; job.pendingBucketIndex = -1;
                        running = !job.completed && !pauseRequested; busy = false;
                        phase = running ? "ready" : job.completed ? "complete" : "paused";
                        getServer().allowPausing(this, !running);
                        nextStepTick = tick + 1; checkpoint(); status(sender);
                    } catch (Exception error) { busy = false; fail("Resume failed", error); }
                });
            } catch (Exception error) { onMain(() -> { busy = false; fail("Resume failed", error); }); }
        });
    }

    private void recoverPending(TerrainSnapshot snapshot) {
        if (!snapshot.id().equals(job.pendingSampleId)
                || !job.id.equals(snapshot.metadata().get("job_id"))) throw new IllegalArgumentException("Pending snapshot belongs to another job.");
        JobState.Bucket b = job.buckets.get(job.pendingBucketIndex);
        if (!b.seed.equals(snapshot.metadata().get("seed")) || !b.dimension.equals(snapshot.metadata().get("dimension"))
                || !b.biome.equals(snapshot.metadata().get("requested_biome"))) throw new IllegalArgumentException("Pending snapshot metadata mismatch.");
        List<?> xyz = (List<?>) snapshot.metadata().get("source_origin_xyz");
        accept(b, snapshot.id(), ((Number) xyz.get(0)).intValue(), ((Number) xyz.get(1)).intValue(), ((Number) xyz.get(2)).intValue());
    }

    private void status(CommandSender sender) {
        if (job == null) { sender.sendMessage("TerrainLab: no job loaded; /terrain resume loads the latest saved job."); return; }
        sender.sendMessage(job.id + ": " + job.samples.size() + "/" + job.target() + "; "
                + (job.completed ? "complete" : running ? "running" : "paused") + "; phase=" + phase);
        if (job.bucketIndex < job.buckets.size()) {
            JobState.Bucket b = job.buckets.get(job.bucketIndex);
            sender.sendMessage("seed=" + b.seed + " " + b.dimension + "/" + b.biome + ": " + b.sampleIds.size()
                    + "/" + job.quota(b) + "; candidates=" + b.attempts + "; locates=" + b.locates + "; rejected=" + b.rejected);
        }
        if (job.lastError != null) sender.sendMessage("Last error: " + job.lastError);
        sender.sendMessage("Snapshots: plugins/TerrainLab/samples ; per-biome report: jobs/" + job.id + "-report.csv");
    }

    private void list(CommandSender sender, String[] args) {
        int page = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        if (page < 1) throw new IllegalArgumentException("Page must be at least 1.");
        io.execute(() -> {
            try {
                List<String> ids = snapshots.listIds();
                List<String> selected = ids.stream().skip((long) (page - 1) * 10).limit(10).toList();
                onMain(() -> { sender.sendMessage("Samples: " + ids.size() + ", page " + page); selected.forEach(sender::sendMessage); });
            } catch (Exception error) { onMain(() -> sender.sendMessage("List failed: " + error.getMessage())); }
        });
    }

    /** Step 1 issues a per-sender code; step 2 must repeat it within 60 s. Any mismatch cancels. */
    private void clearSamples(CommandSender sender, String[] args) {
        String key = sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
        if (args.length == 1) {
            requireIdleForClear();
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 6; i++) code.append(CLEAR_CODE_ALPHABET.charAt(secureRandom.nextInt(CLEAR_CODE_ALPHABET.length())));
            pendingClears.put(key, new PendingClear(code.toString(), System.currentTimeMillis() + CLEAR_CODE_MILLIS));
            io.execute(() -> {
                try {
                    ClearTargets targets = clearTargets();
                    onMain(() -> {
                        sender.sendMessage("TerrainLab: this PERMANENTLY deletes " + targets.samples().size() + " sample file(s) and "
                                + targets.jobs().size() + " job file(s) (checkpoints, reports, latest-job.txt), and empties "
                                + SeedFiles.SAMPLING_SEEDS + ". " + SeedFiles.WORLD_SEEDS + ", config and worlds are kept.");
                        sender.sendMessage("Type /terrain clearsamples confirm " + code + " within 60 seconds, or /terrain clearsamples cancel.");
                    });
                } catch (IOException error) { onMain(() -> sender.sendMessage("TerrainLab: could not list files to clear: " + error.getMessage())); }
            });
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("cancel") && args.length == 2) {
            sender.sendMessage(pendingClears.remove(key) != null ? "TerrainLab: clear cancelled." : "TerrainLab: no pending clear.");
            return;
        }
        if (!action.equals("confirm") || args.length != 3) throw new IllegalArgumentException("Usage: /terrain clearsamples [confirm <code>|cancel]");
        PendingClear pending = pendingClears.remove(key);
        if (pending == null) throw new IllegalArgumentException("No pending clear; run /terrain clearsamples first.");
        if (System.currentTimeMillis() > pending.expiresAtMillis()) throw new IllegalArgumentException("Confirmation code expired; run /terrain clearsamples again.");
        if (!pending.code().equals(args[2].toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Confirmation code does not match; request cancelled. Run /terrain clearsamples again.");
        }
        requireIdleForClear();
        // Drop the loaded job first, so no later checkpoint can recreate its file.
        job = null; observer = null; phase = "idle"; epoch++;
        releaseSource();
        gallery.forgetShownSamples();
        sender.sendMessage("TerrainLab: deleting all samples and job records...");
        io.execute(() -> deleteClearTargets(sender));
    }

    private void requireIdleForClear() {
        if (busy || running) throw new IllegalArgumentException("Collection is busy. /terrain pause and wait before clearing samples.");
    }

    /** io thread: only files carrying this plugin's own names are ever selected. */
    private ClearTargets clearTargets() throws IOException {
        List<Path> samples = new ArrayList<>();
        try (var files = Files.newDirectoryStream(snapshots.root())) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean published = name.endsWith(".json.gz")
                        && TerrainSnapshot.ID_PATTERN.matcher(name.substring(0, name.length() - ".json.gz".length())).matches();
                if ((published || SAMPLE_TEMP.matcher(name).matches()) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) samples.add(file);
            }
        }
        List<Path> jobs = new ArrayList<>();
        Path jobsDir = getDataFolder().toPath().resolve("jobs");
        if (Files.isDirectory(jobsDir)) {
            try (var files = Files.newDirectoryStream(jobsDir)) {
                for (Path file : files) {
                    if (JOB_FILE.matcher(file.getFileName().toString()).matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) jobs.add(file);
                }
            }
        }
        Path latest = getDataFolder().toPath().resolve("latest-job.txt");
        if (Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) jobs.add(latest);
        return new ClearTargets(samples, jobs);
    }

    /** io thread; queued after any earlier checkpoint writes, so none can land afterwards. */
    private void deleteClearTargets(CommandSender sender) {
        List<String> errors = new ArrayList<>();
        int samples = 0, jobs = 0;
        try {
            ClearTargets targets = clearTargets();
            samples = deleteFiles(targets.samples(), errors);
            jobs = deleteFiles(targets.jobs(), errors);
        } catch (IOException error) { errors.add(error.getMessage()); }
        synchronized (samplingIndex) { samplingIndex.clear(); }
        boolean indexWritten = true;
        try { writeSamplingCenters(); }
        catch (IOException error) { indexWritten = false; errors.add(SeedFiles.SAMPLING_SEEDS + ": " + error.getMessage()); }
        String summary = "Deleted " + samples + " sample file(s) and " + jobs + " job file(s)"
                + (indexWritten ? "; " + SeedFiles.SAMPLING_SEEDS + " is now empty." : ".")
                + (errors.isEmpty() ? "" : " " + errors.size() + " problem(s); first: " + errors.get(0));
        if (errors.isEmpty()) getLogger().info("Samples cleared by " + sender.getName() + ". " + summary);
        else getLogger().warning("Samples cleared by " + sender.getName() + " with problems. " + summary);
        onMain(() -> sender.sendMessage("TerrainLab: " + summary));
    }

    private static int deleteFiles(List<Path> files, List<String> errors) {
        int deleted = 0;
        for (Path file : files) {
            try { if (Files.deleteIfExists(file)) deleted++; }
            catch (IOException error) { errors.add(file.getFileName() + ": " + error.getMessage()); }
        }
        return deleted;
    }

    private void tick() {
        tick++;
        try {
            if (chunkBatch != null && !chunkBatch.done && tick - chunkBatch.started > job.settings.chunkTimeoutTicks()) {
                chunkBatch.release(); chunkBatch = null; busy = false;
                currentBucket().reject("chunk_load_timeout"); coolDown(); checkpoint();
            }
            if (capture != null) { capture.readSome(); return; }
            if (!running || busy || tick < nextStepTick || job == null) return;
            while (job.bucketIndex < job.buckets.size()) {
                JobState.Bucket b = currentBucket();
                if (b.sampleIds.size() >= job.quota(b) || b.exhausted) { job.bucketIndex++; continue; }
                if (b.attempts >= job.settings.maxAttempts()
                        || ((b.anchorX == null || b.candidatesAtAnchor >= job.settings.candidatesPerLocate()) && b.locates >= job.settings.maxLocates())) {
                    b.exhausted = true; checkpoint(); job.bucketIndex++; continue;
                }
                step(b); return;
            }
            job.completed = true; running = false; phase = "complete";
            getServer().allowPausing(this, true); checkpoint(); releaseSource();
            tell("Finished " + job.id + ": " + job.samples.size() + "/" + job.target()
                    + ". See the report CSV for any shortfalls.");
        } catch (Exception error) { fail("Collection paused", error); }
    }

    private JobState.Bucket currentBucket() { return job.buckets.get(job.bucketIndex); }

    private void step(JobState.Bucket b) throws IOException {
        CollectionSettings s = job.settings;
        long seed = Long.parseLong(b.seed);
        if (sourceWorld == null || sourceWorld.getSeed() != seed || sourceWorld.getEnvironment() != BiomeCatalog.environment(b.dimension)) {
            releaseSource(); phase = "creating/loading natural world";
            sourceWorld = worlds.source(job.id, seed, b.dimension);
            // Creating a vanilla world can be expensive; do no additional work in this tick.
            coolDown(); return;
        }
        if (b.anchorX == null || b.candidatesAtAnchor >= s.candidatesPerLocate()) {
            phase = "locating biome (synchronous API)";
            Random random = randomFor(b, b.locates, 0x7140L);
            boolean mainEnd = b.dimension.equals("end") && b.biome.equals("minecraft:the_end");
            int x = b.locates == 0 || mainEnd ? 0 : random.nextInt(s.originRange() * 2 + 1) - s.originRange();
            int z = b.locates == 0 || mainEnd ? 0 : random.nextInt(s.originRange() * 2 + 1) - s.originRange();
            int y = b.dimension.equals("nether") ? 64 : sourceWorld.getSeaLevel();
            long begin = System.nanoTime();
            BiomeSearchResult found = sourceWorld.locateNearestBiome(new Location(sourceWorld, x, y, z),
                    s.locateRadius(), s.horizontalInterval(), s.verticalInterval(), BiomeCatalog.resolve(b.biome));
            b.locates++; b.candidatesAtAnchor = 0;
            if (System.nanoTime() - begin > 50_000_000L) getLogger().info("Biome locate took "
                    + ((System.nanoTime() - begin) / 1_000_000) + " ms for " + b.biome);
            if (found == null) { b.anchorX = null; b.reject("biome_not_found_in_search"); }
            else {
                b.anchorX = found.getLocation().getBlockX(); b.anchorY = found.getLocation().getBlockY(); b.anchorZ = found.getLocation().getBlockZ();
            }
            coolDown(); checkpoint(); return;
        }
        Random random = randomFor(b, b.attempts, 0xB19EL);
        int x = b.anchorX + (b.candidatesAtAnchor == 0 ? 0 : random.nextInt(s.candidateRadius() * 2 + 1) - s.candidateRadius());
        int z = b.anchorZ + (b.candidatesAtAnchor == 0 ? 0 : random.nextInt(s.candidateRadius() * 2 + 1) - s.candidateRadius());
        b.attempts++; b.candidatesAtAnchor++;
        if (!sourceWorld.getWorldBorder().isInside(new Location(sourceWorld, x, 64, z))) { b.reject("world_border"); coolDown(); return; }
        busy = true; phase = "loading/generating context chunks";
        chunkBatch = new ChunkBatch(sourceWorld, x, z, s, epoch, () -> candidateReady(b, x, z));
        chunkBatch.pump();
    }

    private void candidateReady(JobState.Bucket b, int x, int z) {
        try {
            CollectionSettings s = job.settings;
            SiteFinder.Site site = SiteFinder.find(sourceWorld, b.biome, x, z, b.anchorY,
                    s.sizeY() / 2, s.clearanceRadius(), s.clearanceHeight(), s.solidSupport(), randomFor(b, b.attempts, 0x82F1L).nextLong());
            if (site == null) { rejectCandidate(b, "no_matching_clear_air_center"); return; }
            if (clashes(job.samples, b, x, site.y(), z, s)) { rejectCandidate(b, "near_or_overlapping_prior_sample"); return; }
            if (job.avoid != null && clashes(job.avoid, b, x, site.y(), z, s)) {
                rejectCandidate(b, "near_or_overlapping_earlier_job_sample"); return;
            }
            phase = "reading block/biome data";
            capture = new Capture(b, site, sourceWorld);
        } catch (Exception error) { releaseChunks(); busy = false; fail("Candidate processing failed", error); }
    }

    /** Same seed and dimension, any biome: too close to, or overlapping the context box of, a prior centre. */
    private static boolean clashes(List<JobState.SampleRef> priors, JobState.Bucket b, int x, int y, int z, CollectionSettings s) {
        for (JobState.SampleRef prior : priors) {
            if (!prior.seed().equals(b.seed) || !prior.dimension().equals(b.dimension)) continue;
            long dx = (long) x - prior.x(), dz = (long) z - prior.z();
            boolean close = dx * dx + dz * dz < (long) s.minimumDistance() * s.minimumDistance();
            boolean overlap = Math.abs(dx) < s.sizeX() && Math.abs((long) y - prior.y()) < s.sizeY() && Math.abs(dz) < s.sizeZ();
            if (close || overlap) return true;
        }
        return false;
    }

    private void rejectCandidate(JobState.Bucket b, String reason) {
        b.reject(reason); releaseChunks(); busy = false; coolDown();
        if (b.attempts % 16 == 0) checkpoint();
    }

    private Random randomFor(JobState.Bucket b, int index, long salt) {
        // samplingSalt is 0 except for -continue jobs, which must not retrace earlier jobs' candidates.
        long mixed = Long.parseLong(b.seed) ^ ((long) b.biome.hashCode() << 32) ^ b.dimension.hashCode()
                ^ salt ^ job.samplingSalt ^ (0x9E3779B97F4A7C15L * (index + 1L));
        return new Random(mixed);
    }
    private void coolDown() {
        nextStepTick = tick + (job == null ? 10 : job.settings.cooldownTicks());
        if (!running && !busy) getServer().allowPausing(this, true);
    }
    private void releaseChunks() { if (chunkBatch != null) { chunkBatch.release(); chunkBatch = null; } }
    private void releaseSource() {
        if (sourceWorld != null) { worlds.releaseSourceWorld(sourceWorld); sourceWorld = null; }
    }

    private void accept(JobState.Bucket b, String id, int x, int y, int z) {
        if (!b.sampleIds.contains(id)) b.sampleIds.add(id);
        if (job.samples.stream().noneMatch(s -> s.id().equals(id))) job.samples.add(new JobState.SampleRef(id, b.seed, b.dimension, b.biome, x, y, z));
        recordSamplingCenters();
    }

    private void recordSamplingCenters() {
        if (io == null || io.isShutdown()) return;
        String id = job.id;
        List<JobState.SampleRef> samples = List.copyOf(job.samples);
        io.execute(() -> {
            synchronized (samplingIndex) { samplingIndex.put(id, samples); }
            try { writeSamplingCenters(); }
            catch (IOException error) { getLogger().warning(SeedFiles.SAMPLING_SEEDS + " not updated: " + error.getMessage()); }
        });
    }

    /** io thread: index every job checkpoint, then write samplingseed.yml (also when there are none). */
    private void rebuildSamplingIndex() {
        try (var files = Files.newDirectoryStream(getDataFolder().toPath().resolve("jobs"), "job_*.json.gz")) {
            for (Path file : files) {
                try {
                    JobState state = readJob(file);
                    synchronized (samplingIndex) { samplingIndex.put(state.id, List.copyOf(state.samples)); }
                } catch (Exception error) {
                    getLogger().warning(SeedFiles.SAMPLING_SEEDS + " skipped unreadable " + file.getFileName() + ": " + error.getMessage());
                }
            }
            writeSamplingCenters();
        } catch (IOException error) {
            getLogger().warning(SeedFiles.SAMPLING_SEEDS + " not rebuilt: " + error.getMessage());
        } finally {
            // Unreadable job files are already logged; they must not block -continue forever.
            samplingIndexReady = true;
        }
    }

    private void writeSamplingCenters() throws IOException {
        String text;
        synchronized (samplingIndex) { text = SeedFiles.renderSamplingCenters(samplingIndex); }
        atomicText(getDataFolder().toPath().resolve(SeedFiles.SAMPLING_SEEDS), text);
    }

    private void fail(String prefix, Exception error) {
        pauseRequested = true; running = false; phase = "error"; capture = null; releaseChunks(); busy = false;
        getServer().allowPausing(this, true);
        if (job != null) { job.lastError = prefix + ": " + error.getMessage(); checkpoint(); }
        getLogger().warning(prefix + ": " + error);
        if (observer != null) observer.sendMessage("TerrainLab: " + prefix + ": " + error.getMessage());
    }
    private void tell(String message) {
        getLogger().info(message);
        if (observer != null && !(observer instanceof org.bukkit.command.ConsoleCommandSender)) observer.sendMessage("TerrainLab: " + message);
    }

    private final class ChunkBatch {
        final World world;
        final ArrayDeque<int[]> waiting = new ArrayDeque<>();
        final List<Chunk> tickets = new ArrayList<>();
        final CollectionSettings settings;
        final long batchEpoch;
        final long started = tick;
        final Runnable ready;
        int inFlight;
        boolean active = true;
        boolean done;
        ChunkBatch(World world, int x, int z, CollectionSettings settings, long batchEpoch, Runnable ready) {
            this.world = world; this.settings = settings; this.batchEpoch = batchEpoch; this.ready = ready;
            for (int cx = (x - settings.sizeX() / 2) >> 4; cx <= (x + settings.sizeX() / 2) >> 4; cx++) {
                for (int cz = (z - settings.sizeZ() / 2) >> 4; cz <= (z + settings.sizeZ() / 2) >> 4; cz++) waiting.add(new int[]{cx, cz});
            }
        }
        void pump() {
            if (!active || batchEpoch != epoch) return;
            while (inFlight < settings.chunkConcurrency() && !waiting.isEmpty()) {
                int[] coords = waiting.removeFirst(); inFlight++;
                world.getChunkAtAsync(coords[0], coords[1], true).whenComplete((chunk, error) -> onMain(() -> {
                    if (!active || batchEpoch != epoch) return;
                    inFlight--;
                    if (error != null || chunk == null) {
                        release(); if (chunkBatch == this) chunkBatch = null;
                        busy = false; currentBucket().reject("chunk_generation_error"); coolDown(); checkpoint();
                        getLogger().warning("Chunk generation failed: " + error); return;
                    }
                    chunk.addPluginChunkTicket(TerrainLabPlugin.this); tickets.add(chunk);
                    pump();
                }));
            }
            if (active && waiting.isEmpty() && inFlight == 0 && !done) { done = true; ready.run(); }
        }
        void release() {
            active = false;
            for (Chunk chunk : tickets) chunk.removePluginChunkTicket(TerrainLabPlugin.this);
            tickets.clear(); waiting.clear();
        }
    }

    private final class Capture {
        final JobState.Bucket bucket;
        final SiteFinder.Site site;
        final World world;
        final CollectionSettings settings = job.settings;
        final LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> biomes = new LinkedHashMap<>();
        final int[] blockIds = new int[settings.sizeX() * settings.sizeY() * settings.sizeZ()];
        final int[] biomeIds = new int[blockIds.length];
        final Map<String, Object> metadata = new LinkedHashMap<>();
        int next;
        Capture(JobState.Bucket bucket, SiteFinder.Site site, World world) {
            this.bucket = bucket; this.site = site; this.world = world;
            metadata.put("job_id", job.id); metadata.put("seed", bucket.seed); metadata.put("dimension", bucket.dimension);
            metadata.put("source_world", world.getName()); metadata.put("source_world_uuid", world.getUID().toString());
            metadata.put("source_origin_xyz", List.of(site.x(), site.y(), site.z()));
            metadata.put("requested_biome", bucket.biome); metadata.put("center_noise_biome", site.noiseBiome());
            metadata.put("center_computed_biome", site.computedBiome()); metadata.put("biome_label_rule", "computed_biome_at_origin");
            metadata.put("biome_volume_rule", "stored_noise_biome_per_block");
            metadata.put("capture_started_utc", Instant.now().toString()); metadata.put("capture_start_tick", Bukkit.getCurrentTick());
            metadata.put("minecraft_version", getServer().getMinecraftVersion()); metadata.put("paper_version", getServer().getVersion());
            metadata.put("data_version", getServer().getUnsafe().getDataVersion());
            metadata.put("world_min_y", world.getMinHeight()); metadata.put("world_max_y_exclusive", world.getMaxHeight());
            metadata.put("source_world_time", world.getTime()); metadata.put("source_world_full_time", world.getFullTime());
            metadata.put("source_clock_note", "Paper reports 0 when a dimension has no default world clock; this does not mean midnight.");
            metadata.put("source_weather_storm", world.hasStorm()); metadata.put("source_weather_thunder", world.isThundering());
            metadata.put("source_difficulty", world.getDifficulty().name());
            metadata.put("enabled_datapacks", getServer().getDatapackManager().getEnabledPacks().stream().map(p -> p.getName()).sorted().toList());
            metadata.put("datapack_content_fingerprint", "unknown; names recorded, content hashes not captured");
            metadata.put("feature_flags", world.getFeatureFlags().stream().map(f -> f.getKey().toString()).sorted().toList());
            metadata.put("server_plugins", Arrays.stream(getServer().getPluginManager().getPlugins()).map(p -> p.getName() + "@" + p.getPluginMeta().getVersion()).sorted().toList());
            Map<String, String> rules = new LinkedHashMap<>();
            for (String rule : world.getGameRules()) rules.put(rule, world.getGameRuleValue(rule));
            metadata.put("source_gamerules", rules);
            metadata.put("generation", "WorldCreator natural environment; structures enabled; no custom ChunkGenerator");
            metadata.put("entities_omitted", true); metadata.put("block_entity_nbt_omitted", true);
            metadata.put("scheduled_ticks_omitted", true); metadata.put("fidelity", "blockdata_only");
            metadata.put("capture_atomic", false); metadata.put("capture_note", "Block reads span multiple ticks; source worlds suppress random ticks and mob griefing, not all scheduled updates.");
            metadata.put("eligibility_rule", "air_clearance_above_natural_non_air_block_v1");
            metadata.put("clearance_radius", settings.clearanceRadius()); metadata.put("clearance_height", settings.clearanceHeight());
            metadata.put("require_solid_support", settings.solidSupport()); metadata.put("capture_eligible", true);
            metadata.put("support_kind", site.supportKind());
            metadata.put("support_blockdata", world.getBlockAt(site.x(), site.y() - 1, site.z()).getBlockData().getAsString());
            metadata.put("support_is_liquid", world.getBlockAt(site.x(), site.y() - 1, site.z()).isLiquid());
            metadata.put("candidate_attempt", bucket.attempts); metadata.put("locate_queries", bucket.locates);
            metadata.put("sampling", "bounded random-origin biome locates, random nearby columns, reservoir sampling of matching center heights; not globally uniform");
            Map<String, Object> settingsData = gson.fromJson(gson.toJson(settings),
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            metadata.put("collection_settings", settingsData);
        }
        void readSome() throws IOException {
            long deadline = System.nanoTime() + 4_000_000L;
            int limit = Math.min(blockIds.length, next + settings.readBudget());
            while (next < limit && System.nanoTime() < deadline) {
                int x = next % settings.sizeX() - settings.sizeX() / 2;
                int z = (next / settings.sizeX()) % settings.sizeZ() - settings.sizeZ() / 2;
                int y = next / (settings.sizeX() * settings.sizeZ()) - settings.sizeY() / 2;
                String block = world.getBlockAt(site.x() + x, site.y() + y, site.z() + z).getBlockData().getAsString();
                String biome = BiomeCatalog.key(world.getBiome(site.x() + x, site.y() + y, site.z() + z));
                blockIds[next] = palette.computeIfAbsent(block, ignored -> palette.size());
                biomeIds[next] = biomes.computeIfAbsent(biome, ignored -> biomes.size()); next++;
            }
            if (next == blockIds.length) finish();
        }
        void finish() throws IOException {
            metadata.put("capture_end_tick", Bukkit.getCurrentTick()); metadata.put("capture_finished_utc", Instant.now().toString());
            List<String> blocks = new ArrayList<>(palette.keySet()), biomeKeys = new ArrayList<>(biomes.keySet());
            TerrainSnapshot.Grid context = new TerrainSnapshot.Grid(settings.sizeX(), settings.sizeY(), settings.sizeZ(),
                    -settings.sizeX() / 2, -settings.sizeY() / 2, -settings.sizeZ() / 2, blocks, blockIds, biomeKeys, biomeIds);
            int[] coreBlocks = new int[6875], coreBiomes = new int[6875];
            int i = 0;
            for (int y = -5; y <= 5; y++) for (int z = -12; z <= 12; z++) for (int x = -12; x <= 12; x++) {
                int index = context.indexAt(x, y, z); coreBlocks[i] = blockIds[index]; coreBiomes[i] = biomeIds[index]; i++;
            }
            TerrainSnapshot.Grid core = new TerrainSnapshot.Grid(25, 11, 25, -12, -5, -12, blocks, coreBlocks, biomeKeys, coreBiomes);
            boolean clearanceStillValid = true;
            for (int dy = 0; dy < settings.clearanceHeight(); dy++) {
                for (int dz = -settings.clearanceRadius(); dz <= settings.clearanceRadius(); dz++) {
                    for (int dx = -settings.clearanceRadius(); dx <= settings.clearanceRadius(); dx++) {
                        if (!isAirState(blocks.get(coreBlocks[core.indexAt(dx, dy, dz)]))) clearanceStillValid = false;
                    }
                }
            }
            String savedSupport = blocks.get(coreBlocks[core.indexAt(0, -1, 0)]);
            if (!clearanceStillValid || isAirState(savedSupport) || !savedSupport.equals(metadata.get("support_blockdata"))) {
                capture = null; rejectCandidate(bucket, "center_changed_during_capture"); return;
            }
            metadata.put("center_clearance_rechecked_in_snapshot", true);
            String id = "sample_" + digest(job.id + ":" + bucket.seed + ":" + bucket.dimension + ":" + site.x() + ":" + site.y() + ":" + site.z()).substring(0, 24);
            TerrainSnapshot snapshot = new TerrainSnapshot(1, id, metadata, core, context);
            capture = null; releaseChunks(); phase = "compressing/saving snapshot";
            job.pendingSampleId = id; job.pendingBucketIndex = job.bucketIndex;
            Checkpoint pendingCheckpoint = checkpointData();
            long savingEpoch = epoch;
            io.execute(() -> {
                try {
                    // Publishing the snapshot is conditional on a durable pending pointer.
                    writeCheckpoint(pendingCheckpoint);
                    Map<String, Object> meta = new LinkedHashMap<>(snapshot.metadata());
                    meta.put("terrain_sha256", SnapshotIO.terrainHash(snapshot.context()));
                    snapshots.save(new TerrainSnapshot(1, id, meta, core, context));
                    onMain(() -> {
                        if (savingEpoch != epoch) return;
                        accept(bucket, id, site.x(), site.y(), site.z());
                        job.pendingSampleId = null; job.pendingBucketIndex = -1; job.lastError = null;
                        busy = false; phase = running ? "ready" : "paused"; coolDown(); checkpoint();
                        tell("Saved " + id + " (" + job.samples.size() + "/" + job.target() + ") " + bucket.biome + " at " + site.x() + "," + site.y() + "," + site.z());
                    });
                } catch (Exception error) { onMain(() -> { busy = false; fail("Snapshot save failed; resume can recover an already published file", error); }); }
            });
        }
    }

    private Path jobPath(String id) { return getDataFolder().toPath().resolve("jobs").resolve(id + ".json.gz"); }
    private JobState readJob(Path file) throws IOException {
        JobState loaded;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
            loaded = gson.fromJson(reader, JobState.class);
        }
        if (loaded == null) throw new IllegalArgumentException("Empty job file.");
        loaded.validate();
        return loaded;
    }
    private record Checkpoint(String id, String json, String report) {}
    private record PendingClear(String code, long expiresAtMillis) {}
    private record ClearTargets(List<Path> samples, List<Path> jobs) {}
    private Checkpoint checkpointData() { return new Checkpoint(job.id, gson.toJson(job), reportCsv(job)); }
    private void writeCheckpoint(Checkpoint checkpoint) throws IOException {
        atomicGzip(jobPath(checkpoint.id()), checkpoint.json());
        atomicText(getDataFolder().toPath().resolve("jobs").resolve(checkpoint.id() + "-report.csv"), checkpoint.report());
        atomicText(getDataFolder().toPath().resolve("latest-job.txt"), checkpoint.id() + "\n");
    }
    private void checkpoint() {
        if (job == null || io == null || io.isShutdown()) return;
        Checkpoint checkpoint = checkpointData();
        long checkpointEpoch = epoch;
        io.execute(() -> {
            try {
                writeCheckpoint(checkpoint);
            } catch (IOException error) {
                onMain(() -> {
                    if (job == null || checkpointEpoch != epoch || !checkpoint.id().equals(job.id)) return;
                    pauseRequested = true; running = false; getServer().allowPausing(this, !busy);
                    job.lastError = "Checkpoint save failed: " + error.getMessage();
                    getLogger().severe("Checkpoint save failed: " + error.getMessage());
                    if (observer != null) observer.sendMessage("TerrainLab paused: checkpoint could not be saved. " + error.getMessage());
                });
            }
        });
    }
    private String reportCsv(JobState state) {
        StringBuilder out = new StringBuilder("seed,dimension,biome,target,accepted,shortfall,candidates,locates,finished,rejections\n");
        for (JobState.Bucket b : state.buckets) out.append(csv(b.seed)).append(',').append(csv(b.dimension)).append(',')
                .append(csv(b.biome)).append(',').append(state.quota(b)).append(',').append(b.sampleIds.size()).append(',')
                .append(state.quota(b) - b.sampleIds.size()).append(',').append(b.attempts).append(',').append(b.locates).append(',')
                .append(b.exhausted || b.sampleIds.size() >= state.quota(b)).append(',').append(csv(gson.toJson(b.rejected))).append('\n');
        return out.toString();
    }
    private static String csv(String value) { return '"' + value.replace("\"", "\"\"").replace("\n", " ") + '"'; }
    private static boolean isAirState(String state) {
        return state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void atomicText(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent()); Path temp = Files.createTempFile(target.getParent(), ".terrainlab-", ".tmp");
        try { Files.writeString(temp, value, StandardCharsets.UTF_8); replace(temp, target); }
        finally { Files.deleteIfExists(temp); }
    }
    private static void atomicGzip(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent()); Path temp = Files.createTempFile(target.getParent(), ".terrainlab-", ".tmp");
        try {
            try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8)) { writer.write(value); }
            replace(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void replace(Path temp, Path target) throws IOException {
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { throw new IOException("Checkpoint filesystem must support same-directory atomic moves.", error); }
    }
}

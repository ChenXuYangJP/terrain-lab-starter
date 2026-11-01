package io.github.terrainlab;

import com.google.gson.Gson;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * worldseeds.yml is user input for /terrain startyml. samplingseed.yml is a view
 * derived from job checkpoints; it is only ever written, never read back.
 * samplingseed_unsupported_terrain.yml is written by tools/check_tnt_support.py and
 * only read here, so that -continue does not count the samples it lists.
 */
public final class SeedFiles {
    public static final String WORLD_SEEDS = "worldseeds.yml";
    public static final String SAMPLING_SEEDS = "samplingseed.yml";
    public static final String UNSUPPORTED_TERRAIN = "samplingseed_unsupported_terrain.yml";
    private static final Pattern SAMPLE_ID = Pattern.compile("sample_[a-z0-9_]{1,100}");
    private static final Gson GSON = new Gson();
    private static final String SAMPLING_HEADER = """
            # TerrainLab sampling centers, grouped by world seed.
            # Regenerated from the job checkpoints in jobs/ at server start and after every
            # saved sample; manual edits are overwritten. Deleting a sample file does not
            # remove its entry here.
            #
            # center is the block position [x, y, z] of the sample center in that seed's
            # dimension (metadata.source_origin_xyz). biome is the requested terrain type the
            # center was accepted for.
            #
            # Example entry:
            # world_seeds:
            #   "101":
            #     - sample: "sample_a0535c4b703a358872cadc05"
            #       job: "job_mhf2k3ab_1a2b3c4d"
            #       dimension: "overworld"
            #       biome: "minecraft:plains"
            #       center: [1234, 72, -567]

            """;

    private SeedFiles() {
    }

    /** Reads the seeds list; the 1..20 limit is checked by the caller like any other seed source. */
    public static LinkedHashSet<Long> readWorldSeeds(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (InvalidConfigurationException error) {
            throw new IllegalArgumentException(WORLD_SEEDS + " is not valid YAML: " + error.getMessage());
        }
        if (yaml.contains("seeds") && !yaml.isList("seeds")) {
            throw new IllegalArgumentException(WORLD_SEEDS + ": seeds must be a list, e.g. seeds: [\"101\", \"202\"].");
        }
        List<?> entries = yaml.getList("seeds");
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException(WORLD_SEEDS + " has no seeds; uncomment and edit the sample list in plugins/TerrainLab/"
                    + WORLD_SEEDS + ".");
        }
        LinkedHashSet<Long> seeds = new LinkedHashSet<>();
        for (Object entry : entries) {
            String raw = String.valueOf(entry).trim();
            try { seeds.add(Long.parseLong(raw)); }
            catch (NumberFormatException error) {
                throw new IllegalArgumentException(WORLD_SEEDS + ": seed \"" + raw + "\" is not a decimal signed 64-bit integer.");
            }
        }
        return seeds;
    }

    /** Sample ids in a samplingseed.yml-layout file such as UNSUPPORTED_TERRAIN; a missing file lists none. */
    public static Set<String> readSampleIds(Path file) throws IOException {
        Set<String> ids = new HashSet<>();
        if (!Files.exists(file)) return ids;
        String name = file.getFileName().toString();
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (InvalidConfigurationException error) {
            throw new IllegalArgumentException(name + " is not valid YAML: " + error.getMessage());
        }
        ConfigurationSection seeds = yaml.getConfigurationSection("world_seeds");
        if (seeds == null) throw new IllegalArgumentException(name + " has no world_seeds map.");
        for (String seed : seeds.getKeys(false)) {
            if (!seeds.isList(seed)) throw new IllegalArgumentException(name + ": seed " + seed + " must hold a list of samples.");
            for (Map<?, ?> entry : seeds.getMapList(seed)) {
                if (!(entry.get("sample") instanceof String id) || !SAMPLE_ID.matcher(id).matches()) {
                    throw new IllegalArgumentException(name + ": seed " + seed + " has an entry without a valid sample id.");
                }
                ids.add(id);
            }
        }
        return ids;
    }

    /** Jobs in the given map order, samples in their accepted order, grouped by seed. */
    public static String renderSamplingCenters(Map<String, List<JobState.SampleRef>> byJob) {
        Map<String, List<String>> bySeed = new LinkedHashMap<>();
        for (Map.Entry<String, List<JobState.SampleRef>> job : byJob.entrySet()) {
            for (JobState.SampleRef sample : job.getValue()) {
                bySeed.computeIfAbsent(sample.seed(), seed -> new ArrayList<>()).add(
                        "    - sample: " + quote(sample.id()) + "\n"
                        + "      job: " + quote(job.getKey()) + "\n"
                        + "      dimension: " + quote(sample.dimension()) + "\n"
                        + "      biome: " + quote(sample.biome()) + "\n"
                        + "      center: [" + sample.x() + ", " + sample.y() + ", " + sample.z() + "]\n");
            }
        }
        StringBuilder out = new StringBuilder(SAMPLING_HEADER);
        if (bySeed.isEmpty()) return out.append("world_seeds: {}\n").toString();
        out.append("world_seeds:\n");
        for (Map.Entry<String, List<String>> seed : bySeed.entrySet()) {
            out.append("  ").append(quote(seed.getKey())).append(":\n");
            seed.getValue().forEach(out::append);
        }
        return out.toString();
    }

    // A JSON string literal is also a valid YAML double-quoted scalar.
    private static String quote(String value) {
        return GSON.toJson(value);
    }
}

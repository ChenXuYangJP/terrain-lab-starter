package io.github.terrainlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure data: no Bukkit objects are written to disk or accessed by the writer. */
public final class JobState {
    public int schemaVersion = 1;
    public String id;
    public String createdAt;
    public String minecraftVersion;
    public CollectionSettings settings;
    public int perBiome;
    public int bucketIndex;
    public boolean completed;
    public List<Bucket> buckets = new ArrayList<>();
    public List<SampleRef> samples = new ArrayList<>();
    public Map<String, Object> catalog = new LinkedHashMap<>();
    public String pendingSampleId;
    public int pendingBucketIndex = -1;
    public String lastError;

    public static final class Bucket {
        public String seed;
        public String dimension;
        public String biome;
        public int attempts;
        public int locates;
        public int candidatesAtAnchor;
        public Integer anchorX;
        public Integer anchorY;
        public Integer anchorZ;
        public boolean exhausted;
        public List<String> sampleIds = new ArrayList<>();
        public Map<String, Integer> rejected = new LinkedHashMap<>();
        public void reject(String reason) { rejected.merge(reason, 1, Integer::sum); }
    }
    public record SampleRef(String id, String seed, String dimension, String biome, int x, int y, int z) {}

    public int target() { return Math.multiplyExact(buckets.size(), perBiome); }
    public void validate() {
        if (schemaVersion != 1 || id == null || !id.matches("job_[a-z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid job file.");
        if (settings == null || buckets == null || samples == null || perBiome < 1 || perBiome > 100
                || buckets.isEmpty() || buckets.size() > 5000 || bucketIndex < 0 || bucketIndex > buckets.size()) {
            throw new IllegalArgumentException("Invalid job state or quota.");
        }
        settings.validate();
        for (Bucket b : buckets) {
            Long.parseLong(b.seed);
            if (!List.of("overworld", "nether", "end").contains(b.dimension) || b.biome == null
                    || b.sampleIds == null || b.rejected == null || b.attempts < 0 || b.locates < 0
                    || b.sampleIds.size() > perBiome) throw new IllegalArgumentException("Invalid job bucket.");
        }
        if (pendingSampleId != null && (pendingBucketIndex < 0 || pendingBucketIndex >= buckets.size())) {
            throw new IllegalArgumentException("Invalid pending snapshot recovery state.");
        }
    }
}

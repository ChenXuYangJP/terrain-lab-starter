package io.github.terrainlab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // -continue jobs only; older job files load these as false/0/null.
    public boolean continuation;
    public long samplingSalt;
    public List<SampleRef> avoid;

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
        /** Per-bucket target for -continue top-ups; null means perBiome. */
        public Integer quota;
        public List<String> sampleIds = new ArrayList<>();
        public Map<String, Integer> rejected = new LinkedHashMap<>();
        public void reject(String reason) { rejected.merge(reason, 1, Integer::sum); }
    }
    public record SampleRef(String id, String seed, String dimension, String biome, int x, int y, int z) {}

    public int quota(Bucket b) { return b.quota == null ? perBiome : b.quota; }
    public int target() {
        int total = 0;
        for (Bucket b : buckets) total = Math.addExact(total, quota(b));
        return total;
    }
    /** Distinct earlier centres in one seed/dimension/biome bucket; repeated positions count once. */
    public static int existing(Collection<SampleRef> samples, String seed, String dimension, String biome) {
        Set<List<Object>> positions = new HashSet<>();
        for (SampleRef s : samples) {
            if (s.seed().equals(seed) && s.dimension().equals(dimension) && s.biome().equals(biome)) {
                positions.add(List.of(s.x(), s.y(), s.z()));
            }
        }
        return positions.size();
    }
    public void validate() {
        if (schemaVersion != 1 || id == null || !id.matches("job_[a-z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid job file.");
        if (settings == null || buckets == null || samples == null || perBiome < 1 || perBiome > 100
                || buckets.isEmpty() || buckets.size() > 5000 || bucketIndex < 0 || bucketIndex > buckets.size()
                || (avoid != null && avoid.size() > 100_000)) {
            throw new IllegalArgumentException("Invalid job state or quota.");
        }
        settings.validate();
        for (Bucket b : buckets) {
            Long.parseLong(b.seed);
            if (!List.of("overworld", "nether", "end").contains(b.dimension) || b.biome == null
                    || b.sampleIds == null || b.rejected == null || b.attempts < 0 || b.locates < 0
                    || (b.quota != null && (b.quota < 0 || b.quota > perBiome))
                    || b.sampleIds.size() > quota(b)) throw new IllegalArgumentException("Invalid job bucket.");
        }
        if (avoid != null) {
            for (SampleRef s : avoid) {
                if (s == null || s.seed() == null || s.dimension() == null || s.biome() == null) {
                    throw new IllegalArgumentException("Invalid avoided sample position.");
                }
            }
        }
        if (pendingSampleId != null && (pendingBucketIndex < 0 || pendingBucketIndex >= buckets.size())) {
            throw new IllegalArgumentException("Invalid pending snapshot recovery state.");
        }
    }
}

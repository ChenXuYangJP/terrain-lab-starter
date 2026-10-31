package io.github.terrainlab;

import org.bukkit.configuration.file.FileConfiguration;

/** A copy of the settings is persisted with every job, so resume cannot silently change it. */
public record CollectionSettings(int sizeX, int sizeY, int sizeZ, int maxAttempts,
        int candidatesPerLocate, int locateRadius, int horizontalInterval, int verticalInterval,
        int originRange, int candidateRadius, int minimumDistance, int cooldownTicks,
        int readBudget, int chunkConcurrency, int chunkTimeoutTicks,
        int clearanceRadius, int clearanceHeight, boolean solidSupport) {
    public static CollectionSettings from(FileConfiguration c) {
        CollectionSettings s = new CollectionSettings(
                c.getInt("context.size-x", 41), c.getInt("context.size-y", 25), c.getInt("context.size-z", 41),
                c.getInt("collection.max-candidate-attempts", 160), c.getInt("collection.candidates-per-locate", 16),
                c.getInt("collection.locate-radius", 4096), c.getInt("collection.locate-horizontal-interval", 32),
                c.getInt("collection.locate-vertical-interval", 32), c.getInt("collection.random-origin-range", 60000),
                c.getInt("collection.candidate-radius", 96), c.getInt("collection.minimum-center-distance", 48),
                c.getInt("collection.cooldown-ticks", 10), c.getInt("collection.capture-blocks-per-tick", 1000),
                c.getInt("collection.concurrent-chunk-loads", 2), c.getInt("collection.chunk-load-timeout-ticks", 2400),
                c.getInt("site.clearance-radius", 1), c.getInt("site.clearance-height", 2),
                c.getBoolean("site.require-solid-support", false));
        s.validate();
        return s;
    }
    public void validate() {
        odd(sizeX, 25, 59, "context.size-x"); odd(sizeY, 11, 63, "context.size-y"); odd(sizeZ, 25, 59, "context.size-z");
        range(maxAttempts, 1, 10000, "max-candidate-attempts"); range(candidatesPerLocate, 1, 128, "candidates-per-locate");
        range(locateRadius, 128, 8192, "locate-radius"); range(horizontalInterval, 4, 128, "horizontal-interval");
        range(verticalInterval, 4, 128, "vertical-interval"); range(originRange, 1024, 1000000, "random-origin-range");
        range(candidateRadius, 1, 1024, "candidate-radius"); range(minimumDistance, 0, 4096, "minimum-center-distance");
        range(cooldownTicks, 1, 1200, "cooldown-ticks"); range(readBudget, 100, 10000, "capture-blocks-per-tick");
        range(chunkConcurrency, 1, 4, "concurrent-chunk-loads"); range(chunkTimeoutTicks, 100, 12000, "chunk-timeout");
        range(clearanceRadius, 0, 4, "clearance-radius"); range(clearanceHeight, 1, 5, "clearance-height");
    }
    public int maxLocates() { return (maxAttempts + candidatesPerLocate - 1) / candidatesPerLocate; }
    private static void odd(int v, int min, int max, String key) {
        range(v, min, max, key);
        if (v % 2 != 1) throw new IllegalArgumentException(key + " must be odd.");
    }
    private static void range(int v, int min, int max, String key) {
        if (v < min || v > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
    }
}

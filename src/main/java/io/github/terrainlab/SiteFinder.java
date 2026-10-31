package io.github.terrainlab;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.Objects;
import java.util.Random;

/** Selects terrain sample centers without generating or inspecting any entities. */
public final class SiteFinder {
    private SiteFinder() {
    }

    public record Site(int x, int y, int z, String noiseBiome, String computedBiome,
                       String supportKind) {
    }

    /**
     * Scans every legal center height in this column and uses reservoir sampling
     * to select uniformly among all matching heights. This is conditional on the
     * supplied X/Z column, not a claim of uniform sampling over an entire biome.
     *
     * <p>The caller must already load and retain all context chunks. This method
     * verifies that the clearance area is loaded and never requests generation.
     * It must run on the server's main thread. A typical clearance is radius 1
     * and height 2. Non-air support can be water, lava, leaves, or other blocks;
     * it is not a guarantee of safe or walkable NPC terrain.</p>
     *
     * <p>{@code hintY} deliberately does not restrict or bias the search: the
     * current implementation scans the full legal interval in ascending order.
     * It is retained as a future traversal/efficiency hint.</p>
     */
    public static Site find(World world, String biomeKey, int x, int z, int hintY,
                            int contextHalfY, int clearanceRadius, int clearanceHeight,
                            boolean requireSolidSupport, long selectionSeed) {
        Objects.requireNonNull(world, "world");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("SiteFinder.find must run on the server main thread.");
        }
        if (contextHalfY < 0 || clearanceRadius < 0 || clearanceHeight < 1) {
            throw new IllegalArgumentException(
                    "Context half-height and clearance radius must be nonnegative; clearance height must be positive.");
        }

        int left = Math.subtractExact(x, clearanceRadius);
        int right = Math.addExact(x, clearanceRadius);
        int near = Math.subtractExact(z, clearanceRadius);
        int far = Math.addExact(z, clearanceRadius);
        requireLoaded(world, left, right, near, far);
        Biome target = BiomeCatalog.resolve(biomeKey);

        long minimumY = Math.max((long) world.getMinHeight() + contextHalfY,
                (long) world.getMinHeight() + 1);
        long maximumY = Math.min((long) world.getMaxHeight() - 1 - contextHalfY,
                (long) world.getMaxHeight() - clearanceHeight);
        if (world.getEnvironment() == World.Environment.NETHER) {
            maximumY = Math.min(maximumY, 115L);
        }
        if (minimumY > maximumY) {
            return null;
        }

        Random random = new Random(selectionSeed);
        int eligibleCount = 0;
        Site selected = null;
        for (int y = (int) minimumY; y <= maximumY; y++) {
            Block support = world.getBlockAt(x, y - 1, z);
            if (support.getType().isAir()) {
                continue;
            }
            Biome computedBiome = world.getComputedBiome(x, y, z);
            if (!target.equals(computedBiome)
                    || !hasAirClearance(world, left, right, near, far, y, clearanceHeight)) {
                continue;
            }
            String supportKind = supportKind(support);
            if (requireSolidSupport && !supportKind.equals("solid")) {
                continue;
            }

            eligibleCount++;
            if (random.nextInt(eligibleCount) == 0) {
                selected = new Site(x, y, z,
                        BiomeCatalog.key(world.getBiome(x, y, z)),
                        BiomeCatalog.key(computedBiome), supportKind);
            }
        }
        return selected;
    }

    private static void requireLoaded(World world, int left, int right, int near, int far) {
        for (int chunkX = left >> 4; chunkX <= (right >> 4); chunkX++) {
            for (int chunkZ = near >> 4; chunkZ <= (far >> 4); chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new IllegalStateException("Sample clearance chunk is not loaded: "
                            + chunkX + ", " + chunkZ);
                }
            }
        }
    }

    private static boolean hasAirClearance(World world, int left, int right,
                                           int near, int far, int y, int height) {
        long topExclusive = (long) y + height;
        for (long sampleX = left; sampleX <= right; sampleX++) {
            for (long sampleZ = near; sampleZ <= far; sampleZ++) {
                for (long sampleY = y; sampleY < topExclusive; sampleY++) {
                    if (!world.getBlockAt((int) sampleX, (int) sampleY, (int) sampleZ)
                            .getType().isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String supportKind(Block block) {
        Material material = block.getType();
        if (material == Material.WATER) {
            return "water";
        }
        if (material == Material.LAVA) {
            return "lava";
        }
        return hasFullCubeCollision(block) ? "solid" : "other";
    }

    /**
     * Conservative full-block support: accept exactly one block-local collision
     * box covering [0,1] on all three axes. A bounding envelope alone would
     * incorrectly accept hollow or partial shapes. Material.isSolid is not used.
     */
    private static boolean hasFullCubeCollision(Block block) {
        Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
        if (boxes.size() != 1) {
            return false;
        }
        BoundingBox box = boxes.iterator().next();
        return box.getMinX() == 0.0 && box.getMinY() == 0.0 && box.getMinZ() == 0.0
                && box.getMaxX() == 1.0 && box.getMaxY() == 1.0 && box.getMaxZ() == 1.0;
    }
}

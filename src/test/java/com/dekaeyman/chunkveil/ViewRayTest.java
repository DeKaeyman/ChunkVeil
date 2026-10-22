package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewRayTest {
    // World -64..320, concealed band -64..0, grace 0 unless stated.
    private static ViewRay.Bounds bounds(int grace) {
        return new ViewRay.Bounds(-64, 320, -64, 0, grace);
    }

    private static final class FakeWorld implements ViewRay.Terrain, ViewRay.ChunkSink {
        final Set<String> solid = new HashSet<>();
        final Set<String> unenterableChunks = new HashSet<>();
        final Set<String> alreadyVisible = new HashSet<>();
        final Set<String> revealed = new HashSet<>();

        FakeWorld solid(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
            return this;
        }

        @Override
        public boolean canEnterChunk(int chunkX, int chunkZ) {
            return !unenterableChunks.contains(chunkX + "," + chunkZ);
        }

        @Override
        public boolean isOccluding(int x, int y, int z) {
            return solid.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean alreadyVisible(int chunkX, int chunkZ) {
            return alreadyVisible.contains(chunkX + "," + chunkZ);
        }

        @Override
        public void reveal(int chunkX, int chunkZ) {
            revealed.add(chunkX + "," + chunkZ);
        }
    }

    private static final double DIAGONAL = Math.sqrt(0.5D);

    @Test void diagonalWallCannotBeSeenThrough() {
        // Two blocks touching only at their corner form a closed diagonal
        // wall. Sampling every 1.0 block jumped from (0,0) straight to (1,1)
        // and revealed what was behind it; the voxel walk must hit the wall.
        FakeWorld open = new FakeWorld();
        ViewRay.walk(0.5, -4.5, 0.5, DIAGONAL, 0, DIAGONAL, 40, bounds(0), open, open);
        assertTrue(open.revealed.contains("1,1"), "without the wall the ray reaches chunk 1,1");

        FakeWorld walled = new FakeWorld().solid(1, -5, 0).solid(0, -5, 1);
        ViewRay.walk(0.5, -4.5, 0.5, DIAGONAL, 0, DIAGONAL, 40, bounds(0), walled, walled);
        assertFalse(walled.revealed.contains("1,1"), "the diagonal wall must stop the ray");
        assertTrue(walled.revealed.contains("0,0"), "the chunk the viewer stands in is still revealed");
    }

    @Test void occlusionGraceLetsRaysThroughThinWallsOnly() {
        FakeWorld thin = new FakeWorld().solid(3, -5, 0);
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 40, bounds(1), thin, thin);
        assertTrue(thin.revealed.contains("1,0"), "one block is within a grace of 1");

        FakeWorld thick = new FakeWorld().solid(3, -5, 0).solid(4, -5, 0);
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 40, bounds(1), thick, thick);
        assertFalse(thick.revealed.contains("1,0"), "two consecutive blocks exceed a grace of 1");
    }

    @Test void graceResetsAfterAnOpenBlock() {
        FakeWorld world = new FakeWorld().solid(3, -5, 0).solid(5, -5, 0);
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 40, bounds(1), world, world);
        assertTrue(world.revealed.contains("1,0"), "separated single blocks each fit the grace");
    }

    @Test void raysAboveTheBandRevealNothing() {
        FakeWorld world = new FakeWorld();
        ViewRay.walk(0.5, 70.5, 0.5, 1, 0, 0, 40, bounds(0), world, world);
        assertTrue(world.revealed.isEmpty());
    }

    @Test void rayReachingTheBandRevealsEveryChunkAfterwards() {
        // Looking down a shaft into the band and on: chunks after the ray
        // reached the band are revealed even where it is above the band again.
        FakeWorld world = new FakeWorld();
        ViewRay.walk(0.5, 2.5, 0.5, DIAGONAL, -DIAGONAL, 0, 40, bounds(0), world, world);
        assertTrue(world.revealed.contains("0,0"));
        assertTrue(world.revealed.contains("1,0"));
    }

    @Test void stopsAtAChunkItMayNotEnter() {
        FakeWorld world = new FakeWorld();
        world.unenterableChunks.add("1,0");
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 40, bounds(0), world, world);
        assertTrue(world.revealed.contains("0,0"));
        assertFalse(world.revealed.contains("1,0"));
        assertFalse(world.revealed.contains("2,0"), "never jumps past an unloaded/unowned chunk");
    }

    @Test void stopsAtTheWorldBottom() {
        FakeWorld world = new FakeWorld();
        int visited = ViewRay.walk(0.5, -60.5, 0.5, 0, -1, 0, 40, bounds(0), world, world);
        assertEquals(4, visited, "y -61..-64 only");
    }

    @Test void respectsTheMaximumDistance() {
        FakeWorld world = new FakeWorld();
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 10, bounds(0), world, world);
        assertTrue(world.revealed.contains("0,0"));
        assertFalse(world.revealed.contains("1,0"), "x=16 is beyond 10 blocks");
    }

    @Test void alreadyVisibleChunksAreNotRevealedAgain() {
        FakeWorld world = new FakeWorld();
        world.alreadyVisible.add("0,0");
        ViewRay.walk(0.5, -4.5, 0.5, 1, 0, 0, 40, bounds(0), world, world);
        assertFalse(world.revealed.contains("0,0"));
        assertTrue(world.revealed.contains("1,0"));
    }

    @Test void negativeCoordinatesMapToTheRightChunks() {
        FakeWorld world = new FakeWorld();
        ViewRay.walk(-0.5, -4.5, -0.5, -1, 0, 0, 20, bounds(0), world, world);
        assertTrue(world.revealed.contains("-1,-1"));
        assertTrue(world.revealed.contains("-2,-1"), "x <= -17 is chunk -2");
        assertFalse(world.revealed.contains("0,-1"));
    }

    @Test void visitsEveryVoxelExactlyOnceAlongAnAxis() {
        FakeWorld world = new FakeWorld();
        int visited = ViewRay.walk(0.5, 70.5, 0.5, 1, 0, 0, 10, bounds(0), world, world);
        assertEquals(11, visited, "x 0..10");
    }
}

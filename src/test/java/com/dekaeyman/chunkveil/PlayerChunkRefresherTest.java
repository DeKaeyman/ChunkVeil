package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerChunkRefresherTest {
    // Must match net.minecraft.world.level.ChunkPos#asLong exactly, or the
    // chunk holder lookup silently misses and every reveal falls back to the
    // broadcast refresh.
    private static long vanillaAsLong(int x, int z) {
        return (long) x & 4294967295L | ((long) z & 4294967295L) << 32;
    }

    @Test void chunkKeyMatchesVanillaPackingForAllQuadrants() {
        int[][] positions = {{0, 0}, {3, -7}, {-3, 7}, {-1, -1}, {29_999_984 >> 4, -(29_999_984 >> 4)}};
        for (int[] pos : positions) {
            assertEquals(vanillaAsLong(pos[0], pos[1]), PlayerChunkRefresher.chunkKey(pos[0], pos[1]),
                    "chunk " + pos[0] + "," + pos[1]);
        }
    }
}

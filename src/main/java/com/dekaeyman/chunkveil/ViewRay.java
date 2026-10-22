package com.dekaeyman.chunkveil;

/**
 * One view-reveal ray, walked voxel by voxel (Amanatides–Woo DDA).
 *
 * The previous walk sampled the ray every 1.0 block. That missed voxels the
 * ray only clipped, so a ray could pass between two diagonal occluders and
 * reveal what was behind a thin diagonal wall; it also allocated a Block
 * object and did two settings map lookups per sample. The DDA visits every
 * voxel the ray actually crosses, in order, with no allocation per step.
 *
 * Reveal rules are unchanged: once the ray has reached the concealed band,
 * every chunk it still passes through is revealed; the walk stops at the world
 * height limits, at a chunk it may not enter (unloaded or, on Folia, owned by
 * another region), after more than {@code occlusionGrace} consecutive
 * occluding blocks, or past {@code maxDistance}.
 */
final class ViewRay {
    /** World access for the walk; implementations must be cheap per call. */
    interface Terrain {
        boolean canEnterChunk(int chunkX, int chunkZ);

        boolean isOccluding(int x, int y, int z);
    }

    /** Receives chunks the ray reveals. */
    interface ChunkSink {
        boolean alreadyVisible(int chunkX, int chunkZ);

        void reveal(int chunkX, int chunkZ);
    }

    /** Everything the walk needs from settings, resolved once per scan. */
    record Bounds(int worldMinY, int worldMaxY, int hideMinY, int hideBelowY, int occlusionGrace) {
    }

    private ViewRay() {
    }

    /**
     * Walks from the origin along a unit direction vector. Returns the number
     * of voxels visited (for diagnostics and tests).
     */
    static int walk(double originX, double originY, double originZ,
            double dirX, double dirY, double dirZ,
            double maxDistance, Bounds bounds, Terrain terrain, ChunkSink sink) {
        int x = floor(originX);
        int y = floor(originY);
        int z = floor(originZ);

        int stepX = Double.compare(dirX, 0.0D);
        int stepY = Double.compare(dirY, 0.0D);
        int stepZ = Double.compare(dirZ, 0.0D);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(dirX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(dirY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(dirZ);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1 - originX : originX - x) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1 - originY : originY - y) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1 - originZ : originZ - z) * tDeltaZ;

        int checkedChunkX = Integer.MIN_VALUE;
        int checkedChunkZ = Integer.MIN_VALUE;
        int revealedChunkX = Integer.MIN_VALUE;
        int revealedChunkZ = Integer.MIN_VALUE;
        boolean currentChunkAlreadyVisible = false;
        boolean reachedHiddenLayer = false;
        int occludingBlocks = 0;
        int visited = 0;
        double travelled = 0.0D;

        while (travelled <= maxDistance) {
            if (y < bounds.worldMinY() || y >= bounds.worldMaxY()) {
                break;
            }
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (chunkX != checkedChunkX || chunkZ != checkedChunkZ) {
                checkedChunkX = chunkX;
                checkedChunkZ = chunkZ;
                if (!terrain.canEnterChunk(chunkX, chunkZ)) {
                    break;
                }
                currentChunkAlreadyVisible = sink.alreadyVisible(chunkX, chunkZ);
            }
            visited++;

            if (y >= bounds.hideMinY() && y < bounds.hideBelowY()) {
                reachedHiddenLayer = true;
            }
            if (reachedHiddenLayer && (chunkX != revealedChunkX || chunkZ != revealedChunkZ)) {
                if (!currentChunkAlreadyVisible) {
                    sink.reveal(chunkX, chunkZ);
                }
                revealedChunkX = chunkX;
                revealedChunkZ = chunkZ;
            }

            if (terrain.isOccluding(x, y, z)) {
                occludingBlocks++;
                if (occludingBlocks > bounds.occlusionGrace()) {
                    break;
                }
            } else {
                occludingBlocks = 0;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    travelled = tMaxX;
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    travelled = tMaxZ;
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                travelled = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                travelled = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (travelled == Double.POSITIVE_INFINITY) {
                break;
            }
        }
        return visited;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}

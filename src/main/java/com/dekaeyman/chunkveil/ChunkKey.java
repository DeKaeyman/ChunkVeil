package com.dekaeyman.chunkveil;

record ChunkKey(String worldName, int x, int z) {
    static ChunkKey of(String worldName, int x, int z) {
        return new ChunkKey(worldName, x, z);
    }
}

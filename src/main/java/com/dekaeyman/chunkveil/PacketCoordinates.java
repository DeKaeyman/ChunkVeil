package com.dekaeyman.chunkveil;

/** Version-independent coordinate decoding shared by protected packet handlers and tests. */
final class PacketCoordinates {
    private PacketCoordinates() {
    }

    static BlockCoordinate modernMultiBlock(int sectionX, int sectionY, int sectionZ, short packed) {
        int value = packed & 0xFFFF;
        return new BlockCoordinate(
                (sectionX << 4) + ((value >>> 8) & 0xF),
                (sectionY << 4) + (value & 0xF),
                (sectionZ << 4) + ((value >>> 4) & 0xF));
    }

    static BlockCoordinate fixedPointSound(int fixedX, int fixedY, int fixedZ) {
        return new BlockCoordinate(fixedX >> 3, fixedY >> 3, fixedZ >> 3);
    }

    static BlockCoordinate floored(double x, double y, double z) {
        return new BlockCoordinate((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    record BlockCoordinate(int x, int y, int z) {
    }
}

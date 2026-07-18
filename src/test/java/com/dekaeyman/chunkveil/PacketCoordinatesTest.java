package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PacketCoordinatesTest {
    @Test void decodesModernMultiBlockOrigin() {
        assertEquals(new PacketCoordinates.BlockCoordinate(0, 0, 0),
                PacketCoordinates.modernMultiBlock(0, 0, 0, (short) 0));
    }

    @Test void decodesModernMultiBlockLocalAxes() {
        assertEquals(new PacketCoordinates.BlockCoordinate(15, 13, 14),
                PacketCoordinates.modernMultiBlock(0, 0, 0, (short) 0x0FED));
    }

    @Test void decodesModernMultiBlockPositiveSection() {
        assertEquals(new PacketCoordinates.BlockCoordinate(39, 42, 57),
                PacketCoordinates.modernMultiBlock(2, 2, 3, (short) 0x079A));
    }

    @Test void decodesModernMultiBlockNegativeSection() {
        assertEquals(new PacketCoordinates.BlockCoordinate(-15, -30, -47),
                PacketCoordinates.modernMultiBlock(-1, -2, -3, (short) 0x0112));
    }

    @Test void decodesModernMultiBlockUnsignedShort() {
        assertEquals(new PacketCoordinates.BlockCoordinate(15, 15, 15),
                PacketCoordinates.modernMultiBlock(0, 0, 0, (short) 0xFFFF));
    }

    @Test void decodesExactPositiveSoundFixedPoint() {
        assertEquals(new PacketCoordinates.BlockCoordinate(12, 64, 3),
                PacketCoordinates.fixedPointSound(96, 512, 24));
    }

    @Test void floorsPositiveSoundFixedPoint() {
        assertEquals(new PacketCoordinates.BlockCoordinate(1, 2, 3),
                PacketCoordinates.fixedPointSound(15, 23, 31));
    }

    @Test void floorsNegativeSoundFixedPoint() {
        assertEquals(new PacketCoordinates.BlockCoordinate(-1, -2, -3),
                PacketCoordinates.fixedPointSound(-1, -9, -17));
    }

    @Test void floorsExplosionCoordinates() {
        assertEquals(new PacketCoordinates.BlockCoordinate(10, 20, 30),
                PacketCoordinates.floored(10.999, 20.5, 30.001));
    }

    @Test void floorsNegativeParticleAndVibrationCoordinates() {
        assertEquals(new PacketCoordinates.BlockCoordinate(-11, -21, -31),
                PacketCoordinates.floored(-10.001, -20.5, -30.999));
    }
}

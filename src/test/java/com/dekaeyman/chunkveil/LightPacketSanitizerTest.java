package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LightPacketSanitizerTest {
    @Test
    void darkensSkyAndBlockLightInFullyHiddenSections() {
        BitSet mask = bits(1, 2, 3, 4, 5);
        List<byte[]> sky = updates(5, (byte) 0x7F);
        List<byte[]> block = updates(5, (byte) 0x33);

        int changed = LightPacketSanitizer.sanitize(mask, sky, mask, block, -64, 0);

        assertEquals(8, changed);
        for (int index = 0; index < 4; index++) {
            assertArrayEquals(new byte[2048], sky.get(index));
            assertArrayEquals(new byte[2048], block.get(index));
        }
        assertArrayEquals(filled((byte) 0x7F), sky.get(4));
        assertArrayEquals(filled((byte) 0x33), block.get(4));
    }

    @Test
    void preservesSectionCrossedByCutoff() {
        BitSet mask = bits(1, 2);
        List<byte[]> sky = updates(2, (byte) 0x55);

        int changed = LightPacketSanitizer.sanitize(mask, sky, new BitSet(), new ArrayList<>(), 0, 20);

        assertEquals(1, changed);
        assertArrayEquals(new byte[2048], sky.get(0));
        assertArrayEquals(filled((byte) 0x55), sky.get(1));
    }

    @Test
    void followsSparseMaskOrder() {
        BitSet mask = bits(1, 4, 7);
        List<byte[]> updates = updates(3, (byte) 1);

        int changed = LightPacketSanitizer.sanitize(mask, updates, new BitSet(), new ArrayList<>(), 0, 64);

        assertEquals(2, changed);
        assertArrayEquals(new byte[2048], updates.get(0));
        assertArrayEquals(new byte[2048], updates.get(1));
        assertArrayEquals(filled((byte) 1), updates.get(2));
    }

    @Test
    void rejectsMaskAndDataMismatch() {
        assertThrows(IllegalArgumentException.class, () -> LightPacketSanitizer.sanitize(
                bits(1, 2), updates(1, (byte) 1), new BitSet(), new ArrayList<>(), 0, 64));
    }

    private static BitSet bits(int... values) {
        BitSet bits = new BitSet();
        for (int value : values) {
            bits.set(value);
        }
        return bits;
    }

    private static List<byte[]> updates(int count, byte value) {
        List<byte[]> updates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            updates.add(filled(value));
        }
        return updates;
    }

    private static byte[] filled(byte value) {
        byte[] data = new byte[2048];
        java.util.Arrays.fill(data, value);
        return data;
    }
}

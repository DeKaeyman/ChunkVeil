package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The optimized rewriter must produce exactly the bytes the original
 * implementation produced, for every container shape and band placement.
 */
final class ChunkRewriterEquivalenceTest {
    private static final int[] AIRS = {0, 13, 12};
    private static final int FAKE = 100;

    @Test
    void matchesReferenceOnRandomChunks() {
        Random random = new Random(1234);
        for (int round = 0; round < 1500; round++) {
            boolean leInt = random.nextBoolean();
            boolean prefixed = random.nextBoolean();
            int fake = random.nextInt(8) == 0 ? 13 : FAKE; // sometimes an air-like fake block
            int minHeight = random.nextBoolean() ? -64 : 0;
            int sections = 1 + random.nextInt(6);
            int maxHeight = minHeight + sections * 16;
            int hideMinY = minHeight - 8 + random.nextInt(sections * 16 + 16);
            int hideBelowY = minHeight - 8 + random.nextInt(sections * 16 + 24);
            boolean hideAir = random.nextBoolean();

            byte[] chunk = randomChunk(random, sections, leInt, prefixed);
            ChunkPacketBlockRewriter optimized = new ChunkPacketBlockRewriter(fake, AIRS, leInt, prefixed);
            ReferenceChunkRewriter reference = new ReferenceChunkRewriter(fake, AIRS, leInt, prefixed);

            ChunkPacketBlockRewriter.RewriteResult actual =
                    optimized.rewriteBuffer(chunk.clone(), minHeight, maxHeight, hideMinY, hideBelowY, hideAir);
            ReferenceChunkRewriter.RewriteResult expected =
                    reference.rewriteBuffer(chunk.clone(), minHeight, maxHeight, hideMinY, hideBelowY, hideAir);

            String context = "round " + round + " leInt=" + leInt + " prefixed=" + prefixed + " band=["
                    + hideMinY + "," + hideBelowY + ") hideAir=" + hideAir + " fake=" + fake;
            assertArrayEquals(expected.buffer(), actual.buffer(), context);
            assertEquals(expected.hiddenSections(), actual.hiddenSections(), context);
            assertEquals(expected.rewrittenSections(), actual.rewrittenSections(), context);
        }
    }

    @Test
    void directOutputUsesTheGlobalPaletteWidth() {
        // One partially hidden section with 300 distinct states (all ids
        // below 2^14) needs a direct palette. The client reads direct data
        // with the registry width (15 bits on 1.21.x), so 14 bits would
        // misparse it.
        int[] states = new int[4096];
        for (int i = 0; i < 4096; i++) {
            states[i] = 1000 + (i % 300);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x10);
        out.write(0x00);
        out.write(15);
        writeData(out, states, 15, false);
        out.write(0);
        writeVarInt(out, 1);
        byte[] chunk = out.toByteArray();

        ChunkPacketBlockRewriter rewriter = new ChunkPacketBlockRewriter(FAKE, AIRS, false, false, 15);
        // Hide only y 0..7: the upper half keeps all 300 states.
        byte[] result = rewriter.rewriteBuffer(chunk, 0, 16, 0, 8, false).buffer();

        assertEquals(15, result[2] & 0xFF, "direct palette must use the global width");
        int longs = (4096 + 4 - 1) / 4; // 15 bits -> 4 entries per long
        assertEquals(2 + 1 + longs * 8 + 2, result.length, "data sized for 15 bits, then the biome container");
        assertEquals(15, rewriter.directPaletteBits(1299));
        assertEquals(15, new ChunkPacketBlockRewriter(FAKE, AIRS, false, true, 15).directPaletteBits(9));
    }

    @Test
    void reusedScratchDoesNotLeakBetweenChunks() {
        // Large then small chunk on the same thread: stale palette entries or
        // buffer contents from the first must not show up in the second.
        Random random = new Random(99);
        ChunkPacketBlockRewriter optimized = new ChunkPacketBlockRewriter(FAKE, AIRS, false, false);
        ReferenceChunkRewriter reference = new ReferenceChunkRewriter(FAKE, AIRS, false, false);
        for (int i = 0; i < 50; i++) {
            int sections = i % 2 == 0 ? 24 : 1;
            byte[] chunk = randomChunk(random, sections, false, false);
            assertArrayEquals(
                    reference.rewriteBuffer(chunk, 0, sections * 16, 0, sections * 16, false).buffer(),
                    optimized.rewriteBuffer(chunk, 0, sections * 16, 0, sections * 16, false).buffer());
        }
    }

    private static byte[] randomChunk(Random random, int sections, boolean leInt, boolean prefixed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int s = 0; s < sections; s++) {
            int count = random.nextInt(4097);
            if (leInt) {
                out.write(count);
                out.write(count >>> 8);
                out.write(0);
                out.write(0);
            } else {
                out.write(count >>> 8);
                out.write(count);
            }
            writeBlocks(random, out, prefixed);
            writeBiomes(random, out, prefixed);
        }
        if (random.nextInt(4) == 0) {
            out.write(0x55); // trailing bytes are carried through
            out.write(0x66);
        }
        return out.toByteArray();
    }

    private static int randomState(Random random) {
        int roll = random.nextInt(10);
        if (roll < 3) {
            return AIRS[random.nextInt(AIRS.length)];
        }
        if (roll == 3) {
            return FAKE;
        }
        return 1 + random.nextInt(roll == 4 ? 30000 : 40);
    }

    private static void writeBlocks(Random random, ByteArrayOutputStream out, boolean prefixed) {
        int shape = random.nextInt(4);
        if (shape == 0) {
            out.write(0);
            writeVarInt(out, randomState(random));
            if (prefixed) {
                writeVarInt(out, 0);
            }
            return;
        }
        if (shape == 3) {
            int bits = 9 + random.nextInt(7);
            out.write(bits);
            int[] values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = randomState(random) & ((1 << bits) - 1);
            }
            writeData(out, values, bits, prefixed);
            return;
        }
        int bits = 1 + random.nextInt(8);
        int paletteSize = 1 + random.nextInt(Math.min(1 << bits, shape == 1 ? 6 : 256));
        out.write(bits);
        writeVarInt(out, paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            writeVarInt(out, randomState(random));
        }
        int[] values = new int[4096];
        boolean layered = random.nextBoolean();
        for (int i = 0; i < 4096; i++) {
            // Occasionally an index past the palette, which reads as state 0.
            int index = random.nextInt(50) == 0 ? random.nextInt(1 << bits) : random.nextInt(paletteSize);
            values[i] = layered ? (i >> 8) % paletteSize : index;
        }
        writeData(out, values, bits, prefixed);
    }

    private static void writeBiomes(Random random, ByteArrayOutputStream out, boolean prefixed) {
        int bits = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(6);
        out.write(bits);
        if (bits == 0) {
            writeVarInt(out, random.nextInt(60));
            if (prefixed) {
                writeVarInt(out, 0);
            }
            return;
        }
        if (bits <= 3) {
            int size = 1 + random.nextInt(1 << bits);
            writeVarInt(out, size);
            for (int i = 0; i < size; i++) {
                writeVarInt(out, random.nextInt(60));
            }
        }
        int[] values = new int[64];
        for (int i = 0; i < 64; i++) {
            values[i] = random.nextInt(1 << Math.min(bits, 3));
        }
        writeData(out, values, bits, prefixed);
    }

    private static void writeData(ByteArrayOutputStream out, int[] values, int bits, boolean prefixed) {
        int perLong = 64 / bits;
        long[] packed = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            packed[i / perLong] |= ((long) values[i]) << ((i % perLong) * bits);
        }
        if (prefixed) {
            writeVarInt(out, packed.length);
        }
        for (long value : packed) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xFF);
            }
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }
}

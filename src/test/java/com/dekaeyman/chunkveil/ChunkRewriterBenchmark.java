package com.dekaeyman.chunkveil;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Manual micro-benchmark for the chunk rewriter (not run by the test suite).
 * Run: java -cp build/classes/java/main:build/classes/java/test
 * com.dekaeyman.chunkveil.ChunkRewriterBenchmark
 *
 * Uses a realistic overworld chunk: 24 sections (y -64..320), the lower 4
 * concealed (cutoff 0), cave-riddled sections with ores, mixed sections near
 * the surface, and mostly-air sections above.
 */
public final class ChunkRewriterBenchmark {
    private static final int AIR = 0;
    private static final int CAVE_AIR = 13;
    private static final int FAKE = 100;

    private ChunkRewriterBenchmark() {
    }

    public static void main(String[] args) {
        byte[] chunk = realisticChunk(new Random(42));
        ChunkPacketBlockRewriter rewriter =
                new ChunkPacketBlockRewriter(FAKE, new int[]{AIR, CAVE_AIR, 12}, false, false);

        for (boolean hideAir : new boolean[]{false, true}) {
            for (int i = 0; i < 20_000; i++) {
                rewriter.rewriteBuffer(chunk, -64, 320, -64, 0, hideAir);
            }
            int iterations = 50_000;
            long allocatedBefore = allocatedBytes();
            long start = System.nanoTime();
            long sink = 0;
            for (int i = 0; i < iterations; i++) {
                sink += rewriter.rewriteBuffer(chunk, -64, 320, -64, 0, hideAir).buffer().length;
            }
            long elapsed = System.nanoTime() - start;
            long allocated = allocatedBytes() - allocatedBefore;
            System.out.printf("hide-air=%s: %.1f us/chunk, %.1f KB allocated/chunk (chunk %d bytes, check %d)%n",
                    hideAir, elapsed / 1000.0 / iterations, allocated / 1024.0 / iterations, chunk.length, sink % 7);
        }
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sunBean) {
            return sunBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return 0L;
    }

    private static byte[] realisticChunk(Random random) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int section = 0; section < 24; section++) {
            int[] states = new int[4096];
            for (int i = 0; i < 4096; i++) {
                if (section < 8) {
                    // Deepslate/stone with caves (air + cave_air), ores, tuff.
                    int roll = random.nextInt(100);
                    states[i] = roll < 12 ? AIR : roll < 20 ? CAVE_AIR : roll < 23 ? 5734 + random.nextInt(6)
                            : roll < 30 ? 22 : 1 + (section < 4 ? 0 : 1);
                } else if (section < 9) {
                    // Surface: grass, dirt, a few flowers, air above.
                    int localY = i >> 8;
                    states[i] = localY < 6 ? 10 : localY < 8 ? 9 : random.nextInt(60) == 0 ? 400 + random.nextInt(8) : AIR;
                } else {
                    states[i] = AIR;
                }
            }
            writeSection(out, states);
        }
        return out.toByteArray();
    }

    private static void writeSection(ByteArrayOutputStream out, int[] states) {
        int nonEmpty = 0;
        List<Integer> palette = new ArrayList<>();
        for (int state : states) {
            if (state != AIR && state != CAVE_AIR) {
                nonEmpty++;
            }
            if (!palette.contains(state)) {
                palette.add(state);
            }
        }
        out.write((nonEmpty >>> 8) & 0xFF);
        out.write(nonEmpty & 0xFF);
        if (palette.size() == 1) {
            out.write(0);
            writeVarInt(out, palette.get(0));
        } else {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            out.write(bits);
            writeVarInt(out, palette.size());
            for (int state : palette) {
                writeVarInt(out, state);
            }
            int perLong = 64 / bits;
            long[] packed = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) {
                packed[i / perLong] |= ((long) palette.indexOf(states[i])) << ((i % perLong) * bits);
            }
            for (long value : packed) {
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (value >>> shift) & 0xFF);
                }
            }
        }
        out.write(0);
        writeVarInt(out, 0);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }
}

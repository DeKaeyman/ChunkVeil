package com.dekaeyman.chunkveil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Remembers recent chunk rewrites by content, so the same chunk sent to
 * several players (players near each other, spawn, a reveal resend) is
 * rewritten once instead of once per player.
 *
 * <p>The key is the chunk section buffer itself (a 128-bit hash of every
 * byte plus its length) together with the band and world height: the
 * rewrite is a pure function of exactly those, so a cached result is always
 * the result a fresh rewrite would produce, and a changed chunk can never
 * hit a stale entry (its bytes, and so its hash, are different). One cache
 * per rewriter, so the fake block is part of the key as well.
 *
 * <p>Direct-mapped and lock-free: Folia region threads read and write it in
 * parallel; a slot holds one immutable entry and newer entries simply replace
 * older ones. Hits hand out a copy, so packets never share an array.
 */
final class ChunkRewriteCache {
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long PRIME_A = 0x9E3779B97F4A7C15L;
    private static final long PRIME_B = 0xC2B2AE3D27D4EB4FL;

    private final AtomicReferenceArray<Entry> slots;
    private final int mask;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    ChunkRewriteCache(int slotsPowerOfTwo) {
        if (Integer.bitCount(slotsPowerOfTwo) != 1) {
            throw new IllegalArgumentException("slot count must be a power of two");
        }
        this.slots = new AtomicReferenceArray<>(slotsPowerOfTwo);
        this.mask = slotsPowerOfTwo - 1;
    }

    /** Key of a rewrite: content hash plus every other input of the rewrite. */
    record Key(long hashA, long hashB, int length, int minHeight, int maxHeight, int hideMinY, int hideBelowY,
            boolean hideAir) {
        int slot(int mask) {
            long mixed = hashA ^ (hashB >>> 17) ^ ((long) hideBelowY << 32) ^ hideMinY;
            return (int) (mixed ^ (mixed >>> 32)) & mask;
        }
    }

    private record Entry(Key key, byte[] output, int hiddenSections, int rewrittenSections) {
    }

    static Key key(byte[] input, int minHeight, int maxHeight, int hideMinY, int hideBelowY, boolean hideAir) {
        long a = PRIME_A ^ input.length;
        long b = PRIME_B ^ ((long) input.length << 1);
        int i = 0;
        int end = input.length - (input.length & 7);
        for (; i < end; i += 8) {
            long v = (long) LONGS.get(input, i);
            a = Long.rotateLeft(a ^ v * PRIME_B, 31) * PRIME_A;
            b = Long.rotateLeft(b + v * PRIME_A, 27) * PRIME_B;
        }
        for (; i < input.length; i++) {
            long v = input[i] & 0xFFL;
            a = Long.rotateLeft(a ^ v * PRIME_B, 11) * PRIME_A;
            b = Long.rotateLeft(b + v * PRIME_A, 13) * PRIME_B;
        }
        a = finish(a);
        b = finish(b ^ a);
        return new Key(a, b, input.length, minHeight, maxHeight, hideMinY, hideBelowY, hideAir);
    }

    private static long finish(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    /** Cached result (with a fresh copy of the buffer), or null. */
    ChunkPacketBlockRewriter.RewriteResult get(Key key) {
        Entry entry = slots.get(key.slot(mask));
        if (entry == null || !entry.key().equals(key)) {
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return new ChunkPacketBlockRewriter.RewriteResult(
                Arrays.copyOf(entry.output(), entry.output().length), entry.hiddenSections(), entry.rewrittenSections());
    }

    /** Stores a rewrite result (its own copy of the buffer). */
    void put(Key key, ChunkPacketBlockRewriter.RewriteResult result) {
        byte[] buffer = result.buffer();
        slots.set(key.slot(mask), new Entry(key, Arrays.copyOf(buffer, buffer.length),
                result.hiddenSections(), result.rewrittenSections()));
    }

    long hits() {
        return hits.get();
    }

    long misses() {
        return misses.get();
    }
}

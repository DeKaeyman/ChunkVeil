package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PlayerVeilStateConcurrencyTest {
    private static final ChunkKey KEY = ChunkKey.of("world", 3, 4);

    @Test
    void concurrentUnloadNeverStrandsAQueueMark() throws Exception {
        // Folia: the player's thread enqueues a reveal while the unloading
        // region thread forgets the chunk. A mark left without a deque entry
        // made every later reveal of that chunk return early, forever.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int stranded = 0;
            for (int i = 0; i < 20_000; i++) {
                PlayerVeilState state = new PlayerVeilState();
                state.markApplied(KEY, VeilMode.HIDE);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> player = pool.submit(() -> {
                    await(start);
                    state.enqueuePriority(KEY, VeilMode.REVEAL);
                });
                Future<?> unload = pool.submit(() -> {
                    await(start);
                    state.forgetChunk(KEY);
                });
                player.get();
                unload.get();

                if (state.isChunkQueued(KEY) && state.poll() == null) {
                    stranded++;
                }
            }
            assertEquals(0, stranded, "queue marks without a deque entry");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void continuousEnqueueAndUnloadKeepMarksConsistent() throws Exception {
        // Both threads hammer the same state without pausing, so the window
        // between marking and inserting is hit many times per run.
        for (int round = 0; round < 50; round++) {
            PlayerVeilState state = new PlayerVeilState();
            ChunkKey[] keys = {KEY, ChunkKey.of("world", 5, 6), ChunkKey.of("world", 7, 8)};
            Thread player = new Thread(() -> {
                for (int i = 0; i < 20_000; i++) {
                    ChunkKey key = keys[i % keys.length];
                    state.markApplied(key, VeilMode.HIDE);
                    state.enqueuePriority(key, VeilMode.REVEAL);
                    if (i % 7 == 0) {
                        state.poll();
                    }
                }
            });
            Thread unload = new Thread(() -> {
                for (int i = 0; i < 20_000; i++) {
                    state.forgetChunk(keys[i % keys.length]);
                }
            });
            player.start();
            unload.start();
            player.join();
            unload.join();
            assertTrue(state.queueMarksConsistent(), "round " + round + ": a queue mark has no deque entry");
        }
    }

    @Test
    void chunkCanBeQueuedAgainAfterAnUnloadRace() throws Exception {
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(KEY, VeilMode.HIDE);
        state.enqueuePriority(KEY, VeilMode.REVEAL);
        state.forgetChunk(KEY);

        // Reload: the packet rewrite marks it hidden and queues a reveal.
        state.markApplied(KEY, VeilMode.HIDE);
        state.enqueuePriority(KEY, VeilMode.REVEAL);

        VeilChunkUpdate update = state.poll();
        assertEquals(KEY, update == null ? null : update.chunkKey());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

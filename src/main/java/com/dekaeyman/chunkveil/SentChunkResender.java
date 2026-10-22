package com.dekaeyman.chunkveil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Resends a player's already-sent chunks to that player only, a few per tick.
 *
 * Used when the client may hold chunk data that no longer matches the current
 * settings (admin disable, or a reload that changed a world's concealment).
 * The old path called World#refreshChunk for every sent chunk of every
 * player; refreshChunk broadcasts to every tracker, so a chunk seen by k
 * players was resent k² times in one tick. This sends each chunk once per
 * player, spread over ticks on the player's own scheduler (safe on Folia).
 */
final class SentChunkResender {
    static final int CHUNKS_PER_TICK = 16;
    // Folia: a chunk owned by a neighbouring region is retried later instead
    // of being dropped (it would keep stale fake blocks on the client). After
    // this many attempts (about 30 s at one attempt per pass) it is given up.
    static final int MAX_ATTEMPTS = 600;

    private SentChunkResender() {
    }

    /**
     * Schedules the resend and returns how many chunks were scheduled. Must
     * run on the player's owning thread (the main thread on Paper).
     */
    static int resend(Plugin plugin, Player player, Predicate<World> worldFilter) {
        Deque<Pending> pending = new ArrayDeque<>();
        for (Chunk chunk : player.getSentChunks()) {
            if (worldFilter.test(chunk.getWorld())) {
                pending.add(new Pending(chunk, 0));
            }
        }
        int scheduledChunks = pending.size();
        if (scheduledChunks == 0) {
            return 0;
        }

        AtomicReference<VeilScheduler.TaskHandle> handle = new AtomicReference<>();
        VeilScheduler.TaskHandle scheduled = VeilScheduler.runOnEntityTimer(plugin, player, () -> {
            // Each pass looks at every chunk left at most once, so retried
            // chunks wait for the next tick instead of spinning.
            int budget = CHUNKS_PER_TICK;
            int remainingThisPass = pending.size();
            while (budget > 0 && remainingThisPass-- > 0) {
                Pending next = pending.poll();
                Outcome outcome = resendIfStillSent(plugin, player, next.chunk());
                if (outcome == Outcome.RETRY_LATER) {
                    if (next.attempts() + 1 < MAX_ATTEMPTS) {
                        pending.add(new Pending(next.chunk(), next.attempts() + 1));
                    }
                } else if (outcome == Outcome.SENT) {
                    budget--;
                }
            }
            if (pending.isEmpty() || !player.isOnline()) {
                VeilScheduler.TaskHandle self = handle.get();
                if (self != null) {
                    self.cancel();
                }
            }
        }, null, 1L, 1L);
        if (scheduled == null) {
            return 0;
        }
        handle.set(scheduled);
        return scheduledChunks;
    }

    enum Outcome { SENT, SKIPPED, RETRY_LATER }

    private record Pending(Chunk chunk, int attempts) {
    }

    private static Outcome resendIfStillSent(Plugin plugin, Player player, Chunk chunk) {
        World world = chunk.getWorld();
        int x = chunk.getX();
        int z = chunk.getZ();
        if (!player.isOnline() || !player.getWorld().equals(world) || !world.isChunkLoaded(x, z)) {
            return Outcome.SKIPPED;
        }
        if (!VeilScheduler.canAccessChunk(world, x, z)) {
            return Outcome.RETRY_LATER;
        }
        if (!player.isChunkSent(chunk)) {
            return Outcome.SKIPPED;
        }
        if (!PlayerChunkRefresher.refresh(plugin.getLogger(), player, world, x, z)) {
            world.refreshChunk(x, z);
        }
        return Outcome.SENT;
    }
}

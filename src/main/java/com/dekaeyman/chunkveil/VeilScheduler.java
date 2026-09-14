package com.dekaeyman.chunkveil;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Thin routing layer over Paper's modern schedulers (global-region, async and
 * per-entity). These APIs exist on Paper 1.20.4+ and execute on the main
 * thread there, while Folia routes them to the owning region thread, so one
 * shared code path serves both platforms.
 *
 * Every platform branch in ChunkVeil goes through {@link #folia()}, which is
 * constant {@code false} on Paper: Paper keeps its existing single-threaded
 * behavior by construction rather than by testing alone.
 */
final class VeilScheduler {
    private static final long MILLIS_PER_TICK = 50L;
    private static final boolean FOLIA = detectFolia();

    private VeilScheduler() {
    }

    /** A cancellable scheduled task. */
    interface TaskHandle {
        void cancel();
    }

    static boolean folia() {
        return FOLIA;
    }

    /**
     * Whether the current thread may read or mutate the given chunk. Constant
     * {@code true} on Paper, where callers already run on the main thread;
     * only meaningful when called from a tick/owner context.
     */
    static boolean canAccessChunk(World world, int chunkX, int chunkZ) {
        return !FOLIA || Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    /** Chunk-access check for the chunk containing a block position. */
    static boolean canAccessBlock(World world, int blockX, int blockZ) {
        return canAccessChunk(world, blockX >> 4, blockZ >> 4);
    }

    /** Whether the current thread may read or mutate the given entity. Constant {@code true} on Paper. */
    static boolean canAccessEntity(Entity entity) {
        return !FOLIA || Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** Runs on the global region thread (the main thread on Paper) on the next tick. */
    static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, consumer(task), initialDelayTicks, periodTicks)::cancel;
    }

    static void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, consumer(task));
    }

    static TaskHandle runAsyncTimer(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, consumer(task),
                initialDelayTicks * MILLIS_PER_TICK, periodTicks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS)::cancel;
    }

    /** Runs on the thread owning the given chunk's region (the main thread on Paper). */
    static void runOnRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    /**
     * Schedules onto the entity's owning thread for the next tick. Never runs
     * inline, matching the defer-by-one-tick semantics packet callbacks rely
     * on. Returns false when the entity is already retired and nothing was
     * scheduled.
     */
    static boolean runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        return entity.getScheduler().run(plugin, consumer(task), null) != null;
    }

    static void runOnEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, consumer(task), null, delayTicks);
    }

    /** Returns null when the entity is already retired and nothing was scheduled. */
    static TaskHandle runOnEntityTimer(
            Plugin plugin,
            Entity entity,
            Runnable task,
            Runnable retired,
            long initialDelayTicks,
            long periodTicks
    ) {
        ScheduledTask scheduled = entity.getScheduler()
                .runAtFixedRate(plugin, consumer(task), retired, initialDelayTicks, periodTicks);
        return scheduled == null ? null : scheduled::cancel;
    }

    /**
     * Runs inline when the current thread already owns the entity — always the
     * case for main-thread code on Paper — otherwise schedules onto the owner.
     * Only for lifecycle fan-out that is synchronous on Paper today; must not
     * be called from packet/netty threads.
     */
    static void runOwned(Plugin plugin, Entity entity, Runnable task) {
        if (!FOLIA || Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            runOnEntity(plugin, entity, task);
        }
    }

    /**
     * Runs an action for every online player on that player's owning thread —
     * inline on Paper, per-region on Folia. The shared shape for lifecycle
     * fan-out; must not be called from packet/netty threads.
     */
    static void forEachOnlinePlayerOwned(Plugin plugin, Consumer<Player> action) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            runOwned(plugin, player, () -> action.accept(player));
        }
    }

    /**
     * Runs inline on Paper (and on Folia's global thread), otherwise schedules
     * onto the global region scheduler. Serializes lifecycle transitions that
     * a Folia player-region thread may otherwise run concurrently; must not be
     * called from packet/netty threads.
     */
    static void runGlobalOwned(Plugin plugin, Runnable task) {
        if (!FOLIA || Bukkit.isGlobalTickThread()) {
            task.run();
        } else {
            runGlobal(plugin, task);
        }
    }

    private static Consumer<ScheduledTask> consumer(Runnable task) {
        return ignored -> task.run();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}

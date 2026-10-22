package com.dekaeyman.chunkveil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Resends a chunk to ONE player. {@code World#refreshChunk} broadcasts to
 * every player tracking the chunk, so one player's reveal used to cost a full
 * chunk packet (and a chunk rewrite) for every nearby player — quadratic in
 * crowded areas.
 *
 * Mirrors Paper's own refreshChunk path, only with a one-player recipient
 * list: {@code ServerChunkCache.chunkMap.getVisibleChunkIfPresent(pos)
 * .getChunkToSend()} handed to {@code FeatureHooks.sendChunkRefreshPackets},
 * which builds the packet with the same per-player anti-xray decision Paper
 * uses and sends it through the normal connection (so it still passes the
 * ChunkVeil packet listener). Verified present with identical signatures on
 * Paper 1.21.4 and 1.21.11 and Folia 1.21.11 and 26.1.2.
 *
 * If anything cannot be resolved, or fails at runtime, the refresher turns
 * itself off once and callers fall back to the broadcast refresh: slower, but
 * never wrong.
 */
final class PlayerChunkRefresher {
    private static final class Handles {
        final MethodHandle worldHandle;
        final MethodHandle chunkSource;
        final MethodHandle chunkMap;
        final MethodHandle visibleChunk;
        final MethodHandle chunkToSend;
        final MethodHandle playerHandle;
        final MethodHandle sendRefresh;

        Handles(ClassLoader loader) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld", false, loader);
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", false, loader);
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel", false, loader);
            Class<?> serverChunkCache = Class.forName("net.minecraft.server.level.ServerChunkCache", false, loader);
            Class<?> chunkMapClass = Class.forName("net.minecraft.server.level.ChunkMap", false, loader);
            Class<?> chunkHolder = Class.forName("net.minecraft.server.level.ChunkHolder", false, loader);
            Class<?> levelChunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk", false, loader);
            Class<?> featureHooks = Class.forName("io.papermc.paper.FeatureHooks", true, loader);

            worldHandle = lookup.unreflect(craftWorld.getMethod("getHandle"));
            chunkSource = lookup.unreflect(serverLevel.getMethod("getChunkSource"));
            chunkMap = lookup.unreflectGetter(serverChunkCache.getField("chunkMap"));
            visibleChunk = lookup.unreflect(chunkMapClass.getMethod("getVisibleChunkIfPresent", long.class));
            chunkToSend = lookup.unreflect(chunkHolder.getMethod("getChunkToSend"));
            playerHandle = lookup.unreflect(craftPlayer.getMethod("getHandle"));
            sendRefresh = lookup.unreflect(featureHooks.getMethod("sendChunkRefreshPackets", List.class, levelChunk));
        }
    }

    private static volatile Handles handles;
    private static volatile boolean disabled;

    private PlayerChunkRefresher() {
    }

    /** Resolves the reflective handles once at startup and logs which path is used. */
    static void initialize(Logger logger) {
        if (handles != null || disabled) {
            return;
        }
        try {
            handles = new Handles(Bukkit.getServer().getClass().getClassLoader());
            logger.info("Per-player chunk reveals enabled (reveals no longer resend chunks to every nearby player).");
        } catch (Throwable throwable) {
            disable(logger, "could not resolve the per-player chunk resend path ("
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage() + ")");
        }
    }

    /**
     * Sends the chunk to this player only. Must run on the thread owning both
     * the player and the chunk (the main thread on Paper). Returns false when
     * the chunk has nothing to send or the per-player path is unavailable;
     * the caller then falls back to the broadcast refresh.
     */
    static boolean refresh(Logger logger, Player player, World world, int chunkX, int chunkZ) {
        Handles resolved = handles;
        if (resolved == null || disabled) {
            return false;
        }
        try {
            Object level = resolved.worldHandle.invoke(world);
            Object source = resolved.chunkSource.invoke(level);
            Object map = resolved.chunkMap.invoke(source);
            Object holder = resolved.visibleChunk.invoke(map, chunkKey(chunkX, chunkZ));
            if (holder == null) {
                return false;
            }
            Object chunk = resolved.chunkToSend.invoke(holder);
            if (chunk == null) {
                return false;
            }
            Object serverPlayer = resolved.playerHandle.invoke(player);
            resolved.sendRefresh.invoke(List.of(serverPlayer), chunk);
            return true;
        } catch (Throwable throwable) {
            disable(logger, "per-player chunk resend failed at runtime ("
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage() + ")");
            return false;
        }
    }

    static boolean available() {
        return handles != null && !disabled;
    }

    /** Minecraft's ChunkPos#asLong packing. */
    static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    private static void disable(Logger logger, String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        logger.warning("Falling back to broadcast chunk reveals: " + reason
                + ". Protection is unaffected; reveals just cost more in crowded areas.");
    }
}

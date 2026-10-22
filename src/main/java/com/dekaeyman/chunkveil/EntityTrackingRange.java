package com.dekaeyman.chunkveil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * How far the server sends entities to a client, used to bound the entity
 * scan. Paper/Spigot track entities per type up to spigot.yml's
 * {@code entity-tracking-range} (horizontal distance only, never beyond the
 * view distance), so scanning the whole view distance walked every entity of
 * large farms the client would never receive.
 *
 * <p>The ender dragon is the exception: it is tracked across the full view
 * distance. Worlds of the End environment therefore keep the view-distance
 * radius.
 */
final class EntityTrackingRange {
    /** Extra blocks beyond the tracking range, for entities moving between scans. */
    static final int MARGIN_BLOCKS = 32;
    /** No usable configuration: do not cap. */
    static final int UNKNOWN = -1;

    private static final String[] CATEGORIES = {"players", "animals", "monsters", "misc", "display", "other"};
    // Spigot's built-in defaults, used when a key is missing from spigot.yml.
    private static final int[] DEFAULTS = {128, 96, 96, 96, 128, 64};

    private final Map<String, Integer> rangeByWorld = new ConcurrentHashMap<>();
    private volatile ConfigurationSection spigotConfig;
    private volatile boolean configResolved;
    private volatile boolean scaleWarningLogged;

    /** Horizontal scan radius in blocks for a viewer in this world. */
    int scanBlocks(World world, int viewScanBlocks) {
        if (world.getEnvironment() == World.Environment.THE_END) {
            return viewScanBlocks;
        }
        int range = rangeByWorld.computeIfAbsent(world.getName(), this::resolveRange);
        return capScanBlocks(viewScanBlocks, range);
    }

    /** Resolves the ranges of already loaded worlds up front, off the scan path. */
    void prime(Iterable<World> worlds) {
        for (World world : worlds) {
            rangeByWorld.computeIfAbsent(world.getName(), this::resolveRange);
        }
    }

    /**
     * Configured range scaled the way the server scales it:
     * entity-broadcast-range-percentage in server.properties (up to 500%)
     * multiplies every tracking range. Unscaled, a 200% server tracks out to
     * 256 blocks while the scan stopped at 160, so entities in that ring were
     * neither hidden nor revealed. If the scaling cannot be read, no cap.
     */
    private int resolveRange(String worldName) {
        int configured = maxRange(spigotConfig(), worldName);
        if (configured == UNKNOWN) {
            return UNKNOWN;
        }
        try {
            Object server = Bukkit.getServer();
            Object minecraftServer = server.getClass().getMethod("getServer").invoke(server);
            int scaled = (Integer) minecraftServer.getClass()
                    .getMethod("getScaledTrackingDistance", int.class).invoke(minecraftServer, configured);
            return scaled > 0 ? scaled : UNKNOWN;
        } catch (Throwable throwable) {
            if (!scaleWarningLogged) {
                scaleWarningLogged = true;
                Bukkit.getLogger().warning("[ChunkVeil] Could not read the entity broadcast range percentage ("
                        + throwable + "); entity scans use the full view distance.");
            }
            return UNKNOWN;
        }
    }

    static int capScanBlocks(int viewScanBlocks, int maxTrackingRange) {
        if (maxTrackingRange <= 0) {
            return viewScanBlocks;
        }
        return Math.min(viewScanBlocks, maxTrackingRange + MARGIN_BLOCKS);
    }

    /** Largest configured tracking range for the world, or {@link #UNKNOWN}. */
    static int maxRange(ConfigurationSection spigot, String worldName) {
        if (spigot == null) {
            return UNKNOWN;
        }
        int max = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            String key = "entity-tracking-range." + CATEGORIES[i];
            int value = spigot.getInt("world-settings." + worldName + "." + key,
                    spigot.getInt("world-settings.default." + key, DEFAULTS[i]));
            max = Math.max(max, value);
        }
        return max > 0 ? max : UNKNOWN;
    }

    // Deprecated in Paper but present on Paper and Folia; it is the only API
    // view of the tracking ranges the server actually uses.
    @SuppressWarnings({"deprecation", "removal"})
    private ConfigurationSection spigotConfig() {
        if (!configResolved) {
            try {
                spigotConfig = Bukkit.spigot().getSpigotConfig();
            } catch (Throwable throwable) {
                // Fork without Spigot config access: scan the view distance.
                spigotConfig = null;
                Bukkit.getLogger().warning("[ChunkVeil] Could not read entity tracking ranges from spigot.yml ("
                        + throwable + "); entity scans use the full view distance.");
            }
            configResolved = true;
        }
        return spigotConfig;
    }
}

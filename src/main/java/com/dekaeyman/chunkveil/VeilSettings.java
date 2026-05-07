package com.dekaeyman.chunkveil;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

record VeilSettings(Map<String, VeilWorldSettings> worlds, int viewRevealHorizontalRays, int viewRevealVerticalRays, int viewRevealOcclusionGraceBlocks, int viewRevealRefreshMillis) {
    static VeilSettings load(Plugin plugin) {
        ConfigurationSection config = plugin.getConfig();
        Map<String, VeilWorldSettings> worlds = new HashMap<>();

        ConfigurationSection worldSection = config.getConfigurationSection("worlds");
        if (worldSection != null) {
            for (String worldName : worldSection.getKeys(false)) {
                ConfigurationSection section = worldSection.getConfigurationSection(worldName);
                if (section == null) {
                    continue;
                }

                VeilWorldSettings worldSettings = loadWorldSettings(section);
                worlds.put(worldName, worldSettings);
            }
        }

        int viewRevealHorizontalRays = Math.max(12, config.getInt("view-reveal-horizontal-rays", 64));
        int viewRevealVerticalRays = Math.max(3, config.getInt("view-reveal-vertical-rays", 13));
        int viewRevealOcclusionGraceBlocks = Math.max(0, config.getInt("view-reveal-occlusion-grace-blocks", 2));
        int viewRevealRefreshMillis = Math.max(50, config.getInt("view-reveal-refresh-millis", 150));

        return new VeilSettings(Map.copyOf(worlds), viewRevealHorizontalRays, viewRevealVerticalRays, viewRevealOcclusionGraceBlocks, viewRevealRefreshMillis);
    }

    boolean isEnabledWorld(World world) {
        return world != null && world(world).enabled();
    }

    VeilWorldSettings world(World world) {
        if (world == null) {
            return disabledDefaults();
        }
        return worlds.getOrDefault(world.getName(), disabledDefaults());
    }

    int hideBelowY(World world) {
        return world(world).hideBelowY();
    }

    int minY(World world) {
        return world(world).minY();
    }

    Material defaultFakeBlock(World world) {
        return world(world).defaultFakeBlock();
    }

    boolean hideAir(World world) {
        return world(world).hideAir();
    }

    boolean hideEntities(World world) {
        return world(world).hideEntities();
    }

    boolean hidePlayers(World world) {
        return world(world).hidePlayers();
    }

    int hiddenSectionCount(World world) {
        return world(world).hiddenSectionCount();
    }

    Material replacementFor(World world, Material realBlock) {
        return world(world).replacementFor(realBlock);
    }

    private VeilWorldSettings disabledDefaults() {
        return new VeilWorldSettings(false, 0, -64, Material.DEEPSLATE, false, true, false);
    }

    private static VeilWorldSettings loadWorldSettings(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", false);
        int hideBelowY = section.getInt("hide-below-y", 0);
        int minY = section.getInt("min-y", -64);
        Material defaultFakeBlock = material(section.getString("default-fake-block"), Material.DEEPSLATE);
        boolean hideAir = section.getBoolean("hide-air", false);
        boolean hideEntities = section.getBoolean("hide-entities", true);
        boolean hidePlayers = section.getBoolean("hide-players", false);

        return new VeilWorldSettings(enabled, hideBelowY, minY, defaultFakeBlock, hideAir, hideEntities, hidePlayers);
    }

    private static Material material(String value, Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    Set<String> enabledWorlds() {
        return worlds.entrySet().stream().filter(entry -> entry.getValue().enabled()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}

package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

record VeilSettings(
        Map<String, VeilWorldSettings> worlds,
        int viewRevealFrontHorizontalRays,
        int viewRevealSideHorizontalRays,
        int viewRevealBackHorizontalRays,
        int viewRevealVerticalRays,
        int viewRevealOcclusionGraceBlocks,
        int viewRevealRefreshMillis,
        int viewRevealForceRefreshMillis,
        double viewRevealMoveThresholdBlocks,
        float viewRevealYawThresholdDegrees,
        float viewRevealPitchThresholdDegrees,
        int maxPriorityChunkUpdatesPerPlayerPerTick,
        int maxRegularChunkUpdatesPerPlayerPerTick,
        int entityScanIntervalMillis,
        int entityScanMaxEntitiesPerPlayer,
        int viewRevealYawCacheBucketDegrees,
        boolean adaptiveScanQualityEnabled,
        double adaptiveScanReduceBelowTps,
        int adaptiveScanMinimumFrontHorizontalRays,
        int adaptiveScanMinimumSideHorizontalRays,
        int adaptiveScanMinimumBackHorizontalRays,
        int adaptiveScanMinimumVerticalRays,
        boolean cancelExplosionsInHiddenZones,
        boolean cancelWorldEventsInHiddenZones,
        boolean cancelBlockCrackInHiddenZones,
        boolean cancelPositionalSoundsInHiddenZones,
        boolean cancelParticlesInHiddenZones,
        boolean cancelVibrationsInHiddenZones,
        boolean sanitizeLightInHiddenZones,
        List<String> validationWarnings
) {
    static VeilSettings load(Plugin plugin) {
        return load(plugin, plugin.getConfig());
    }

    // Also used by /chunkveil reload --check to validate a configuration read
    // from disk without applying it to the runtime.
    static VeilSettings load(Plugin plugin, ConfigurationSection config) {
        Map<String, VeilWorldSettings> worlds = new HashMap<>();
        List<String> validationWarnings = new ArrayList<>();

        ConfigurationSection worldSection = config.getConfigurationSection("worlds");
        if (worldSection != null) {
            for (String worldName : worldSection.getKeys(false)) {
                ConfigurationSection section = worldSection.getConfigurationSection(worldName);
                if (section == null) {
                    continue;
                }

                VeilWorldSettings worldSettings = loadWorldSettings(worldName, section, validationWarnings);
                worlds.put(worldName, worldSettings);
            }
        } else {
            validationWarnings.add("No worlds section found in config.yml.");
        }

        int legacyHorizontalRays = Math.max(12, config.getInt("view-reveal-horizontal-rays", 32));
        int viewRevealFrontHorizontalRays = Math.max(4, config.getInt(
                "view-reveal-front-horizontal-rays",
                Math.max(8, Math.round(legacyHorizontalRays * 0.56F))
        ));
        int viewRevealSideHorizontalRays = Math.max(2, config.getInt(
                "view-reveal-side-horizontal-rays",
                Math.max(4, Math.round(legacyHorizontalRays * 0.22F))
        ));
        int viewRevealBackHorizontalRays = Math.max(1, config.getInt(
                "view-reveal-back-horizontal-rays",
                Math.max(2, Math.round(legacyHorizontalRays * 0.12F))
        ));
        int viewRevealVerticalRays = Math.max(3, config.getInt("view-reveal-vertical-rays", 13));
        int viewRevealOcclusionGraceBlocks = Math.max(0, config.getInt("view-reveal-occlusion-grace-blocks", 2));
        int viewRevealRefreshMillis = Math.max(50, config.getInt("view-reveal-refresh-millis", 150));
        int viewRevealForceRefreshMillis = Math.max(
                viewRevealRefreshMillis,
                config.getInt("view-reveal-force-refresh-millis", 1200)
        );
        double viewRevealMoveThresholdBlocks = Math.max(0.0D, config.getDouble("view-reveal-move-threshold-blocks", 3.0D));
        float viewRevealYawThresholdDegrees = Math.max(0.0F, (float) config.getDouble("view-reveal-yaw-threshold-degrees", 12.0D));
        float viewRevealPitchThresholdDegrees = Math.max(0.0F, (float) config.getDouble("view-reveal-pitch-threshold-degrees", 8.0D));
        ConfigurationSection performance = config.getConfigurationSection("performance");
        int maxPriorityChunkUpdatesPerPlayerPerTick = Math.max(1, intValue(
                performance,
                "max-priority-chunk-updates-per-player-per-tick",
                24
        ));
        int maxRegularChunkUpdatesPerPlayerPerTick = Math.max(1, intValue(
                performance,
                "max-regular-chunk-updates-per-player-per-tick",
                1
        ));
        int entityScanIntervalMillis = Math.max(100, intValue(performance, "entity-scan-interval-millis", 500));
        int entityScanMaxEntitiesPerPlayer = Math.max(16, intValue(performance, "entity-scan-max-entities-per-player", 256));
        int viewRevealYawCacheBucketDegrees = Math.max(1, intValue(performance, "view-reveal-yaw-cache-bucket-degrees", 5));
        ConfigurationSection adaptiveScanQuality = performance == null ? null : performance.getConfigurationSection("adaptive-scan-quality");
        boolean adaptiveScanQualityEnabled = adaptiveScanQuality != null && adaptiveScanQuality.getBoolean("enabled", false);
        double adaptiveScanReduceBelowTps = Math.max(1.0D, doubleValue(adaptiveScanQuality, "reduce-rays-below-tps", 18.5D));
        int adaptiveScanMinimumFrontHorizontalRays = Math.max(1, intValue(adaptiveScanQuality, "minimum-front-horizontal-rays", 6));
        int adaptiveScanMinimumSideHorizontalRays = Math.max(1, intValue(adaptiveScanQuality, "minimum-side-horizontal-rays", 3));
        int adaptiveScanMinimumBackHorizontalRays = Math.max(1, intValue(adaptiveScanQuality, "minimum-back-horizontal-rays", 1));
        int adaptiveScanMinimumVerticalRays = Math.max(1, intValue(adaptiveScanQuality, "minimum-vertical-rays", 6));
        ConfigurationSection packetProtection = config.getConfigurationSection("packet-protection");
        boolean cancelExplosionsInHiddenZones = booleanValue(packetProtection, "cancel-explosions", true);
        boolean cancelWorldEventsInHiddenZones = booleanValue(packetProtection, "cancel-world-events", true);
        boolean cancelBlockCrackInHiddenZones = booleanValue(packetProtection, "cancel-block-crack", true);
        boolean cancelPositionalSoundsInHiddenZones = booleanValue(packetProtection, "cancel-positional-sounds", true);
        boolean cancelParticlesInHiddenZones = booleanValue(packetProtection, "cancel-particles", true);
        boolean cancelVibrationsInHiddenZones = booleanValue(packetProtection, "cancel-vibrations", true);
        boolean sanitizeLightInHiddenZones = booleanValue(packetProtection, "sanitize-light", true);
        if (sanitizeLightInHiddenZones) {
            for (Map.Entry<String, VeilWorldSettings> entry : worlds.entrySet()) {
                VeilWorldSettings worldSettings = entry.getValue();
                if (worldSettings.enabled() && Math.floorMod(worldSettings.hideBelowY(), 16) != 0) {
                    validationWarnings.add("World '" + entry.getKey() + "' uses hide-below-y="
                            + worldSettings.hideBelowY() + ", which crosses a light section. Align it to a multiple of 16"
                            + " to sanitize all light below the cutoff.");
                }
            }
        }
        validateGlobalSettings(
                viewRevealFrontHorizontalRays,
                viewRevealSideHorizontalRays,
                viewRevealBackHorizontalRays,
                viewRevealVerticalRays,
                viewRevealRefreshMillis,
                validationWarnings
        );
        validateWorldSettings(plugin, worlds, validationWarnings);
        validateHideAirCost(
                worlds,
                viewRevealFrontHorizontalRays,
                viewRevealSideHorizontalRays,
                viewRevealBackHorizontalRays,
                viewRevealVerticalRays,
                viewRevealRefreshMillis,
                validationWarnings
        );
        validateAdaptiveScanQuality(
                adaptiveScanQualityEnabled,
                adaptiveScanReduceBelowTps,
                adaptiveScanMinimumFrontHorizontalRays,
                adaptiveScanMinimumSideHorizontalRays,
                adaptiveScanMinimumBackHorizontalRays,
                adaptiveScanMinimumVerticalRays,
                viewRevealFrontHorizontalRays,
                viewRevealSideHorizontalRays,
                viewRevealBackHorizontalRays,
                viewRevealVerticalRays,
                validationWarnings
        );

        return new VeilSettings(
                Map.copyOf(worlds),
                viewRevealFrontHorizontalRays,
                viewRevealSideHorizontalRays,
                viewRevealBackHorizontalRays,
                viewRevealVerticalRays,
                viewRevealOcclusionGraceBlocks,
                viewRevealRefreshMillis,
                viewRevealForceRefreshMillis,
                viewRevealMoveThresholdBlocks,
                viewRevealYawThresholdDegrees,
                viewRevealPitchThresholdDegrees,
                maxPriorityChunkUpdatesPerPlayerPerTick,
                maxRegularChunkUpdatesPerPlayerPerTick,
                entityScanIntervalMillis,
                entityScanMaxEntitiesPerPlayer,
                viewRevealYawCacheBucketDegrees,
                adaptiveScanQualityEnabled,
                adaptiveScanReduceBelowTps,
                adaptiveScanMinimumFrontHorizontalRays,
                adaptiveScanMinimumSideHorizontalRays,
                adaptiveScanMinimumBackHorizontalRays,
                adaptiveScanMinimumVerticalRays,
                cancelExplosionsInHiddenZones,
                cancelWorldEventsInHiddenZones,
                cancelBlockCrackInHiddenZones,
                cancelPositionalSoundsInHiddenZones,
                cancelParticlesInHiddenZones,
                cancelVibrationsInHiddenZones,
                sanitizeLightInHiddenZones,
                List.copyOf(validationWarnings)
        );
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

    Material replacementFor(World world, Material realBlock) {
        return world(world).replacementFor(realBlock);
    }

    private VeilWorldSettings disabledDefaults() {
        return new VeilWorldSettings(false, 0, -64, Material.DEEPSLATE, false, true, false);
    }

    private static VeilWorldSettings loadWorldSettings(String worldName, ConfigurationSection section, List<String> validationWarnings) {
        boolean enabled = section.getBoolean("enabled", false);
        int hideBelowY = section.getInt("hide-below-y", 0);
        int minY = section.getInt("min-y", -64);
        Material defaultFakeBlock = material(worldName, section.getString("default-fake-block"), Material.DEEPSLATE, validationWarnings);
        boolean hideAir = section.getBoolean("hide-air", false);
        boolean hideEntities = section.getBoolean("hide-entities", true);
        boolean hidePlayers = section.getBoolean("hide-players", false);

        return new VeilWorldSettings(enabled, hideBelowY, minY, defaultFakeBlock, hideAir, hideEntities, hidePlayers);
    }

    private static Material material(String worldName, String value, Material fallback, List<String> validationWarnings) {
        if (value == null || value.isBlank()) {
            validationWarnings.add("World '" + worldName + "' has no default-fake-block set. Using " + fallback + ".");
            return fallback;
        }

        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        if (material == null) {
            validationWarnings.add("World '" + worldName + "' uses unknown default-fake-block '" + value + "'. Using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static int intValue(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static double doubleValue(ConfigurationSection section, String path, double fallback) {
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    private static boolean booleanValue(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static void validateGlobalSettings(
            int frontHorizontalRays,
            int sideHorizontalRays,
            int backHorizontalRays,
            int verticalRays,
            int refreshMillis,
            List<String> validationWarnings
    ) {
        int horizontalRays = frontHorizontalRays + sideHorizontalRays * 2 + backHorizontalRays;
        int totalRays = horizontalRays * verticalRays;
        if (horizontalRays > 64) {
            validationWarnings.add("Configured reveal scan uses " + horizontalRays + " horizontal rays. Values above 64 can be expensive.");
        }
        if (verticalRays > 24) {
            validationWarnings.add("Configured reveal scan uses " + verticalRays + " vertical rays. Values above 24 can be expensive.");
        }
        if (totalRays > 512) {
            validationWarnings.add("Configured reveal scan uses " + totalRays + " total rays per scan. This may be expensive with many players.");
        }
        if (refreshMillis < 100) {
            validationWarnings.add("view-reveal-refresh-millis is " + refreshMillis + ". Values below 100ms can be expensive.");
        }
    }

    private static void validateWorldSettings(Plugin plugin, Map<String, VeilWorldSettings> worlds, List<String> validationWarnings) {
        if (worlds.values().stream().noneMatch(VeilWorldSettings::enabled)) {
            validationWarnings.add("No worlds are enabled. ChunkVeil will not hide underground chunks until at least one world is enabled.");
        }

        for (Map.Entry<String, VeilWorldSettings> entry : worlds.entrySet()) {
            String worldName = entry.getKey();
            VeilWorldSettings worldSettings = entry.getValue();
            World loadedWorld = plugin.getServer().getWorld(worldName);

            if (loadedWorld == null) {
                validationWarnings.add("Configured world '" + worldName + "' does not exist or is not loaded.");
            }
            if (worldSettings.hideBelowY() <= worldSettings.minY()) {
                validationWarnings.add("World '" + worldName + "' has hide-below-y <= min-y. No hidden Y range will be processed.");
            }
            validateFakeBlock(worldName, worldSettings.defaultFakeBlock(), validationWarnings);
            validateDimensionFakeBlock(worldName, loadedWorld, worldSettings.defaultFakeBlock(), validationWarnings);
        }
    }

    private static void validateHideAirCost(
            Map<String, VeilWorldSettings> worlds,
            int frontHorizontalRays,
            int sideHorizontalRays,
            int backHorizontalRays,
            int verticalRays,
            int refreshMillis,
            List<String> validationWarnings
    ) {
        int horizontalRays = frontHorizontalRays + sideHorizontalRays * 2 + backHorizontalRays;
        int totalRays = horizontalRays * verticalRays;
        for (Map.Entry<String, VeilWorldSettings> entry : worlds.entrySet()) {
            VeilWorldSettings worldSettings = entry.getValue();
            if (worldSettings.enabled() && worldSettings.hideAir() && (totalRays > 256 || refreshMillis < 150)) {
                validationWarnings.add("World '" + entry.getKey() + "' has hide-air enabled with " + totalRays
                        + " rays and " + refreshMillis + "ms refresh. This can be expensive on busy servers.");
            }
        }
    }

    private static void validateAdaptiveScanQuality(
            boolean enabled,
            double reduceBelowTps,
            int minimumFrontRays,
            int minimumSideRays,
            int minimumBackRays,
            int minimumVerticalRays,
            int configuredFrontRays,
            int configuredSideRays,
            int configuredBackRays,
            int configuredVerticalRays,
            List<String> validationWarnings
    ) {
        if (!enabled) {
            return;
        }
        if (reduceBelowTps >= 20.0D) {
            validationWarnings.add("Adaptive scan quality reduce-rays-below-tps is " + reduceBelowTps
                    + ". Values at or above 20 may reduce scan quality almost all the time.");
        }
        if (minimumFrontRays > configuredFrontRays
                || minimumSideRays > configuredSideRays
                || minimumBackRays > configuredBackRays
                || minimumVerticalRays > configuredVerticalRays) {
            validationWarnings.add("Adaptive scan quality minimum ray counts are above configured ray counts. They will not reduce scan cost.");
        }
    }

    private static void validateFakeBlock(String worldName, Material fakeBlock, List<String> validationWarnings) {
        if (!fakeBlock.isBlock()) {
            validationWarnings.add("World '" + worldName + "' uses " + fakeBlock + " as default-fake-block, but it is not a placeable block.");
            return;
        }
        if (fakeBlock.isAir()) {
            validationWarnings.add("World '" + worldName + "' uses air as default-fake-block. This can reveal underground shapes.");
        }
        if (!fakeBlock.isOccluding()) {
            validationWarnings.add("World '" + worldName + "' uses non-occluding fake block " + fakeBlock + ". Solid opaque blocks are safer.");
        }
    }

    private static void validateDimensionFakeBlock(String worldName, World loadedWorld, Material fakeBlock, List<String> validationWarnings) {
        World.Environment environment = loadedWorld == null ? environmentGuess(worldName) : loadedWorld.getEnvironment();
        if (environment == World.Environment.NETHER && fakeBlock != Material.NETHERRACK) {
            validationWarnings.add("Nether world '" + worldName + "' uses " + fakeBlock + " as fake block. NETHERRACK is usually safer for Nether terrain.");
        }
        if (environment == World.Environment.THE_END && fakeBlock != Material.END_STONE) {
            validationWarnings.add("End world '" + worldName + "' uses " + fakeBlock + " as fake block. END_STONE is usually safer for End terrain.");
        }
    }

    private static World.Environment environmentGuess(String worldName) {
        String normalized = worldName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_nether") || normalized.contains("nether")) {
            return World.Environment.NETHER;
        }
        if (normalized.endsWith("_the_end") || normalized.endsWith("_end") || normalized.contains("the_end")) {
            return World.Environment.THE_END;
        }
        return World.Environment.NORMAL;
    }

    Set<String> enabledWorlds() {
        return worlds.entrySet().stream().filter(entry -> entry.getValue().enabled()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
}

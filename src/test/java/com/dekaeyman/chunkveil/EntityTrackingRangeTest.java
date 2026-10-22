package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EntityTrackingRangeTest {
    private static YamlConfiguration spigot(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void stockSpigotDefaultsGivePlayerRange() throws Exception {
        YamlConfiguration config = spigot("""
                world-settings:
                  default:
                    entity-tracking-range:
                      players: 128
                      animals: 96
                      monsters: 96
                      misc: 96
                      display: 128
                      other: 64
                """);
        assertEquals(128, EntityTrackingRange.maxRange(config, "world"));
    }

    @Test
    void worldOverrideWinsOverDefault() throws Exception {
        YamlConfiguration config = spigot("""
                world-settings:
                  default:
                    entity-tracking-range:
                      players: 128
                      display: 128
                  farmworld:
                    entity-tracking-range:
                      players: 48
                      animals: 32
                      monsters: 32
                      misc: 16
                      display: 16
                      other: 16
                """);
        assertEquals(48, EntityTrackingRange.maxRange(config, "farmworld"));
        assertEquals(128, EntityTrackingRange.maxRange(config, "world"));
    }

    @Test
    void missingKeysFallBackToSpigotDefaults() throws Exception {
        // Older spigot.yml files have no "display" key, for example.
        assertEquals(128, EntityTrackingRange.maxRange(spigot("settings: {}"), "world"));
    }

    @Test
    void noConfigMeansNoCap() {
        assertEquals(EntityTrackingRange.UNKNOWN, EntityTrackingRange.maxRange(null, "world"));
        assertEquals(384, EntityTrackingRange.capScanBlocks(384, EntityTrackingRange.UNKNOWN));
    }

    @Test
    void capIsTrackingRangePlusMarginButNeverAboveViewRadius() {
        // View distance 22 (+2 chunks) = 384 blocks; default tracking 128.
        assertEquals(128 + EntityTrackingRange.MARGIN_BLOCKS, EntityTrackingRange.capScanBlocks(384, 128));
        // Small view distance: the view radius is already smaller.
        assertEquals(96, EntityTrackingRange.capScanBlocks(96, 128));
    }
}

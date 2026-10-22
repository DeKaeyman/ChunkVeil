package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class VeilSettingsEntityPacketsTest {
    private static VeilWorldSettings world(boolean enabled, boolean hideEntities) {
        return new VeilWorldSettings(enabled, 0, -64, Material.DEEPSLATE, false, hideEntities, false);
    }

    @Test
    void entityPacketsAreSubscribedWhenAnyEnabledWorldHidesEntities() {
        assertTrue(VeilSettings.anyWorldHidesEntities(Map.of(
                "world", world(true, false),
                "world_nether", world(true, true))));
    }

    @Test
    void notWhenEntityHidingIsOffOrOnlyInDisabledWorlds() {
        assertFalse(VeilSettings.anyWorldHidesEntities(Map.of()));
        assertFalse(VeilSettings.anyWorldHidesEntities(Map.of(
                "world", world(true, false),
                "old_world", world(false, true))));
    }
}

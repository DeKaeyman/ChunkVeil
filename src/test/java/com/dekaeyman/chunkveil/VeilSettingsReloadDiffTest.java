package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class VeilSettingsReloadDiffTest {
    private static VeilWorldSettings world(boolean enabled, int hideBelowY, int minY, Material fake, boolean hideAir,
            boolean hideEntities) {
        return new VeilWorldSettings(enabled, hideBelowY, minY, fake, hideAir, hideEntities, false);
    }

    private static final VeilWorldSettings BASE = world(true, 0, -64, Material.DEEPSLATE, false, true);

    private static Set<String> diff(VeilWorldSettings before, VeilWorldSettings after) {
        return VeilSettings.worldsWithChangedChunkConcealment(
                before == null ? Map.of() : Map.of("world", before),
                after == null ? Map.of() : Map.of("world", after));
    }

    @Test void unchangedReloadResendsNothing() {
        // The common case: reload without touching concealment must cost nothing.
        assertTrue(diff(BASE, BASE).isEmpty());
    }

    @Test void nonChunkSettingsDoNotTriggerAResend() {
        assertTrue(diff(BASE, world(true, 0, -64, Material.DEEPSLATE, false, false)).isEmpty(),
                "hide-entities does not change chunk contents");
    }

    @Test void everyChunkRelevantChangeTriggersAResend() {
        Set<String> expected = Set.of("world");
        assertEquals(expected, diff(BASE, world(false, 0, -64, Material.DEEPSLATE, false, true)), "disabled");
        assertEquals(expected, diff(BASE, world(true, 16, -64, Material.DEEPSLATE, false, true)), "cutoff");
        assertEquals(expected, diff(BASE, world(true, 0, -32, Material.DEEPSLATE, false, true)), "min-y");
        assertEquals(expected, diff(BASE, world(true, 0, -64, Material.STONE, false, true)), "fake block");
        assertEquals(expected, diff(BASE, world(true, 0, -64, Material.DEEPSLATE, true, true)), "hide-air");
    }

    @Test void addingOrRemovingAnEnabledWorldTriggersAResend() {
        assertEquals(Set.of("world"), diff(null, BASE));
        assertEquals(Set.of("world"), diff(BASE, null));
    }

    @Test void changesToADisabledWorldAreIgnored() {
        VeilWorldSettings disabled = world(false, 0, -64, Material.DEEPSLATE, false, true);
        VeilWorldSettings disabledOther = world(false, 32, 0, Material.NETHERRACK, true, true);
        assertTrue(diff(disabled, disabledOther).isEmpty(), "nothing was or is concealed");
        assertTrue(diff(null, disabled).isEmpty());
    }
}

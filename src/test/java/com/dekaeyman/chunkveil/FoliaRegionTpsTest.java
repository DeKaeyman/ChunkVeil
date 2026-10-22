package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FoliaRegionTpsTest {
    @Test
    void readingsAreClampedToTheTpsRange() {
        assertEquals(20.0, FoliaRegionTps.clamp(20.4), 1e-9);
        assertEquals(11.5, FoliaRegionTps.clamp(11.5), 1e-9);
        assertEquals(0.0, FoliaRegionTps.clamp(-1.0), 1e-9);
        assertEquals(20.0, FoliaRegionTps.clamp(Double.NaN), 1e-9, "no data counts as full speed");
    }

    @Test
    void outsideFoliaItReportsFullSpeed() {
        // Not initialized (Paper, or adaptive quality off): never degrades.
        assertEquals(20.0, FoliaRegionTps.currentRegionTps(null), 1e-9);
    }
}

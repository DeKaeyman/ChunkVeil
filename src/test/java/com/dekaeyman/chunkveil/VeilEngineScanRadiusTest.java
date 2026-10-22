package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VeilEngineScanRadiusTest {
    @Test void usesThePlayersSendDistanceNotTheGlobalOne() {
        // World with send distance 12 while the global view distance might
        // be 10: chunks out to 12 are on the client and must be scanned.
        assertEquals(12, VeilEngine.effectiveScanRadius(12, 10, 16));
    }

    @Test void neverScansBeyondWhatTheClientRenders() {
        assertEquals(8, VeilEngine.effectiveScanRadius(12, 12, 8));
    }

    @Test void fallsBackToViewDistanceWhenSendDistanceIsUnset() {
        assertEquals(10, VeilEngine.effectiveScanRadius(-1, 10, 16));
        assertEquals(10, VeilEngine.effectiveScanRadius(0, 10, 16));
    }

    @Test void unknownClientDistanceDoesNotCollapseTheRadius() {
        // Before the client sends its settings the value can be 0.
        assertEquals(10, VeilEngine.effectiveScanRadius(10, 10, 0));
    }

    @Test void radiusHasASaneMinimum() {
        assertEquals(2, VeilEngine.effectiveScanRadius(1, 1, 1));
        assertEquals(2, VeilEngine.effectiveScanRadius(0, 0, 0));
    }
}

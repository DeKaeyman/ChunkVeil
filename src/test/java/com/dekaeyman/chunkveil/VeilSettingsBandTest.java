package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VeilSettingsBandTest {
    @Test void overworldDefaultHasABand() {
        assertTrue(VeilSettings.hasConcealedBand(-64, 320, -64, 0));
    }

    @Test void oldEndDefaultHasNoBand() {
        // End spans y=0..256; the old default hide-below-y 0 hides nothing.
        // This used to make every End chunk count as a rewrite failure.
        assertFalse(VeilSettings.hasConcealedBand(0, 256, -64, 0));
    }

    @Test void newEndDefaultHasABand() {
        assertTrue(VeilSettings.hasConcealedBand(0, 256, 0, 48));
    }

    @Test void minYBelowWorldBottomIsClamped() {
        assertTrue(VeilSettings.hasConcealedBand(0, 256, -64, 16));
    }

    @Test void cutoffAtOrBelowMinYHasNoBand() {
        assertFalse(VeilSettings.hasConcealedBand(-64, 320, 0, 0));
        assertFalse(VeilSettings.hasConcealedBand(-64, 320, 10, 5));
    }

    @Test void bandEntirelyAboveTheWorldTopHasNoBand() {
        assertFalse(VeilSettings.hasConcealedBand(0, 128, 200, 300));
    }
}

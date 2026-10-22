package com.dekaeyman.chunkveil;

import java.util.BitSet;
import java.util.List;

/** Replaces light arrays for fully concealed sections with darkness. */
final class LightPacketSanitizer {
    static final int LIGHT_ARRAY_BYTES = 2048;
    private LightPacketSanitizer() {
    }

    static int sanitize(
            BitSet skyMask,
            List<byte[]> skyUpdates,
            BitSet blockMask,
            List<byte[]> blockUpdates,
            int minHeight,
            int hideBelowY
    ) {
        return sanitize(skyMask, skyUpdates, blockMask, blockUpdates, minHeight, minHeight, hideBelowY);
    }

    /**
     * Darkens light sections overlapping the concealed band
     * {@code hideMinY <= y < hideBelowY} that end at or below the cutoff
     * (sections crossing hide-below-y are left intact). When the band starts
     * at the world bottom, the extra light section below the world is
     * included too.
     */
    static int sanitize(
            BitSet skyMask,
            List<byte[]> skyUpdates,
            BitSet blockMask,
            List<byte[]> blockUpdates,
            int minHeight,
            int hideMinY,
            int hideBelowY
    ) {
        int lowerBound = hideMinY <= minHeight ? Integer.MIN_VALUE : hideMinY;
        int changed = sanitizeUpdates(skyMask, skyUpdates, minHeight, lowerBound, hideBelowY);
        changed += sanitizeUpdates(blockMask, blockUpdates, minHeight, lowerBound, hideBelowY);
        return changed;
    }

    private static int sanitizeUpdates(BitSet mask, List<byte[]> updates, int minHeight, int lowerBound, int hideBelowY) {
        if (mask == null || updates == null || updates.isEmpty()) {
            return 0;
        }

        int minSection = Math.floorDiv(minHeight, 16);
        int updateIndex = 0;
        int changed = 0;
        for (int lightIndex = mask.nextSetBit(0); lightIndex >= 0; lightIndex = mask.nextSetBit(lightIndex + 1)) {
            if (updateIndex >= updates.size()) {
                throw new IllegalArgumentException("Light update mask contains more sections than its data list");
            }

            // Light masks include one section below the world's first block section.
            int sectionY = minSection + lightIndex - 1;
            int sectionMaxYExclusive = (sectionY + 1) * 16;
            byte[] update = updates.get(updateIndex);
            if (update == null || update.length != LIGHT_ARRAY_BYTES) {
                throw new IllegalArgumentException("Light update array must contain exactly "
                        + LIGHT_ARRAY_BYTES + " bytes");
            }
            // A section crossed by min-y is darkened too (fail closed): its
            // visible part lies below the band, deep underground, so the cost
            // is a little darkness there. A section crossed by hide-below-y is
            // left intact, because its visible part is the playable area
            // above the cutoff.
            if (sectionMaxYExclusive > lowerBound && sectionMaxYExclusive <= hideBelowY && containsLight(update)) {
                updates.set(updateIndex, new byte[LIGHT_ARRAY_BYTES]);
                changed++;
            }
            updateIndex++;
        }
        if (updateIndex != updates.size()) {
            throw new IllegalArgumentException("Light update data contains sections missing from its mask");
        }
        return changed;
    }

    private static boolean containsLight(byte[] update) {
        for (byte value : update) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }
}

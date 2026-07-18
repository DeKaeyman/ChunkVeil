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
        int changed = sanitizeUpdates(skyMask, skyUpdates, minHeight, hideBelowY);
        changed += sanitizeUpdates(blockMask, blockUpdates, minHeight, hideBelowY);
        return changed;
    }

    private static int sanitizeUpdates(BitSet mask, List<byte[]> updates, int minHeight, int hideBelowY) {
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
            if (sectionMaxYExclusive <= hideBelowY && containsLight(update)) {
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

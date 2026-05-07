package com.dekaeyman.chunkveil;

import org.bukkit.Material;

record VeilWorldSettings(boolean enabled, int hideBelowY, int minY, Material defaultFakeBlock, boolean hideAir, boolean hideEntities, boolean hidePlayers) {
    int hiddenSectionCount() {
        return Math.max(0, Math.floorDiv(hideBelowY - minY + 15, 16));
    }

    Material replacementFor(Material realBlock) {
        if (realBlock == defaultFakeBlock) {
            return null;
        }
        if (!hideAir && realBlock.isAir()) {
            return null;
        }

        return defaultFakeBlock;
    }
}

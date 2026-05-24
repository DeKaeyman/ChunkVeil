package com.dekaeyman.chunkveil;

import org.bukkit.Material;

record VeilWorldSettings(boolean enabled, int hideBelowY, int minY, Material defaultFakeBlock, boolean hideAir, boolean hideEntities, boolean hidePlayers) {
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

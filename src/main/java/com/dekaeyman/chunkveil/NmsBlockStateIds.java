package com.dekaeyman.chunkveil;

import java.lang.reflect.Method;
import org.bukkit.Material;

final class NmsBlockStateIds {
    private NmsBlockStateIds() {
    }

    /**
     * Bits per entry of the global (direct) block palette: ceil(log2(number
     * of block states)). The client sizes a direct-palette section's data by
     * this value and ignores the bit count sent in the packet, so a direct
     * section written with fewer bits is misread (and on 1.21.5+, without a
     * length prefix, desynchronizes the rest of the chunk).
     */
    static int globalPaletteBits() {
        try {
            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
            Object registry = blockClass.getField("BLOCK_STATE_REGISTRY").get(null);
            int size = (Integer) registry.getClass().getMethod("size").invoke(registry);
            return ceilLog2(size);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not resolve the block state registry size", exception);
        }
    }

    /** Same as Minecraft's Mth.ceillog2. */
    static int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    static int defaultStateId(Material material) {
        try {
            Class<?> craftMagicNumbers = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers");
            Method getBlock = craftMagicNumbers.getMethod("getBlock", Material.class);
            Object block = getBlock.invoke(null, material);

            Method defaultBlockState = block.getClass().getMethod("defaultBlockState");
            Object blockState = defaultBlockState.invoke(block);

            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
            Method getId = blockClass.getMethod("getId", Class.forName("net.minecraft.world.level.block.state.BlockState"));
            Object id = getId.invoke(null, blockState);
            return (Integer) id;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve NMS block state id for " + material, exception);
        }
    }
}

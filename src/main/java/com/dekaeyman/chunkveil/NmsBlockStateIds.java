package com.dekaeyman.chunkveil;

import java.lang.reflect.Method;
import org.bukkit.Material;

final class NmsBlockStateIds {
    private NmsBlockStateIds() {
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

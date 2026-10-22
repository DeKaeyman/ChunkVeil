package com.dekaeyman.chunkveil;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Fast block access for the reveal ray walk.
 *
 * <p>The Bukkit path, {@code world.getType(x, y, z).isOccluding()}, did a
 * chunk lookup and a block-to-Material map lookup for every voxel of every
 * ray; in a 100-player stress test that was the largest single cost in
 * ChunkVeil. This reads the block state straight from the chunk section,
 * keeps the current chunk between steps (a ray stays in one chunk for many
 * steps), skips all-air sections, and keeps the occlusion result per block
 * state in a shared cache. The answer is the same as before: the Material of
 * the block and its {@code isOccluding()}, only computed once per state.
 *
 * <p>Server internals are bound once with {@link LambdaMetafactory}: that
 * generates small real classes implementing the interfaces below, which the
 * JIT inlines like ordinary calls (plain method handles stored in fields were
 * measured going through the generic invoker in a stress test). Verified on
 * Paper 1.21.4, 1.21.11, 26.2 and Folia 26.2. If anything is missing or
 * fails, {@link #create} returns null and callers use the Bukkit path.
 *
 * <p>One instance per scan, used by one thread; the state cache is shared.
 */
final class FastTerrain implements ViewRay.Terrain {
    interface WorldHandle {
        Object get(World world);
    }

    interface ChunkIfLoaded {
        Object get(Object level, int chunkX, int chunkZ);
    }

    interface Sections {
        Object[] get(Object chunk);
    }

    interface OnlyAir {
        boolean test(Object section);
    }

    interface SectionState {
        Object get(Object section, int x, int y, int z);
    }

    interface StateBlock {
        Object get(Object state);
    }

    interface BlockMaterial {
        Material get(Object block);
    }

    private static final WorldHandle WORLD_HANDLE;
    private static final ChunkIfLoaded CHUNK_IF_LOADED;
    private static final Sections SECTIONS;
    private static final OnlyAir ONLY_AIR;
    private static final SectionState SECTION_STATE;
    private static final StateBlock STATE_BLOCK;
    private static final BlockMaterial BLOCK_MATERIAL;
    private static volatile boolean available;
    // -Dchunkveil.disableFastTerrain=true: use the Bukkit path (A/B tests, emergencies).
    static final boolean DISABLED_BY_PROPERTY = Boolean.getBoolean("chunkveil.disableFastTerrain");
    private static volatile String unavailableReason;

    static {
        WorldHandle worldHandle = null;
        ChunkIfLoaded chunkIfLoaded = null;
        Sections sections = null;
        OnlyAir onlyAir = null;
        SectionState sectionState = null;
        StateBlock stateBlock = null;
        BlockMaterial blockMaterial = null;
        boolean ok = false;
        try {
            MethodHandles.Lookup caller = MethodHandles.lookup();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> levelChunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            Class<?> chunkAccess = Class.forName("net.minecraft.world.level.chunk.ChunkAccess");
            Class<?> section = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
            Class<?> stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Class<?> block = Class.forName("net.minecraft.world.level.block.Block");
            Class<?> magicNumbers = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers");

            worldHandle = bind(caller, WorldHandle.class, "get",
                    lookup.findVirtual(craftWorld, "getHandle", MethodType.methodType(serverLevel)),
                    MethodType.methodType(serverLevel, craftWorld));
            chunkIfLoaded = bind(caller, ChunkIfLoaded.class, "get",
                    lookup.findVirtual(serverLevel, "getChunkIfLoaded", MethodType.methodType(levelChunk, int.class, int.class)),
                    MethodType.methodType(levelChunk, serverLevel, int.class, int.class));
            sections = bind(caller, Sections.class, "get",
                    lookup.findVirtual(chunkAccess, "getSections", MethodType.methodType(section.arrayType())),
                    MethodType.methodType(section.arrayType(), levelChunk));
            onlyAir = bind(caller, OnlyAir.class, "test",
                    lookup.findVirtual(section, "hasOnlyAir", MethodType.methodType(boolean.class)),
                    MethodType.methodType(boolean.class, section));
            sectionState = bind(caller, SectionState.class, "get",
                    lookup.findVirtual(section, "getBlockState", MethodType.methodType(blockState, int.class, int.class, int.class)),
                    MethodType.methodType(blockState, section, int.class, int.class, int.class));
            stateBlock = bind(caller, StateBlock.class, "get",
                    lookup.findVirtual(stateBase, "getBlock", MethodType.methodType(block)),
                    MethodType.methodType(block, blockState));
            blockMaterial = bind(caller, BlockMaterial.class, "get",
                    lookup.findStatic(magicNumbers, "getMaterial", MethodType.methodType(Material.class, block)),
                    MethodType.methodType(Material.class, block));
            ok = true;
        } catch (Throwable throwable) {
            unavailableReason = throwable.toString();
        }
        WORLD_HANDLE = worldHandle;
        CHUNK_IF_LOADED = chunkIfLoaded;
        SECTIONS = sections;
        ONLY_AIR = onlyAir;
        SECTION_STATE = sectionState;
        STATE_BLOCK = stateBlock;
        BLOCK_MATERIAL = blockMaterial;
        available = ok;
    }

    /** Generates an implementation of a one-method interface that calls {@code target}. */
    private static <T> T bind(MethodHandles.Lookup caller, Class<T> type, String name, MethodHandle target,
            MethodType instantiated) throws Throwable {
        MethodType erased = erasedSignature(type, name);
        CallSite site = LambdaMetafactory.metafactory(caller, name, MethodType.methodType(type),
                erased, target, instantiated);
        return type.cast(site.getTarget().invoke());
    }

    private static MethodType erasedSignature(Class<?> type, String name) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return MethodType.methodType(method.getReturnType(), method.getParameterTypes());
            }
        }
        throw new IllegalArgumentException(type + " has no method " + name);
    }

    // Shared, direct-mapped: block state object -> occluding. Entries are
    // immutable, so racy reads and writes from several threads are safe (a
    // miss simply recomputes). A world uses far fewer states than slots.
    private static final int STATE_CACHE_SIZE = 1 << 12;
    private static final StateEntry[] STATE_CACHE = new StateEntry[STATE_CACHE_SIZE];

    private record StateEntry(Object state, boolean occluding) {
    }

    private final World world;
    private final Object level;
    private final int minSection;
    private final Logger logger;

    private int currentChunkX = Integer.MIN_VALUE;
    private int currentChunkZ = Integer.MIN_VALUE;
    private Object[] currentSections;

    private FastTerrain(World world, Object level, Logger logger) {
        this.world = world;
        this.level = level;
        this.minSection = world.getMinHeight() >> 4;
        this.logger = logger;
    }

    /** Fast terrain for a scan, or null when unavailable (use the Bukkit path). */
    static FastTerrain create(World world, Logger logger) {
        if (!available || DISABLED_BY_PROPERTY) {
            return null;
        }
        try {
            return new FastTerrain(world, WORLD_HANDLE.get(world), logger);
        } catch (Throwable throwable) {
            disable(logger, throwable);
            return null;
        }
    }

    static boolean available() {
        return available;
    }

    static String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public boolean canEnterChunk(int chunkX, int chunkZ) {
        if (chunkX == currentChunkX && chunkZ == currentChunkZ && currentSections != null) {
            return true;
        }
        // Folia: never touch a chunk another region owns.
        if (!VeilScheduler.canAccessChunk(world, chunkX, chunkZ)) {
            return false;
        }
        try {
            Object chunk = CHUNK_IF_LOADED.get(level, chunkX, chunkZ);
            if (chunk == null) {
                return false;
            }
            currentSections = SECTIONS.get(chunk);
            currentChunkX = chunkX;
            currentChunkZ = chunkZ;
            return true;
        } catch (Throwable throwable) {
            disable(logger, throwable);
            currentSections = null;
            return false;
        }
    }

    @Override
    public boolean isOccluding(int x, int y, int z) {
        if ((x >> 4) != currentChunkX || (z >> 4) != currentChunkZ || currentSections == null) {
            if (!canEnterChunk(x >> 4, z >> 4)) {
                // The walk stops at chunks it cannot enter; treat as solid.
                return true;
            }
        }
        Object[] sections = currentSections;
        int index = (y >> 4) - minSection;
        if (index < 0 || index >= sections.length) {
            return false;
        }
        Object section = sections[index];
        if (section == null) {
            return false;
        }
        try {
            if (ONLY_AIR.test(section)) {
                return false;
            }
            return occluding(SECTION_STATE.get(section, x & 15, y & 15, z & 15));
        } catch (Throwable throwable) {
            disable(logger, throwable);
            // Fail toward less reveal: an unreadable block counts as solid.
            return true;
        }
    }

    private static boolean occluding(Object state) {
        int slot = System.identityHashCode(state) & (STATE_CACHE_SIZE - 1);
        StateEntry entry = STATE_CACHE[slot];
        if (entry != null && entry.state() == state) {
            return entry.occluding();
        }
        Material material = BLOCK_MATERIAL.get(STATE_BLOCK.get(state));
        boolean occluding = material != null && material.isOccluding();
        STATE_CACHE[slot] = new StateEntry(state, occluding);
        return occluding;
    }

    private static void disable(Logger logger, Throwable throwable) {
        if (available) {
            available = false;
            unavailableReason = throwable.toString();
            if (logger != null) {
                logger.warning("Fast block access for reveal scans disabled (" + throwable
                        + "); using the slower Bukkit path.");
            }
        }
    }
}

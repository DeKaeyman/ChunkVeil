package com.dekaeyman.chunkveil;

import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import java.util.Arrays;
import org.bukkit.World;

/**
 * Rewrites the block containers of concealed chunk sections inside a chunk
 * packet buffer.
 *
 * <p>This runs for every chunk sent to every player, on the sending thread
 * (the main thread on Paper, region threads on Folia), so it avoids
 * per-section allocations:
 * <ul>
 *   <li>Sections outside the concealed band, and every biome container, are
 *       only skip-parsed and copied straight from the input in bulk.</li>
 *   <li>A section fully inside the band with hide-air writes a single-value
 *       container without decoding the source data at all.</li>
 *   <li>Other concealed sections remap source palette entries (not blocks)
 *       where the source is indirect, using primitive arrays and a primitive
 *       state-to-index map held in per-thread scratch space.</li>
 * </ul>
 * The output is byte-identical to the original straightforward
 * implementation (kept in the tests as an equivalence oracle), except that a
 * rewritten direct-palette section uses the global palette width so the
 * client can read it (see {@link #directPaletteBits(int)}).
 */
final class ChunkPacketBlockRewriter {
    private static final int BLOCK_PALETTE_BITS = 4;
    private static final int MAX_INDIRECT_BLOCK_BITS = 8;
    private static final int MAX_INDIRECT_BIOME_BITS = 3;
    private static final int BLOCKS_PER_SECTION = 4096;
    private static final int BIOMES_PER_SECTION = 64;
    private static final int BLOCKS_PER_LAYER = 256;

    // Scratch space is per thread: on Folia several region threads rewrite
    // chunks at the same time through the same rewriter instance.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final int fakeBlockStateId;
    private final boolean fakeBlockIsAir;
    // Every air-like state (air, cave_air, void_air), matching vanilla's
    // BlockState#isAir. Carver caves are cave_air; treating only plain air
    // as air filled those caves with the fake block under hide-air: false
    // and miscounted non-empty blocks.
    private final int[] airStateIds;
    private final boolean sectionBlockCountIsLeInt;
    // Before Minecraft 1.21.5 every paletted container's data array was
    // preceded by a VarInt length (0 for single-value containers); 1.21.5
    // removed it. Reading one format as the other misparses every section.
    private final boolean dataArrayLengthPrefixed;
    // The client reads direct-palette data with exactly this many bits per
    // entry, whatever bit count the packet declares.
    private final int globalPaletteBits;
    // Same chunk to several players = one rewrite (about 10 MB at most).
    private final ChunkRewriteCache cache = new ChunkRewriteCache(512);
    // -Dchunkveil.disableRewriteCache=true: rewrite every packet (A/B tests, emergencies).
    private static final boolean REWRITE_CACHE_DISABLED = Boolean.getBoolean("chunkveil.disableRewriteCache");

    ChunkPacketBlockRewriter(int fakeBlockStateId, int airBlockStateId, boolean sectionBlockCountIsLeInt) {
        this(fakeBlockStateId, new int[]{airBlockStateId}, sectionBlockCountIsLeInt, false);
    }

    ChunkPacketBlockRewriter(int fakeBlockStateId, int airBlockStateId, boolean sectionBlockCountIsLeInt,
            boolean dataArrayLengthPrefixed) {
        this(fakeBlockStateId, new int[]{airBlockStateId}, sectionBlockCountIsLeInt, dataArrayLengthPrefixed);
    }

    ChunkPacketBlockRewriter(int fakeBlockStateId, int[] airStateIds, boolean sectionBlockCountIsLeInt,
            boolean dataArrayLengthPrefixed) {
        this(fakeBlockStateId, airStateIds, sectionBlockCountIsLeInt, dataArrayLengthPrefixed, 0);
    }

    /**
     * @param globalPaletteBits bits of the server's global block palette
     *        (see {@link NmsBlockStateIds#globalPaletteBits()}); 0 only in tests
     *        that compare against the old writer.
     */
    ChunkPacketBlockRewriter(int fakeBlockStateId, int[] airStateIds, boolean sectionBlockCountIsLeInt,
            boolean dataArrayLengthPrefixed, int globalPaletteBits) {
        if (airStateIds == null || airStateIds.length == 0) {
            throw new IllegalArgumentException("at least one air state id is required");
        }
        this.fakeBlockStateId = fakeBlockStateId;
        this.airStateIds = airStateIds.clone();
        this.fakeBlockIsAir = isAir(fakeBlockStateId);
        this.sectionBlockCountIsLeInt = sectionBlockCountIsLeInt;
        this.dataArrayLengthPrefixed = dataArrayLengthPrefixed;
        this.globalPaletteBits = globalPaletteBits;
    }

    private boolean isAir(int stateId) {
        for (int airStateId : airStateIds) {
            if (stateId == airStateId) {
                return true;
            }
        }
        return false;
    }

    private int concealedState(int stateId, boolean hideAir) {
        return hideAir || !isAir(stateId) ? fakeBlockStateId : stateId;
    }

    /** Whether a Minecraft version uses length-prefixed paletted-container data arrays (before 1.21.5). */
    static boolean dataArrayLengthPrefixed(String minecraftVersion) {
        if (minecraftVersion == null) {
            return false;
        }
        String[] parts = minecraftVersion.split("-", 2)[0].split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major != 1) {
                return false;
            }
            return minor < 21 || (minor == 21 && patch < 5);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Rewrites the section buffer of an already-read chunk data wrapper. The
     * caller writes the wrapper back once, after its other changes (block
     * entities), instead of every step reading and writing it separately.
     */
    int rewriteHiddenSections(WrappedLevelChunkData.ChunkData chunkData, World world, int hideMinY, int hideBelowY,
            boolean hideAir) {
        byte[] input = chunkData.getBuffer();
        if (input == null || input.length == 0) {
            return 0;
        }

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        RewriteResult result;
        if (REWRITE_CACHE_DISABLED) {
            result = rewriteBuffer(input, minHeight, maxHeight, hideMinY, hideBelowY, hideAir);
        } else {
            ChunkRewriteCache.Key key = ChunkRewriteCache.key(input, minHeight, maxHeight, hideMinY, hideBelowY, hideAir);
            result = cache.get(key);
            if (result == null) {
                result = rewriteBuffer(input, minHeight, maxHeight, hideMinY, hideBelowY, hideAir);
                if (result.rewrittenSections() > 0) {
                    cache.put(key, result);
                }
            }
        }
        if (result.rewrittenSections() > 0) {
            chunkData.setBuffer(result.buffer());
        }
        return result.hiddenSections();
    }

    long cacheHits() {
        return cache.hits();
    }

    long cacheMisses() {
        return cache.misses();
    }

    // Pure buffer-level rewrite, separated from ProtocolLib and Bukkit types so
    // the packet regression test suite can exercise the wire-format handling.
    RewriteResult rewriteBuffer(byte[] input, int minHeight, int maxHeight, int hideBelowY, boolean hideAir) {
        return rewriteBuffer(input, minHeight, maxHeight, minHeight, hideBelowY, hideAir);
    }

    /**
     * Conceals blocks with {@code hideMinY <= y < hideBelowY}; sections outside that band pass through.
     * When no section is rewritten the input array itself is returned.
     */
    RewriteResult rewriteBuffer(byte[] input, int minHeight, int maxHeight, int hideMinY, int hideBelowY, boolean hideAir) {
        int minSection = Math.floorDiv(minHeight, 16);
        int sectionCount = Math.floorDiv(maxHeight - minHeight, 16);
        int effectiveHideMinY = Math.max(minHeight, hideMinY);
        if (hideBelowY <= effectiveHideMinY) {
            return new RewriteResult(input, 0, 0);
        }

        Scratch scratch = SCRATCH.get();
        ByteSink output = scratch.sink;
        output.reset(input.length);
        PacketReader reader = new PacketReader(input, dataArrayLengthPrefixed);
        // Input bytes from copyFrom up to the current section are unchanged
        // and still have to be copied; they are flushed in one bulk copy.
        int copyFrom = 0;
        int rewrittenSections = 0;

        try {
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                int sectionMinY = (minSection + sectionIndex) * 16;
                boolean hiddenSection = sectionMinY < hideBelowY && sectionMinY + 16 > effectiveHideMinY;
                int sectionStart = reader.index;

                if (sectionBlockCountIsLeInt) {
                    reader.readLittleEndianInt();
                } else {
                    reader.readUnsignedShort();
                }

                if (!hiddenSection) {
                    reader.skipPalettedContainer(MAX_INDIRECT_BLOCK_BITS, BLOCKS_PER_SECTION);
                    reader.skipPalettedContainer(MAX_INDIRECT_BIOME_BITS, BIOMES_PER_SECTION);
                    continue;
                }

                output.write(input, copyFrom, sectionStart - copyFrom);
                rewriteSection(reader, output, scratch, sectionMinY, effectiveHideMinY, hideBelowY, hideAir);
                rewrittenSections++;
                // The biome container is copied unchanged with the next run.
                copyFrom = reader.index;
                reader.skipPalettedContainer(MAX_INDIRECT_BIOME_BITS, BIOMES_PER_SECTION);
            }

            if (rewrittenSections == 0) {
                return new RewriteResult(input, 0, 0);
            }
            output.write(input, copyFrom, input.length - copyFrom);
            return new RewriteResult(output.toByteArray(), rewrittenSections, rewrittenSections);
        } finally {
            output.releaseIfOversized();
        }
    }

    record RewriteResult(byte[] buffer, int hiddenSections, int rewrittenSections) {
    }

    /**
     * Reads one concealed section's block container and writes its
     * replacement, prefixed by the recomputed non-empty block count.
     */
    private void rewriteSection(PacketReader reader, ByteSink output, Scratch scratch, int sectionMinY,
            int hideMinY, int hideBelowY, boolean hideAir) {
        boolean fullyHidden = sectionMinY >= hideMinY && sectionMinY + 16 <= hideBelowY;
        int bitsPerEntry = reader.readUnsignedByte();
        boolean indirect = bitsPerEntry <= MAX_INDIRECT_BLOCK_BITS;
        int paletteLength;
        int[] sourcePalette = scratch.sourcePalette;
        if (bitsPerEntry == 0) {
            sourcePalette[0] = reader.readVarInt();
            reader.readDataArrayLength(0);
            paletteLength = 1;
        } else if (indirect) {
            paletteLength = reader.readVarInt();
            sourcePalette = scratch.sourcePalette(paletteLength);
            for (int i = 0; i < paletteLength; i++) {
                sourcePalette[i] = reader.readVarInt();
            }
        } else {
            paletteLength = 0;
        }

        // Fully concealed with hide-air: every block becomes the fake block,
        // whatever the source holds, so its data is skipped, not decoded.
        if (fullyHidden && hideAir) {
            if (bitsPerEntry != 0) {
                reader.skipDataArray(bitsPerEntry, BLOCKS_PER_SECTION);
            }
            writeBlockCount(output, fakeBlockIsAir ? 0 : BLOCKS_PER_SECTION);
            writeSingleValue(output, fakeBlockStateId);
            return;
        }

        int[] blocks = scratch.blocks;
        IntIndexMap palette = scratch.palette;
        palette.clear();

        if (bitsPerEntry == 0) {
            // One source state: each layer maps to a single output entry.
            int state = sourcePalette[0];
            int visibleIndex = -1;
            int hiddenIndex = -1;
            for (int layer = 0; layer < 16; layer++) {
                int blockY = sectionMinY + layer;
                int outIndex;
                if (blockY >= hideMinY && blockY < hideBelowY) {
                    if (hiddenIndex < 0) {
                        hiddenIndex = palette.indexOf(concealedState(state, hideAir));
                    }
                    outIndex = hiddenIndex;
                } else {
                    if (visibleIndex < 0) {
                        visibleIndex = palette.indexOf(state);
                    }
                    outIndex = visibleIndex;
                }
                int from = layer * BLOCKS_PER_LAYER;
                Arrays.fill(blocks, from, from + BLOCKS_PER_LAYER, outIndex);
            }
        } else {
            reader.decodeDataArray(bitsPerEntry, blocks);
            if (indirect) {
                remapIndirect(blocks, sourcePalette, paletteLength, 1 << bitsPerEntry, scratch,
                        sectionMinY, hideMinY, hideBelowY, hideAir);
            } else {
                remapDirect(blocks, palette, sectionMinY, hideMinY, hideBelowY, hideAir);
            }
        }

        int[] states = palette.keys;
        int size = palette.size;
        boolean[] entryIsAir = scratch.entryIsAir;
        for (int i = 0; i < size; i++) {
            entryIsAir[i] = isAir(states[i]);
        }
        int nonEmptyBlockCount = 0;
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            if (!entryIsAir[blocks[i]]) {
                nonEmptyBlockCount++;
            }
        }
        writeBlockCount(output, nonEmptyBlockCount);
        writeContainer(output, blocks, states, size);
    }

    /**
     * Maps decoded palette indices to output palette indices. Each source
     * entry is resolved once per visibility (concealed or not), so the
     * per-block work is two array lookups.
     */
    private void remapIndirect(int[] blocks, int[] sourcePalette, int paletteLength, int indexRange, Scratch scratch,
            int sectionMinY, int hideMinY, int hideBelowY, boolean hideAir) {
        int[] hiddenSlots = scratch.hiddenSlots;
        int[] visibleSlots = scratch.visibleSlots;
        Arrays.fill(hiddenSlots, 0, indexRange, -1);
        Arrays.fill(visibleSlots, 0, indexRange, -1);
        IntIndexMap palette = scratch.palette;

        for (int layer = 0; layer < 16; layer++) {
            int blockY = sectionMinY + layer;
            boolean hidden = blockY >= hideMinY && blockY < hideBelowY;
            int[] slots = hidden ? hiddenSlots : visibleSlots;
            int end = (layer + 1) * BLOCKS_PER_LAYER;
            for (int i = layer * BLOCKS_PER_LAYER; i < end; i++) {
                int sourceIndex = blocks[i];
                int outIndex = slots[sourceIndex];
                if (outIndex < 0) {
                    // An index past the palette reads as state 0, as before.
                    int state = sourceIndex < paletteLength ? sourcePalette[sourceIndex] : 0;
                    outIndex = palette.indexOf(hidden ? concealedState(state, hideAir) : state);
                    slots[sourceIndex] = outIndex;
                }
                blocks[i] = outIndex;
            }
        }
    }

    /** Direct (global) source palette: the decoded values are block states. */
    private void remapDirect(int[] blocks, IntIndexMap palette, int sectionMinY, int hideMinY, int hideBelowY,
            boolean hideAir) {
        for (int layer = 0; layer < 16; layer++) {
            int blockY = sectionMinY + layer;
            boolean hidden = blockY >= hideMinY && blockY < hideBelowY;
            int end = (layer + 1) * BLOCKS_PER_LAYER;
            for (int i = layer * BLOCKS_PER_LAYER; i < end; i++) {
                int state = blocks[i];
                blocks[i] = palette.indexOf(hidden ? concealedState(state, hideAir) : state);
            }
        }
    }

    /** Writes a block container for blocks holding output palette indices into {@code states}. */
    private void writeContainer(ByteSink output, int[] blocks, int[] states, int size) {
        if (size == 1) {
            writeSingleValue(output, states[0]);
            return;
        }

        if (size <= (1 << MAX_INDIRECT_BLOCK_BITS)) {
            int bitsPerEntry = Math.max(BLOCK_PALETTE_BITS, bitsNeeded(size - 1));
            output.write(bitsPerEntry);
            output.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                output.writeVarInt(states[i]);
            }
            writePackedValues(output, blocks, null, bitsPerEntry);
            return;
        }

        int maxStateId = 0;
        for (int i = 0; i < size; i++) {
            maxStateId = Math.max(maxStateId, states[i]);
        }
        int bitsPerEntry = directPaletteBits(maxStateId);
        output.write(bitsPerEntry);
        writePackedValues(output, blocks, states, bitsPerEntry);
    }

    /**
     * Direct (global) palette width. Vanilla always writes the registry width
     * and the client always reads that width; the per-section minimum used
     * before (max(9, bits of the largest state)) was narrower whenever the
     * section's largest state id was below 2^14, corrupting such sections.
     */
    int directPaletteBits(int maxStateId) {
        int legacyBits = Math.max(9, bitsNeeded(maxStateId));
        return globalPaletteBits > 0 ? Math.max(globalPaletteBits, bitsNeeded(maxStateId)) : legacyBits;
    }

    private void writeSingleValue(ByteSink output, int stateId) {
        output.write(0);
        output.writeVarInt(stateId);
        if (dataArrayLengthPrefixed) {
            output.writeVarInt(0);
        }
    }

    /** Packs {@code values} (or {@code lookup[values[i]]} when a lookup is given) without an intermediate long[]. */
    private void writePackedValues(ByteSink output, int[] values, int[] lookup, int bitsPerEntry) {
        int entriesPerLong = Long.SIZE / bitsPerEntry;
        if (dataArrayLengthPrefixed) {
            output.writeVarInt((BLOCKS_PER_SECTION + entriesPerLong - 1) / entriesPerLong);
        }
        long mask = (1L << bitsPerEntry) - 1L;
        long packed = 0L;
        int inLong = 0;
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int value = lookup == null ? values[i] : lookup[values[i]];
            packed |= ((long) value & mask) << (inLong * bitsPerEntry);
            if (++inLong == entriesPerLong) {
                output.writeLong(packed);
                packed = 0L;
                inLong = 0;
            }
        }
        if (inLong > 0) {
            output.writeLong(packed);
        }
    }

    private void writeBlockCount(ByteSink output, int count) {
        if (sectionBlockCountIsLeInt) {
            output.writeLittleEndianInt(count);
        } else {
            output.writeShort(count);
        }
    }

    private static int bitsNeeded(int maxValue) {
        if (maxValue <= 0) {
            return 1;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    /** Per-thread working memory, reused across chunks. */
    private static final class Scratch {
        final int[] blocks = new int[BLOCKS_PER_SECTION];
        final boolean[] entryIsAir = new boolean[BLOCKS_PER_SECTION];
        final int[] hiddenSlots = new int[1 << MAX_INDIRECT_BLOCK_BITS];
        final int[] visibleSlots = new int[1 << MAX_INDIRECT_BLOCK_BITS];
        final IntIndexMap palette = new IntIndexMap();
        final ByteSink sink = new ByteSink();
        int[] sourcePalette = new int[1 << MAX_INDIRECT_BLOCK_BITS];

        int[] sourcePalette(int length) {
            if (length < 0) {
                throw new IllegalArgumentException("Negative palette length " + length);
            }
            if (length > sourcePalette.length) {
                // A palette longer than 256 entries is malformed but was
                // accepted before; it still has to be read past.
                sourcePalette = new int[length];
            }
            return sourcePalette;
        }
    }

    /**
     * Open-addressing map from block state to its first-seen index. At most
     * 4096 distinct states fit in a section, so the table never exceeds half
     * load. Clearing only touches the slots that were used.
     */
    static final class IntIndexMap {
        private static final int TABLE_SIZE = 1 << 13;
        private static final int TABLE_MASK = TABLE_SIZE - 1;

        private final int[] table = new int[TABLE_SIZE]; // entry index + 1, 0 = empty
        private final int[] slotOfEntry = new int[BLOCKS_PER_SECTION];
        final int[] keys = new int[BLOCKS_PER_SECTION];
        int size;

        void clear() {
            for (int i = 0; i < size; i++) {
                table[slotOfEntry[i]] = 0;
            }
            size = 0;
        }

        /** Returns the key's index, adding it at the end when absent. */
        int indexOf(int key) {
            int slot = (key * 0x9E3779B9) >>> 19;
            while (true) {
                int stored = table[slot];
                if (stored == 0) {
                    table[slot] = size + 1;
                    keys[size] = key;
                    slotOfEntry[size] = slot;
                    return size++;
                }
                if (keys[stored - 1] == key) {
                    return stored - 1;
                }
                slot = (slot + 1) & TABLE_MASK;
            }
        }
    }

    /** Unsynchronized growable byte buffer (ByteArrayOutputStream synchronizes every write). */
    static final class ByteSink {
        // Keep at most this much per thread between chunks.
        private static final int RETAINED_CAPACITY = 1 << 20;

        private byte[] buffer = new byte[0];
        private int size;

        void reset(int expectedSize) {
            size = 0;
            if (buffer.length < expectedSize) {
                buffer = new byte[Math.max(expectedSize, 1024)];
            }
        }

        void releaseIfOversized() {
            if (buffer.length > RETAINED_CAPACITY) {
                buffer = new byte[0];
            }
            size = 0;
        }

        private void ensure(int extra) {
            int needed = size + extra;
            if (needed > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(needed, buffer.length + (buffer.length >> 1) + 64));
            }
        }

        void write(int value) {
            ensure(1);
            buffer[size++] = (byte) value;
        }

        void write(byte[] source, int offset, int length) {
            if (length <= 0) {
                return;
            }
            ensure(length);
            System.arraycopy(source, offset, buffer, size, length);
            size += length;
        }

        void writeShort(int value) {
            ensure(2);
            buffer[size++] = (byte) (value >>> 8);
            buffer[size++] = (byte) value;
        }

        void writeLittleEndianInt(int value) {
            ensure(4);
            buffer[size++] = (byte) value;
            buffer[size++] = (byte) (value >>> 8);
            buffer[size++] = (byte) (value >>> 16);
            buffer[size++] = (byte) (value >>> 24);
        }

        void writeVarInt(int value) {
            ensure(5);
            while ((value & 0xFFFFFF80) != 0) {
                buffer[size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buffer[size++] = (byte) (value & 0x7F);
        }

        void writeLong(long value) {
            ensure(8);
            for (int shift = 56; shift >= 0; shift -= 8) {
                buffer[size++] = (byte) (value >>> shift);
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buffer, size);
        }
    }

    private static final class PacketReader {
        private final byte[] data;
        private final boolean dataArrayLengthPrefixed;
        private int index;

        private PacketReader(byte[] data, boolean dataArrayLengthPrefixed) {
            this.data = data;
            this.dataArrayLengthPrefixed = dataArrayLengthPrefixed;
        }

        /**
         * Consumes the pre-1.21.5 data-array length and checks it against the
         * length implied by bits-per-entry. A mismatch means the server uses
         * the other format (or the packet is corrupt): fail loudly rather than
         * misparse the rest of the chunk.
         */
        private void readDataArrayLength(int expectedLongs) {
            if (!dataArrayLengthPrefixed) {
                return;
            }
            int declaredLongs = readVarInt();
            if (declaredLongs != expectedLongs) {
                throw new IllegalArgumentException("Paletted container declares " + declaredLongs
                        + " data longs but " + expectedLongs + " were expected (chunk format mismatch)");
            }
        }

        int readUnsignedShort() {
            require(2);
            int value = ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
            index += 2;
            return value;
        }

        int readLittleEndianInt() {
            require(4);
            int value = (data[index] & 0xFF)
                    | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16)
                    | ((data[index + 3] & 0xFF) << 24);
            index += 4;
            return value;
        }

        /** Moves past a paletted container without decoding it. */
        void skipPalettedContainer(int maxIndirectPaletteBits, int entryCount) {
            int bitsPerEntry = readUnsignedByte();
            if (bitsPerEntry == 0) {
                readVarInt();
                readDataArrayLength(0);
                return;
            }

            if (bitsPerEntry <= maxIndirectPaletteBits) {
                int paletteLength = readVarInt();
                for (int i = 0; i < paletteLength; i++) {
                    readVarInt();
                }
            }

            skipDataArray(bitsPerEntry, entryCount);
        }

        void skipDataArray(int bitsPerEntry, int entryCount) {
            int length = dataArrayLongs(bitsPerEntry, entryCount);
            readDataArrayLength(length);
            require(length * Long.BYTES);
            index += length * Long.BYTES;
        }

        /** Decodes a 4096-entry data array straight from the buffer into {@code out}. */
        void decodeDataArray(int bitsPerEntry, int[] out) {
            int entryCount = out.length;
            int length = dataArrayLongs(bitsPerEntry, entryCount);
            readDataArrayLength(length);
            require(length * Long.BYTES);
            int entriesPerLong = Long.SIZE / bitsPerEntry;
            long mask = (1L << bitsPerEntry) - 1L;
            int entry = 0;
            for (int i = 0; i < length; i++) {
                long value = readLongUnchecked();
                int end = Math.min(entry + entriesPerLong, entryCount);
                for (; entry < end; entry++) {
                    out[entry] = (int) (value & mask);
                    value >>>= bitsPerEntry;
                }
            }
        }

        private static int dataArrayLongs(int bitsPerEntry, int entryCount) {
            int entriesPerLong = Math.floorDiv(Long.SIZE, bitsPerEntry);
            if (entriesPerLong <= 0) {
                throw new IllegalArgumentException("Invalid bits per entry " + bitsPerEntry);
            }
            return Math.floorDiv(entryCount + entriesPerLong - 1, entriesPerLong);
        }

        private int readUnsignedByte() {
            require(1);
            return data[index++] & 0xFF;
        }

        private int readVarInt() {
            int value = 0;
            int position = 0;
            byte currentByte;
            do {
                require(1);
                currentByte = data[index++];
                value |= (currentByte & 0x7F) << position;
                position += 7;
                if (position >= 35) {
                    throw new IllegalArgumentException("VarInt is too big");
                }
            } while ((currentByte & 0x80) != 0);
            return value;
        }

        private long readLongUnchecked() {
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (data[index++] & 0xFFL);
            }
            return value;
        }

        private void require(int bytes) {
            if (bytes < 0 || index + bytes > data.length) {
                throw new IllegalArgumentException("Chunk packet buffer ended early (needed " + bytes
                        + " byte(s) at offset " + index + " of " + data.length + ")");
            }
        }
    }
}

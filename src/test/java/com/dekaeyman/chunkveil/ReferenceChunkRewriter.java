package com.dekaeyman.chunkveil;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The 1.1.0-rc.4 rewriter kept verbatim as an oracle for the optimized one (equivalence test only). */
final class ReferenceChunkRewriter {
    private static final int BLOCK_PALETTE_BITS = 4;
    private static final int BLOCKS_PER_SECTION = 4096;

    private final int fakeBlockStateId;
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

    ReferenceChunkRewriter(int fakeBlockStateId, int airBlockStateId, boolean sectionBlockCountIsLeInt) {
        this(fakeBlockStateId, new int[]{airBlockStateId}, sectionBlockCountIsLeInt, false);
    }

    ReferenceChunkRewriter(int fakeBlockStateId, int airBlockStateId, boolean sectionBlockCountIsLeInt,
            boolean dataArrayLengthPrefixed) {
        this(fakeBlockStateId, new int[]{airBlockStateId}, sectionBlockCountIsLeInt, dataArrayLengthPrefixed);
    }

    ReferenceChunkRewriter(int fakeBlockStateId, int[] airStateIds, boolean sectionBlockCountIsLeInt,
            boolean dataArrayLengthPrefixed) {
        if (airStateIds == null || airStateIds.length == 0) {
            throw new IllegalArgumentException("at least one air state id is required");
        }
        this.fakeBlockStateId = fakeBlockStateId;
        this.airStateIds = airStateIds.clone();
        this.sectionBlockCountIsLeInt = sectionBlockCountIsLeInt;
        this.dataArrayLengthPrefixed = dataArrayLengthPrefixed;
    }

    private boolean isAir(int stateId) {
        for (int airStateId : airStateIds) {
            if (stateId == airStateId) {
                return true;
            }
        }
        return false;
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

    // Pure buffer-level rewrite, separated from ProtocolLib and Bukkit types so
    // the packet regression test suite can exercise the wire-format handling.
    RewriteResult rewriteBuffer(byte[] input, int minHeight, int maxHeight, int hideBelowY, boolean hideAir) {
        return rewriteBuffer(input, minHeight, maxHeight, minHeight, hideBelowY, hideAir);
    }

    /** Conceals blocks with {@code hideMinY <= y < hideBelowY}; sections outside that band pass through. */
    RewriteResult rewriteBuffer(byte[] input, int minHeight, int maxHeight, int hideMinY, int hideBelowY, boolean hideAir) {
        int minSection = Math.floorDiv(minHeight, 16);
        int sectionCount = Math.floorDiv(maxHeight - minHeight, 16);
        int effectiveHideMinY = Math.max(minHeight, hideMinY);
        if (hideBelowY <= effectiveHideMinY) {
            return new RewriteResult(input, 0, 0);
        }

        PacketReader reader = new PacketReader(input, dataArrayLengthPrefixed);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int handledHiddenSections = 0;
        int rewrittenSections = 0;

        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            int sectionY = minSection + sectionIndex;
            int sectionMinY = sectionY * 16;
            boolean hiddenSection = sectionMinY < hideBelowY && sectionMinY + 16 > effectiveHideMinY;

            int nonEmptyBlockCount = sectionBlockCountIsLeInt ? reader.readLittleEndianInt() : reader.readUnsignedShort();
            PalettedContainer blockContainer = reader.readPalettedContainer(8, BLOCKS_PER_SECTION);
            byte[] biomeContainer = reader.readPalettedContainerBytes(3, 64);

            if (hiddenSection) {
                handledHiddenSections++;
                RewrittenSection rewrittenSection = rewriteSection(blockContainer, sectionMinY, effectiveHideMinY, hideBelowY, hideAir);
                if (sectionBlockCountIsLeInt) {
                    writeLittleEndianInt(output, rewrittenSection.nonEmptyBlockCount());
                } else {
                    writeShort(output, rewrittenSection.nonEmptyBlockCount());
                }
                output.writeBytes(rewrittenSection.bytes());
                rewrittenSections++;
            } else {
                if (sectionBlockCountIsLeInt) {
                    writeLittleEndianInt(output, nonEmptyBlockCount);
                } else {
                    writeShort(output, nonEmptyBlockCount);
                }
                output.writeBytes(blockContainer.bytes());
            }
            output.writeBytes(biomeContainer);
        }

        if (reader.remaining() > 0) {
            output.writeBytes(reader.readRemaining());
        }

        return new RewriteResult(output.toByteArray(), handledHiddenSections, rewrittenSections);
    }

    record RewriteResult(byte[] buffer, int hiddenSections, int rewrittenSections) {
    }

    private RewrittenSection rewriteSection(PalettedContainer source, int sectionMinY, int hideMinY, int hideBelowY, boolean hideAir) {
        int[] stateIds = new int[BLOCKS_PER_SECTION];
        int nonEmptyBlockCount = 0;
        for (int blockIndex = 0; blockIndex < BLOCKS_PER_SECTION; blockIndex++) {
            int blockY = sectionMinY + (blockIndex >> 8);
            boolean hiddenBlock = blockY >= hideMinY && blockY < hideBelowY;
            int stateId = source.stateId(blockIndex);
            boolean air = isAir(stateId);
            if (hiddenBlock && (hideAir || !air)) {
                stateId = fakeBlockStateId;
                air = isAir(stateId);
            }

            stateIds[blockIndex] = stateId;
            if (!air) {
                nonEmptyBlockCount++;
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(source.bytes().length);
        writePalette(output, stateIds);
        return new RewrittenSection(output.toByteArray(), nonEmptyBlockCount);
    }

    private void writePalette(ByteArrayOutputStream output, int[] stateIds) {
        Map<Integer, Integer> palette = new LinkedHashMap<>();
        for (int stateId : stateIds) {
            palette.computeIfAbsent(stateId, ignored -> palette.size());
        }

        if (palette.size() == 1) {
            output.write(0);
            writeVarInt(output, stateIds[0]);
            if (dataArrayLengthPrefixed) {
                writeVarInt(output, 0);
            }
            return;
        }

        if (palette.size() <= (1 << 8)) {
            int bitsPerEntry = Math.max(BLOCK_PALETTE_BITS, bitsNeeded(palette.size() - 1));
            output.write(bitsPerEntry);
            writeVarInt(output, palette.size());
            for (int stateId : palette.keySet()) {
                writeVarInt(output, stateId);
            }
            writePackedValues(output, stateIds, bitsPerEntry, palette);
            return;
        }

        int maxStateId = 0;
        for (int stateId : stateIds) {
            maxStateId = Math.max(maxStateId, stateId);
        }
        int bitsPerEntry = Math.max(9, bitsNeeded(maxStateId));
        output.write(bitsPerEntry);
        writePackedValues(output, stateIds, bitsPerEntry, null);
    }

    private void writePackedValues(ByteArrayOutputStream output, int[] stateIds, int bitsPerEntry, Map<Integer, Integer> palette) {
        int entriesPerLong = Math.floorDiv(Long.SIZE, bitsPerEntry);
        int arrayLength = Math.floorDiv(BLOCKS_PER_SECTION + entriesPerLong - 1, entriesPerLong);
        if (dataArrayLengthPrefixed) {
            writeVarInt(output, arrayLength);
        }
        long[] values = new long[arrayLength];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int blockIndex = 0; blockIndex < BLOCKS_PER_SECTION; blockIndex++) {
            int value = palette == null ? stateIds[blockIndex] : palette.get(stateIds[blockIndex]);
            int dataIndex = blockIndex / entriesPerLong;
            int bitOffset = (blockIndex % entriesPerLong) * bitsPerEntry;
            values[dataIndex] |= ((long) value & mask) << bitOffset;
        }

        for (long packedValue : values) {
            writeLong(output, packedValue);
        }
    }

    private static int bitsNeeded(int maxValue) {
        if (maxValue <= 0) {
            return 1;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value & 0x7F);
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xFF);
        }
    }

    private record RewrittenSection(byte[] bytes, int nonEmptyBlockCount) {
    }

    private record PalettedContainer(byte[] bytes, int bitsPerEntry, int[] palette, long[] data, int entryCount) {
        boolean singleValue() {
            return bitsPerEntry == 0;
        }

        int singleStateId() {
            return palette[0];
        }

        int stateId(int index) {
            if (bitsPerEntry == 0) {
                return palette[0];
            }

            int entriesPerLong = Math.floorDiv(Long.SIZE, bitsPerEntry);
            int dataIndex = index / entriesPerLong;
            int bitOffset = (index % entriesPerLong) * bitsPerEntry;
            int paletteIndex = (int) ((data[dataIndex] >>> bitOffset) & ((1L << bitsPerEntry) - 1L));
            if (palette.length == 0) {
                return paletteIndex;
            }
            return paletteIndex < palette.length ? palette[paletteIndex] : 0;
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

        int remaining() {
            return data.length - index;
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

        byte[] readRemaining() {
            byte[] remaining = Arrays.copyOfRange(data, index, data.length);
            index = data.length;
            return remaining;
        }

        PalettedContainer readPalettedContainer(int maxIndirectPaletteBits, int entryCount) {
            int start = index;
            int bitsPerEntry = readUnsignedByte();
            if (bitsPerEntry == 0) {
                int stateId = readVarInt();
                readDataArrayLength(0);
                return new PalettedContainer(
                        Arrays.copyOfRange(data, start, index),
                        bitsPerEntry,
                        new int[]{stateId},
                        new long[0],
                        entryCount
                );
            }

            int[] palette = new int[0];
            if (usesIndirectPalette(bitsPerEntry, maxIndirectPaletteBits)) {
                int paletteLength = readVarInt();
                palette = new int[paletteLength];
                for (int i = 0; i < paletteLength; i++) {
                    palette[i] = readVarInt();
                }
            }

            long[] dataArray = readFixedLongArray(bitsPerEntry, entryCount);
            return new PalettedContainer(
                    Arrays.copyOfRange(data, start, index),
                    bitsPerEntry,
                    palette,
                    dataArray,
                    entryCount
            );
        }

        byte[] readPalettedContainerBytes(int maxIndirectPaletteBits, int entryCount) {
            int start = index;
            int bitsPerEntry = readUnsignedByte();
            if (bitsPerEntry == 0) {
                readVarInt();
                readDataArrayLength(0);
                return Arrays.copyOfRange(data, start, index);
            }

            if (usesIndirectPalette(bitsPerEntry, maxIndirectPaletteBits)) {
                int paletteLength = readVarInt();
                for (int i = 0; i < paletteLength; i++) {
                    readVarInt();
                }
            }

            skipFixedLongArray(bitsPerEntry, entryCount);
            return Arrays.copyOfRange(data, start, index);
        }

        private boolean usesIndirectPalette(int bitsPerEntry, int maxIndirectPaletteBits) {
            return bitsPerEntry <= maxIndirectPaletteBits;
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

        private long[] readFixedLongArray(int bitsPerEntry, int entryCount) {
            int entriesPerLong = Math.floorDiv(Long.SIZE, bitsPerEntry);
            if (entriesPerLong <= 0) {
                throw new IllegalArgumentException("Invalid bits per entry " + bitsPerEntry);
            }

            int length = Math.floorDiv(entryCount + entriesPerLong - 1, entriesPerLong);
            readDataArrayLength(length);
            long[] values = new long[length];
            for (int i = 0; i < length; i++) {
                values[i] = readLong();
            }
            return values;
        }

        private void skipFixedLongArray(int bitsPerEntry, int entryCount) {
            int entriesPerLong = Math.floorDiv(Long.SIZE, bitsPerEntry);
            if (entriesPerLong <= 0) {
                throw new IllegalArgumentException("Invalid bits per entry " + bitsPerEntry);
            }

            int length = Math.floorDiv(entryCount + entriesPerLong - 1, entriesPerLong);
            readDataArrayLength(length);
            require(length * Long.BYTES);
            index += length * Long.BYTES;
        }

        private long readLong() {
            require(Long.BYTES);
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

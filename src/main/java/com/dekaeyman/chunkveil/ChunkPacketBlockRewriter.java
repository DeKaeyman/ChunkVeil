package com.dekaeyman.chunkveil;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.World;

final class ChunkPacketBlockRewriter {
    private static final int BLOCK_PALETTE_BITS = 4;
    private static final int BLOCKS_PER_SECTION = 4096;

    private final int fakeBlockStateId;
    private final int airBlockStateId;

    ChunkPacketBlockRewriter(int fakeBlockStateId, int airBlockStateId) {
        this.fakeBlockStateId = fakeBlockStateId;
        this.airBlockStateId = airBlockStateId;
    }

    int rewriteHiddenSections(PacketEvent event, World world, int hideBelowY, boolean hideAir) {
        WrappedLevelChunkData.ChunkData chunkData = event.getPacket().getLevelChunkData().read(0);
        byte[] input = chunkData.getBuffer();
        if (input == null || input.length == 0) {
            return 0;
        }

        int minHeight = world.getMinHeight();
        int minSection = Math.floorDiv(minHeight, 16);
        int sectionCount = Math.floorDiv(world.getMaxHeight() - world.getMinHeight(), 16);
        int hiddenRangeHeight = hideBelowY - minHeight;
        if (hiddenRangeHeight <= 0) {
            return 0;
        }

        PacketReader reader = new PacketReader(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int handledHiddenSections = 0;
        int rewrittenSections = 0;

        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            int sectionY = minSection + sectionIndex;
            int sectionMinY = sectionY * 16;
            boolean hiddenSection = sectionMinY < hideBelowY;

            int nonEmptyBlockCount = reader.readUnsignedShort();
            PalettedContainer blockContainer = reader.readPalettedContainer(8, BLOCKS_PER_SECTION);
            byte[] biomeContainer = reader.readPalettedContainerBytes(3, 64);

            if (hiddenSection) {
                handledHiddenSections++;
                RewrittenSection rewrittenSection = rewriteSection(blockContainer, sectionMinY, hideBelowY, hideAir);
                writeShort(output, rewrittenSection.nonEmptyBlockCount());
                output.writeBytes(rewrittenSection.bytes());
                rewrittenSections++;
            } else {
                writeShort(output, nonEmptyBlockCount);
                output.writeBytes(blockContainer.bytes());
            }
            output.writeBytes(biomeContainer);
        }

        if (reader.remaining() > 0) {
            output.writeBytes(reader.readRemaining());
        }

        if (handledHiddenSections == 0) {
            return 0;
        }

        if (rewrittenSections > 0) {
            chunkData.setBuffer(output.toByteArray());
            event.getPacket().getLevelChunkData().write(0, chunkData);
        }
        return handledHiddenSections;
    }

    private RewrittenSection rewriteSection(PalettedContainer source, int sectionMinY, int hideBelowY, boolean hideAir) {
        int[] stateIds = new int[BLOCKS_PER_SECTION];
        int nonEmptyBlockCount = 0;
        for (int blockIndex = 0; blockIndex < BLOCKS_PER_SECTION; blockIndex++) {
            int localY = blockIndex >> 8;
            boolean hiddenBlock = sectionMinY + localY < hideBelowY;
            int stateId = source.stateId(blockIndex);
            if (hiddenBlock && (hideAir || stateId != airBlockStateId)) {
                stateId = fakeBlockStateId;
            }

            stateIds[blockIndex] = stateId;
            if (stateId != airBlockStateId) {
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
            writeSingleValuedPalette(output, stateIds[0]);
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

    private static void writeSingleValuedPalette(ByteArrayOutputStream output, int stateId) {
        output.write(0);
        writeVarInt(output, stateId);
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
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
        private int index;

        private PacketReader(byte[] data) {
            this.data = data;
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
                throw new IllegalArgumentException("Chunk packet buffer ended early");
            }
        }
    }
}

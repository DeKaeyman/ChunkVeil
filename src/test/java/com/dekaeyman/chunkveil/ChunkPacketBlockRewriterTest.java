package com.dekaeyman.chunkveil;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packet regression fixtures for the chunk section rewrite path.
 *
 * <p>The encoder and decoder here are written independently against the chunk
 * data wire format (block count, paletted block container, paletted biome
 * container per section) so the tests verify the production reader/writer
 * against the format, not against itself.
 */
final class ChunkPacketBlockRewriterTest {
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int DIAMOND_ORE = 5734;
    private static final int FAKE = 100;
    private static final int BLOCKS_PER_SECTION = 4096;

    private static ChunkPacketBlockRewriter rewriter(boolean leInt) {
        return new ChunkPacketBlockRewriter(FAKE, AIR, leInt);
    }

    // ---------------------------------------------------------------
    // Basic hiding behaviour
    // ---------------------------------------------------------------

    @Test
    void hidesSolidBlocksInSingleValuePaletteSection() {
        int[] stone = filled(STONE);
        byte[] buffer = buffer(false, section(false, stone), section(false, stone), section(false, stone), section(false, stone));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 64, 16, false);

        assertEquals(1, result.hiddenSections());
        assertEquals(1, result.rewrittenSections());
        List<DecodedSection> sections = decode(result.buffer(), 4, false);
        assertAllStates(sections.get(0), FAKE);
        assertEquals(BLOCKS_PER_SECTION, sections.get(0).nonEmptyCount);
        assertAllStates(sections.get(1), STONE);
        assertAllStates(sections.get(3), STONE);
    }

    @Test
    void keepsAirVisibleWhenHideAirDisabled() {
        int[] states = filled(STONE);
        // Carve a cave pocket into the lower half of the section.
        for (int i = 0; i < 512; i++) {
            states[i] = AIR;
        }
        states[600] = DIAMOND_ORE;
        byte[] buffer = buffer(false, section(false, states), section(false, filled(STONE)));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 32, 16, false);

        DecodedSection hidden = decode(result.buffer(), 2, false).get(0);
        for (int i = 0; i < 512; i++) {
            assertEquals(AIR, hidden.states[i], "cave air must stay air with hide-air disabled, index " + i);
        }
        assertEquals(FAKE, hidden.states[600], "ore must be replaced by the fake block");
        assertEquals(FAKE, hidden.states[2000], "stone must be replaced by the fake block");
        assertEquals(BLOCKS_PER_SECTION - 512, hidden.nonEmptyCount);
    }

    @Test
    void hidesAirWhenHideAirEnabled() {
        int[] states = filled(STONE);
        for (int i = 0; i < 512; i++) {
            states[i] = AIR;
        }
        byte[] buffer = buffer(false, section(false, states));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 16, true);

        DecodedSection hidden = decode(result.buffer(), 1, false).get(0);
        assertAllStates(hidden, FAKE);
        assertEquals(BLOCKS_PER_SECTION, hidden.nonEmptyCount);
    }

    @Test
    void partialSectionCrossingCutoffOnlyHidesBelow() {
        int[] states = filled(STONE);
        byte[] buffer = buffer(false, section(false, states));

        // Cutoff at y=8 inside the single section [0, 16).
        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 8, false);

        assertEquals(1, result.hiddenSections());
        DecodedSection section = decode(result.buffer(), 1, false).get(0);
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            int localY = index >> 8;
            int expected = localY < 8 ? FAKE : STONE;
            assertEquals(expected, section.states[index], "block index " + index + " at local y " + localY);
        }
    }

    @Test
    void negativeWorldHeightHidesSectionsBelowCutoff() {
        int[] stone = filled(STONE);
        byte[][] sections = new byte[8][];
        for (int i = 0; i < 8; i++) {
            sections[i] = section(false, stone);
        }
        byte[] buffer = buffer(false, sections);

        // World from -64 to 64: sections at y -64..-1 are hidden below cutoff 0.
        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, -64, 64, 0, false);

        assertEquals(4, result.hiddenSections());
        assertEquals(4, result.rewrittenSections());
        List<DecodedSection> decoded = decode(result.buffer(), 8, false);
        for (int i = 0; i < 4; i++) {
            assertAllStates(decoded.get(i), FAKE);
        }
        for (int i = 4; i < 8; i++) {
            assertAllStates(decoded.get(i), STONE);
        }
    }

    @Test
    void cutoffAtOrBelowWorldBottomLeavesBufferUntouched() {
        byte[] buffer = buffer(false, section(false, filled(STONE)));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 0, false);

        assertEquals(0, result.hiddenSections());
        assertEquals(0, result.rewrittenSections());
        assertSame(buffer, result.buffer());
    }

    // ---------------------------------------------------------------
    // Palette variants
    // ---------------------------------------------------------------

    @Test
    void rewritesIndirectPaletteSection() {
        int[] states = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            states[i] = switch (i % 3) {
                case 0 -> STONE;
                case 1 -> AIR;
                default -> DIAMOND_ORE;
            };
        }
        byte[] buffer = buffer(false, section(false, states));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 16, false);

        DecodedSection section = decode(result.buffer(), 1, false).get(0);
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int expected = states[i] == AIR ? AIR : FAKE;
            assertEquals(expected, section.states[i], "index " + i);
        }
    }

    @Test
    void rewritesDirectPaletteSection() {
        // More than 256 distinct states forces the direct (paletteless) format.
        int[] states = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            states[i] = i + 1;
        }
        byte[] buffer = buffer(false, section(false, states));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 16, false);

        DecodedSection section = decode(result.buffer(), 1, false).get(0);
        assertAllStates(section, FAKE);
        assertEquals(BLOCKS_PER_SECTION, section.nonEmptyCount);
    }

    @Test
    void acceptsLowBitIndirectPaletteInput() {
        // Vanilla favours >=4 bits for blocks, but the format allows fewer;
        // the reader must not misparse such sections.
        int[] states = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            states[i] = i % 2 == 0 ? STONE : AIR;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeCount(out, BLOCKS_PER_SECTION / 2, false);
        writeIndirectBlockContainer(out, states, 1);
        out.writeBytes(singleValueBiomeContainer());
        byte[] buffer = out.toByteArray();

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 16, false);

        DecodedSection section = decode(result.buffer(), 1, false).get(0);
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int expected = i % 2 == 0 ? FAKE : AIR;
            assertEquals(expected, section.states[i], "index " + i);
        }
    }

    // ---------------------------------------------------------------
    // 26.x little-endian block count variant
    // ---------------------------------------------------------------

    @Test
    void rewritesLittleEndianBlockCountFormat() {
        int[] states = filled(STONE);
        byte[] buffer = buffer(true, section(true, states), section(true, states));

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(true).rewriteBuffer(buffer, 0, 32, 16, false);

        assertEquals(1, result.hiddenSections());
        List<DecodedSection> sections = decode(result.buffer(), 2, true);
        assertAllStates(sections.get(0), FAKE);
        assertEquals(BLOCKS_PER_SECTION, sections.get(0).nonEmptyCount);
        assertAllStates(sections.get(1), STONE);
    }

    // ---------------------------------------------------------------
    // Pass-through integrity
    // ---------------------------------------------------------------

    @Test
    void visibleSectionsAndBiomesPassThroughByteIdentical() {
        int[] mixed = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            mixed[i] = i % 7 == 0 ? DIAMOND_ORE : STONE;
        }
        byte[] hiddenSection = section(false, filled(STONE));
        byte[] visibleSection = sectionWithBiomes(false, mixed, multiBiomeContainer());
        byte[] buffer = concat(hiddenSection, visibleSection);

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 32, 16, false);

        // The visible section, including its indirect biome container, must be
        // byte-identical at the tail of the output.
        byte[] tail = Arrays.copyOfRange(result.buffer(), result.buffer().length - visibleSection.length, result.buffer().length);
        assertArrayEquals(visibleSection, tail);
    }

    @Test
    void trailingDataAfterSectionsIsPreserved() {
        byte[] trailing = {0x0A, (byte) 0x92, 0x3C, 0x00, 0x7F};
        byte[] buffer = concat(buffer(false, section(false, filled(STONE))), trailing);

        ChunkPacketBlockRewriter.RewriteResult result =
                rewriter(false).rewriteBuffer(buffer, 0, 16, 16, false);

        byte[] tail = Arrays.copyOfRange(result.buffer(), result.buffer().length - trailing.length, result.buffer().length);
        assertArrayEquals(trailing, tail);
    }

    @Test
    void rewriteIsIdempotent() {
        int[] states = filled(STONE);
        for (int i = 0; i < 256; i++) {
            states[i] = AIR;
        }
        byte[] buffer = buffer(false, section(false, states));

        ChunkPacketBlockRewriter rewriter = rewriter(false);
        byte[] once = rewriter.rewriteBuffer(buffer, 0, 16, 16, false).buffer();
        byte[] twice = rewriter.rewriteBuffer(once, 0, 16, 16, false).buffer();

        assertArrayEquals(once, twice);
    }

    // ---------------------------------------------------------------
    // Malformed input must throw (the listener cancels the packet and
    // fails closed when this happens; it must never send a bad buffer)
    // ---------------------------------------------------------------

    @Test
    void truncatedBufferThrows() {
        int[] states = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            states[i] = i % 2 == 0 ? STONE : DIAMOND_ORE;
        }
        byte[] buffer = buffer(false, section(false, states), section(false, states));
        byte[] truncated = Arrays.copyOf(buffer, buffer.length - 100);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> rewriter(false).rewriteBuffer(truncated, 0, 32, 16, false));
        assertTrue(exception.getMessage().contains("ended early"));
    }

    @Test
    void emptyBufferThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> rewriter(false).rewriteBuffer(new byte[0], 0, 16, 16, false));
    }

    @Test
    void invalidBitsPerEntryThrows() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeCount(out, 1, false);
        out.write(65); // bits per entry larger than a long can hold
        for (int i = 0; i < 600; i++) {
            out.write(0);
        }

        assertThrows(IllegalArgumentException.class,
                () -> rewriter(false).rewriteBuffer(out.toByteArray(), 0, 16, 16, false));
    }

    @Test
    void oversizedVarIntThrows() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeCount(out, 1, false);
        out.write(0); // single-value palette marker
        for (int i = 0; i < 6; i++) {
            out.write(0x80); // never-terminating VarInt
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> rewriter(false).rewriteBuffer(out.toByteArray(), 0, 16, 16, false));
        assertTrue(exception.getMessage().contains("VarInt"));
    }

    // ---------------------------------------------------------------
    // Fixture encoder (independent implementation of the wire format)
    // ---------------------------------------------------------------

    private static int[] filled(int stateId) {
        int[] states = new int[BLOCKS_PER_SECTION];
        Arrays.fill(states, stateId);
        return states;
    }

    private static byte[] buffer(boolean leInt, byte[]... sections) {
        return concat(sections);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] section(boolean leInt, int[] states) {
        return sectionWithBiomes(leInt, states, singleValueBiomeContainer());
    }

    private static byte[] sectionWithBiomes(boolean leInt, int[] states, byte[] biomeContainer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int nonEmpty = 0;
        for (int state : states) {
            if (state != AIR) {
                nonEmpty++;
            }
        }
        writeCount(out, nonEmpty, leInt);
        writeBlockContainer(out, states);
        out.writeBytes(biomeContainer);
        return out.toByteArray();
    }

    private static void writeCount(ByteArrayOutputStream out, int count, boolean leInt) {
        if (leInt) {
            out.write(count & 0xFF);
            out.write((count >>> 8) & 0xFF);
            out.write((count >>> 16) & 0xFF);
            out.write((count >>> 24) & 0xFF);
        } else {
            out.write((count >>> 8) & 0xFF);
            out.write(count & 0xFF);
        }
    }

    private static void writeBlockContainer(ByteArrayOutputStream out, int[] states) {
        List<Integer> palette = distinct(states);
        if (palette.size() == 1) {
            out.write(0);
            writeVarInt(out, palette.get(0));
            return;
        }
        if (palette.size() <= 256) {
            int bits = Math.max(4, bitsNeeded(palette.size() - 1));
            writeIndirectBlockContainer(out, states, bits);
            return;
        }
        int maxState = 0;
        for (int state : states) {
            maxState = Math.max(maxState, state);
        }
        int bits = Math.max(9, bitsNeeded(maxState));
        out.write(bits);
        writePacked(out, states, bits, null);
    }

    private static void writeIndirectBlockContainer(ByteArrayOutputStream out, int[] states, int bits) {
        List<Integer> palette = distinct(states);
        out.write(bits);
        writeVarInt(out, palette.size());
        Map<Integer, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            writeVarInt(out, palette.get(i));
            indexOf.put(palette.get(i), i);
        }
        writePacked(out, states, bits, indexOf);
    }

    private static byte[] singleValueBiomeContainer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        writeVarInt(out, 0);
        return out.toByteArray();
    }

    private static byte[] multiBiomeContainer() {
        // Indirect biome palette: 2 bits, 4 biomes, 64 entries.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        writeVarInt(out, 4);
        for (int biome = 0; biome < 4; biome++) {
            writeVarInt(out, biome + 10);
        }
        int[] entries = new int[64];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = i % 4;
        }
        writePackedEntries(out, entries, 2);
        return out.toByteArray();
    }

    private static List<Integer> distinct(int[] states) {
        List<Integer> palette = new ArrayList<>();
        for (int state : states) {
            if (!palette.contains(state)) {
                palette.add(state);
            }
        }
        return palette;
    }

    private static void writePacked(ByteArrayOutputStream out, int[] states, int bits, Map<Integer, Integer> paletteIndex) {
        int[] entries = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            entries[i] = paletteIndex == null ? states[i] : paletteIndex.get(states[i]);
        }
        writePackedEntries(out, entries, bits);
    }

    private static void writePackedEntries(ByteArrayOutputStream out, int[] entries, int bits) {
        int entriesPerLong = 64 / bits;
        int longCount = (entries.length + entriesPerLong - 1) / entriesPerLong;
        long[] packed = new long[longCount];
        for (int i = 0; i < entries.length; i++) {
            int longIndex = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bits;
            packed[longIndex] |= ((long) entries[i] & ((1L << bits) - 1)) << bitOffset;
        }
        for (long value : packed) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (value >>> shift) & 0xFF);
            }
        }
    }

    private static int bitsNeeded(int maxValue) {
        if (maxValue <= 0) {
            return 1;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    // ---------------------------------------------------------------
    // Fixture decoder
    // ---------------------------------------------------------------

    private static final class DecodedSection {
        int nonEmptyCount;
        int[] states = new int[BLOCKS_PER_SECTION];
    }

    private static void assertAllStates(DecodedSection section, int expected) {
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            assertEquals(expected, section.states[i], "block index " + i);
        }
    }

    private static List<DecodedSection> decode(byte[] buffer, int sectionCount, boolean leInt) {
        Cursor cursor = new Cursor(buffer);
        List<DecodedSection> sections = new ArrayList<>();
        for (int s = 0; s < sectionCount; s++) {
            DecodedSection section = new DecodedSection();
            section.nonEmptyCount = leInt ? cursor.readLeInt() : cursor.readBeShort();
            decodeContainer(cursor, section.states, 8);
            decodeContainer(cursor, new int[64], 3);
            sections.add(section);
        }
        return sections;
    }

    private static void decodeContainer(Cursor cursor, int[] target, int maxIndirectBits) {
        int bits = cursor.readByte();
        if (bits == 0) {
            Arrays.fill(target, cursor.readVarInt());
            return;
        }

        int[] palette = null;
        if (bits <= maxIndirectBits) {
            int size = cursor.readVarInt();
            palette = new int[size];
            for (int i = 0; i < size; i++) {
                palette[i] = cursor.readVarInt();
            }
        }

        int entriesPerLong = 64 / bits;
        int longCount = (target.length + entriesPerLong - 1) / entriesPerLong;
        long mask = (1L << bits) - 1;
        for (int longIndex = 0; longIndex < longCount; longIndex++) {
            long value = cursor.readLong();
            for (int entry = 0; entry < entriesPerLong; entry++) {
                int index = longIndex * entriesPerLong + entry;
                if (index >= target.length) {
                    break;
                }
                int raw = (int) ((value >>> (entry * bits)) & mask);
                target[index] = palette == null ? raw : palette[raw];
            }
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int index;

        Cursor(byte[] data) {
            this.data = data;
        }

        int readByte() {
            return data[index++] & 0xFF;
        }

        int readBeShort() {
            return (readByte() << 8) | readByte();
        }

        int readLeInt() {
            return readByte() | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
        }

        long readLong() {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (data[index++] & 0xFFL);
            }
            return value;
        }

        int readVarInt() {
            int value = 0;
            int position = 0;
            byte current;
            do {
                current = data[index++];
                value |= (current & 0x7F) << position;
                position += 7;
            } while ((current & 0x80) != 0);
            return value;
        }
    }
}

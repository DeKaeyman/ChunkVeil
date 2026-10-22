package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NmsBlockStateIdsTest {
    @Test
    void ceilLog2MatchesMinecraft() {
        assertEquals(0, NmsBlockStateIds.ceilLog2(1));
        assertEquals(1, NmsBlockStateIds.ceilLog2(2));
        assertEquals(2, NmsBlockStateIds.ceilLog2(3));
        assertEquals(15, NmsBlockStateIds.ceilLog2(29_873)); // ~1.21 block state count
        assertEquals(15, NmsBlockStateIds.ceilLog2(32_768));
        assertEquals(16, NmsBlockStateIds.ceilLog2(32_769));
    }
}

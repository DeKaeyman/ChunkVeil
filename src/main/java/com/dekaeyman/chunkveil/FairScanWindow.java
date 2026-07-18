package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.List;

/** Selects a bounded, rotating window so a stable candidate list cannot starve its tail. */
final class FairScanWindow {
    private FairScanWindow() {
    }

    static <T> Selection<T> select(List<T> candidates, int cursor, int limit) {
        if (candidates.isEmpty() || limit <= 0) {
            return new Selection<>(List.of(), 0, candidates.size());
        }
        int size = candidates.size();
        int start = Math.floorMod(cursor, size);
        int count = Math.min(size, limit);
        List<T> selected = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            selected.add(candidates.get((start + offset) % size));
        }
        return new Selection<>(List.copyOf(selected), (start + count) % size, size - count);
    }

    record Selection<T>(List<T> items, int nextCursor, int deferred) {
    }
}

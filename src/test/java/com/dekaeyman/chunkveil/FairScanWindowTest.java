package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FairScanWindowTest {
    @Test void repeatedCappedScansVisitEveryCandidate() {
        List<Integer> candidates = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        Set<Integer> visited = new LinkedHashSet<>();
        int cursor = 0;
        for (int scan = 0; scan < 4; scan++) {
            FairScanWindow.Selection<Integer> selection = FairScanWindow.select(candidates, cursor, 3);
            visited.addAll(selection.items());
            cursor = selection.nextCursor();
        }
        assertEquals(new LinkedHashSet<>(candidates), visited);
    }

    @Test void wrapsWithoutRepeatingWithinAWindow() {
        FairScanWindow.Selection<Integer> selection = FairScanWindow.select(List.of(0, 1, 2, 3, 4), 4, 3);
        assertEquals(List.of(4, 0, 1), selection.items());
        assertEquals(2, selection.nextCursor());
        assertEquals(2, selection.deferred());
    }

    @Test void changingCandidateCountKeepsCursorSafe() {
        assertEquals(List.of("b", "a"), FairScanWindow.select(List.of("a", "b"), 5, 2).items());
        assertEquals(List.of(), FairScanWindow.select(new ArrayList<>(), 5, 2).items());
    }
}

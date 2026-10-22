package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerVeilStateQueueTest {
    private static final ChunkKey UNOWNED = ChunkKey.of("world", 40, 40);
    private static final ChunkKey NEXT = ChunkKey.of("world", 1, 1);
    private static final ChunkKey LATER = ChunkKey.of("world", 2, 2);

    private static PlayerVeilState hiddenState(ChunkKey... keys) {
        PlayerVeilState state = new PlayerVeilState();
        for (ChunkKey key : keys) {
            state.markApplied(key, VeilMode.HIDE);
        }
        return state;
    }

    @Test
    void deferredPriorityUpdateNoLongerBlocksTheQueueHead() {
        // An unowned chunk (Folia) used to be put back at the front and the
        // tick ended, so the updates behind it were never processed.
        PlayerVeilState state = hiddenState(UNOWNED, NEXT, LATER);
        state.enqueuePriority(LATER, VeilMode.REVEAL);
        state.enqueuePriority(NEXT, VeilMode.REVEAL);
        state.enqueuePriority(UNOWNED, VeilMode.REVEAL);

        VeilChunkUpdate head = state.poll();
        assertEquals(UNOWNED, head.chunkKey());
        state.requeueDeferred(head);

        assertEquals(NEXT, state.poll().chunkKey());
        assertEquals(LATER, state.poll().chunkKey());
        VeilChunkUpdate retried = state.poll();
        assertEquals(UNOWNED, retried.chunkKey(), "the deferred update is retried later");
        assertTrue(retried.priority(), "and keeps its priority flag");
        assertNull(state.poll());
    }

    @Test
    void deferredUpdateForAForgottenChunkIsDropped() {
        // The chunk unloaded (forgetChunk, another region thread) while the
        // update was deferred: re-adding it must not resurrect it.
        PlayerVeilState state = hiddenState(UNOWNED);
        state.enqueuePriority(UNOWNED, VeilMode.REVEAL);
        VeilChunkUpdate update = state.poll();
        state.forgetChunk(UNOWNED);

        state.requeueDeferred(update);

        assertFalse(state.isChunkQueued(UNOWNED));
        assertNull(state.poll());
    }

    @Test
    void onlyVisibleChunksStillHiddenAwaitReveal() {
        ChunkKey revealed = ChunkKey.of("world", 10, 10);
        ChunkKey hidden = ChunkKey.of("world", 11, 10);
        ChunkKey queued = ChunkKey.of("world", 12, 10);
        ChunkKey notOurs = ChunkKey.of("world", 13, 10); // never rewritten
        ChunkKey otherWorld = ChunkKey.of("world_nether", 11, 10);
        PlayerVeilState state = hiddenState(hidden, queued, otherWorld);
        state.markApplied(revealed, VeilMode.REVEAL);
        state.enqueuePriority(queued, VeilMode.REVEAL);
        state.rememberVisibleChunks(java.util.Set.of(revealed, hidden, queued, notOurs, otherWorld));

        assertEquals(java.util.List.of(hidden), state.visibleChunksAwaitingReveal("world"));
    }
}

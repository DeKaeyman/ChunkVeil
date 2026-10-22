package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerVeilStateChunkTest {
    private static final ChunkKey CHUNK = ChunkKey.of("world", 3, -7);

    @Test void revealedChunkLeavingViewIsRecordedHiddenWithoutQueueingAnything() {
        // No HIDE update may be queued: processing one used to send a full
        // multi-block mask (~13 ms per chunk on the main thread).
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(CHUNK, VeilMode.REVEAL);

        state.markChunkHiddenWithoutSending(CHUNK);

        assertEquals(VeilMode.HIDE, state.appliedMode(CHUNK));
        assertFalse(state.isChunkQueued(CHUNK));
        assertNull(state.poll());
    }

    @Test void pendingRevealIsDroppedWhenTheChunkLeavesView() {
        // A reveal still queued for a chunk that is no longer visible must not
        // be sent later.
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(CHUNK, VeilMode.HIDE);
        state.enqueuePriority(CHUNK, VeilMode.REVEAL);
        assertTrue(state.isChunkQueued(CHUNK));

        state.markChunkHiddenWithoutSending(CHUNK);

        assertFalse(state.isChunkQueued(CHUNK));
        assertNull(state.poll());
    }

    @Test void reEnteringViewQueuesARealReveal() {
        // The client may hold fake block updates sent while the chunk was out
        // of view; the reveal resend must happen to clean them up.
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(CHUNK, VeilMode.REVEAL);
        state.markChunkHiddenWithoutSending(CHUNK);

        state.enqueuePriority(CHUNK, VeilMode.REVEAL);

        VeilChunkUpdate update = state.poll();
        assertEquals(CHUNK, update.chunkKey());
        assertEquals(VeilMode.REVEAL, update.mode());
    }

    @Test void chunkNeverHiddenBeforeIsRecordedHiddenToo() {
        // Sent while visible, then left view: block updates after that are
        // rewritten fake, so a later reveal must still resend the chunk.
        PlayerVeilState state = new PlayerVeilState();
        assertNull(state.appliedMode(CHUNK));

        state.markChunkHiddenWithoutSending(CHUNK);

        assertEquals(VeilMode.HIDE, state.appliedMode(CHUNK));
        state.enqueuePriority(CHUNK, VeilMode.REVEAL);
        assertTrue(state.isChunkQueued(CHUNK));
    }

    @Test void otherQueuedChunksAreUnaffected() {
        ChunkKey other = ChunkKey.of("world", 4, -7);
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(CHUNK, VeilMode.HIDE);
        state.markApplied(other, VeilMode.HIDE);
        state.enqueuePriority(CHUNK, VeilMode.REVEAL);
        state.enqueuePriority(other, VeilMode.REVEAL);

        state.markChunkHiddenWithoutSending(CHUNK);

        VeilChunkUpdate update = state.poll();
        assertEquals(other, update.chunkKey());
        assertNull(state.poll());
    }
}

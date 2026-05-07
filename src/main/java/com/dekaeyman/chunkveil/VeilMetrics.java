package com.dekaeyman.chunkveil;

import java.util.concurrent.atomic.LongAdder;

final class VeilMetrics {
    private final LongAdder chunkPackets = new LongAdder();
    private final LongAdder hiddenChunkPackets = new LongAdder();
    private final LongAdder rewrittenChunkPackets = new LongAdder();
    private final LongAdder unrewrittenHiddenChunkPackets = new LongAdder();
    private final LongAdder blockChangesRewritten = new LongAdder();
    private final LongAdder multiBlockChangesRewritten = new LongAdder();
    private final LongAdder blockEntityUpdatesCancelled = new LongAdder();
    private final LongAdder entitySpawnsCancelled = new LongAdder();
    private final LongAdder entityPacketsCancelled = new LongAdder();
    private final LongAdder chunkUpdatePacketsSent = new LongAdder();

    void countChunkPacket(boolean hidden, boolean rewritten) {
        chunkPackets.increment();
        if (hidden) {
            hiddenChunkPackets.increment();
        }
        if (rewritten) {
            rewrittenChunkPackets.increment();
        } else if (hidden) {
            unrewrittenHiddenChunkPackets.increment();
        }
    }

    void countBlockChangeRewritten() {
        blockChangesRewritten.increment();
    }

    void countMultiBlockChangeRewritten() {
        multiBlockChangesRewritten.increment();
    }

    void countBlockEntityUpdateCancelled() {
        blockEntityUpdatesCancelled.increment();
    }

    void countEntitySpawnCancelled() {
        entitySpawnsCancelled.increment();
    }

    void countEntityPacketCancelled() {
        entityPacketsCancelled.increment();
    }

    void countChunkUpdatePacketSent() {
        chunkUpdatePacketsSent.increment();
    }

    long chunkPackets() {
        return chunkPackets.sum();
    }

    long hiddenChunkPackets() {
        return hiddenChunkPackets.sum();
    }

    long rewrittenChunkPackets() {
        return rewrittenChunkPackets.sum();
    }

    long unrewrittenHiddenChunkPackets() {
        return unrewrittenHiddenChunkPackets.sum();
    }

    long blockChangesRewritten() {
        return blockChangesRewritten.sum();
    }

    long multiBlockChangesRewritten() {
        return multiBlockChangesRewritten.sum();
    }

    long blockEntityUpdatesCancelled() {
        return blockEntityUpdatesCancelled.sum();
    }

    long entitySpawnsCancelled() {
        return entitySpawnsCancelled.sum();
    }

    long entityPacketsCancelled() {
        return entityPacketsCancelled.sum();
    }

    long chunkUpdatePacketsSent() {
        return chunkUpdatePacketsSent.sum();
    }
}

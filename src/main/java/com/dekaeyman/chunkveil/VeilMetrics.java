package com.dekaeyman.chunkveil;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Timing revealScanTiming = new Timing();
    private final Timing chunkMaskTiming = new Timing();
    private final Timing entityScanTiming = new Timing();
    private final Timing queueProcessingTiming = new Timing();

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

    void recordRevealScanNanos(long nanos) {
        revealScanTiming.record(nanos);
    }

    void recordChunkMaskNanos(long nanos) {
        chunkMaskTiming.record(nanos);
    }

    void recordEntityScanNanos(long nanos) {
        entityScanTiming.record(nanos);
    }

    void recordQueueProcessingNanos(long nanos) {
        queueProcessingTiming.record(nanos);
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

    double revealScanAverageMillis() {
        return revealScanTiming.averageMillis();
    }

    double revealScanMaxMillis() {
        return revealScanTiming.maxMillis();
    }

    long revealScanSamples() {
        return revealScanTiming.samples();
    }

    double chunkMaskAverageMillis() {
        return chunkMaskTiming.averageMillis();
    }

    double chunkMaskMaxMillis() {
        return chunkMaskTiming.maxMillis();
    }

    long chunkMaskSamples() {
        return chunkMaskTiming.samples();
    }

    double entityScanAverageMillis() {
        return entityScanTiming.averageMillis();
    }

    double entityScanMaxMillis() {
        return entityScanTiming.maxMillis();
    }

    long entityScanSamples() {
        return entityScanTiming.samples();
    }

    double queueProcessingAverageMillis() {
        return queueProcessingTiming.averageMillis();
    }

    double queueProcessingMaxMillis() {
        return queueProcessingTiming.maxMillis();
    }

    long queueProcessingSamples() {
        return queueProcessingTiming.samples();
    }

    private static final class Timing {
        private final LongAdder samples = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long nanos) {
            if (nanos < 0L) {
                return;
            }
            samples.increment();
            totalNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        long samples() {
            return samples.sum();
        }

        double averageMillis() {
            long sampleCount = samples();
            if (sampleCount == 0L) {
                return 0.0D;
            }
            return totalNanos.sum() / 1_000_000.0D / sampleCount;
        }

        double maxMillis() {
            return maxNanos.get() / 1_000_000.0D;
        }
    }
}

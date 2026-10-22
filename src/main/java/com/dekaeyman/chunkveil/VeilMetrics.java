package com.dekaeyman.chunkveil;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLongArray;

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
    private final LongAdder explosionPacketsCancelled = new LongAdder();
    private final LongAdder worldEventPacketsCancelled = new LongAdder();
    private final LongAdder blockBreakAnimationPacketsCancelled = new LongAdder();
    private final LongAdder soundPacketsCancelled = new LongAdder();
    private final LongAdder particlePacketsCancelled = new LongAdder();
    private final LongAdder vibrationPacketsCancelled = new LongAdder();
    private final LongAdder lightPacketsSanitized = new LongAdder();
    private final LongAdder securityPacketsCancelled = new LongAdder();
    private final LongAdder chunkUpdatePacketsSent = new LongAdder();
    private final LongAdder entityScanCandidates = new LongAdder();
    private final LongAdder entityScanCandidatesDeferred = new LongAdder();
    private final Timing revealScanTiming = new Timing();
    private final Timing packetChunkRewriteTiming = new Timing();
    private final Timing entityScanTiming = new Timing();
    private final Timing queueProcessingTiming = new Timing();
    // Worker ticks of tracked players; queue timing is only sampled on the
    // ticks among these that had queued work.
    private final LongAdder trackedWorkerTicks = new LongAdder();
    // Time spent in the packet listener, per protected path (every packet of
    // the subscribed types, including the fast "nothing to hide" exits).
    private final Timing[] packetHandlingTiming = newTimings(PacketSecurityState.ProtectedPath.values().length);

    private static Timing[] newTimings(int count) {
        Timing[] timings = new Timing[count];
        for (int i = 0; i < count; i++) {
            timings[i] = new Timing();
        }
        return timings;
    }

    void recordPacketHandlingNanos(PacketSecurityState.ProtectedPath path, long nanos) {
        packetHandlingTiming[path.ordinal()].record(nanos);
    }

    long packetHandlingSamples(PacketSecurityState.ProtectedPath path) {
        return packetHandlingTiming[path.ordinal()].samples();
    }

    long packetHandlingTotalNanos(PacketSecurityState.ProtectedPath path) {
        return packetHandlingTiming[path.ordinal()].totalNanos();
    }

    long revealScanTotalNanos() {
        return revealScanTiming.totalNanos();
    }

    long packetChunkRewriteTotalNanos() {
        return packetChunkRewriteTiming.totalNanos();
    }

    long entityScanTotalNanos() {
        return entityScanTiming.totalNanos();
    }

    long queueProcessingTotalNanos() {
        return queueProcessingTiming.totalNanos();
    }

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

    void countExplosionPacketCancelled() {
        explosionPacketsCancelled.increment();
    }

    void countWorldEventPacketCancelled() {
        worldEventPacketsCancelled.increment();
    }

    void countBlockBreakAnimationPacketCancelled() {
        blockBreakAnimationPacketsCancelled.increment();
    }

    void countSoundPacketCancelled() {
        soundPacketsCancelled.increment();
    }

    void countParticlePacketCancelled() {
        particlePacketsCancelled.increment();
    }

    void countVibrationPacketCancelled() {
        vibrationPacketsCancelled.increment();
    }

    void countLightPacketSanitized() {
        lightPacketsSanitized.increment();
    }

    void countSecurityPacketCancelled() {
        securityPacketsCancelled.increment();
    }

    void countChunkUpdatePacketSent() {
        chunkUpdatePacketsSent.increment();
    }

    void recordRevealScanNanos(long nanos) {
        revealScanTiming.record(nanos);
    }

    void recordPacketChunkRewriteNanos(long nanos) {
        packetChunkRewriteTiming.record(nanos);
    }

    void recordEntityScanNanos(long nanos) {
        entityScanTiming.record(nanos);
    }

    void recordEntityScanCandidates(int inspected, int deferred) {
        entityScanCandidates.add(Math.max(0, inspected));
        entityScanCandidatesDeferred.add(Math.max(0, deferred));
    }

    void recordQueueProcessingNanos(long nanos) {
        queueProcessingTiming.record(nanos);
    }

    void countTrackedWorkerTick() {
        trackedWorkerTicks.increment();
    }

    long trackedWorkerTicks() {
        return trackedWorkerTicks.sum();
    }

    /** Share of tracked worker ticks that had queued chunk work (1.0 before any tick). */
    double queueBusyFraction() {
        long ticks = trackedWorkerTicks();
        if (ticks == 0L) {
            return 1.0D;
        }
        return Math.min(1.0D, queueProcessingTiming.samples() / (double) ticks);
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

    long explosionPacketsCancelled() {
        return explosionPacketsCancelled.sum();
    }

    long worldEventPacketsCancelled() {
        return worldEventPacketsCancelled.sum();
    }

    long blockBreakAnimationPacketsCancelled() {
        return blockBreakAnimationPacketsCancelled.sum();
    }

    long soundPacketsCancelled() {
        return soundPacketsCancelled.sum();
    }

    long particlePacketsCancelled() {
        return particlePacketsCancelled.sum();
    }

    long vibrationPacketsCancelled() {
        return vibrationPacketsCancelled.sum();
    }

    long lightPacketsSanitized() {
        return lightPacketsSanitized.sum();
    }

    long securityPacketsCancelled() {
        return securityPacketsCancelled.sum();
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

    double packetChunkRewriteAverageMillis() {
        return packetChunkRewriteTiming.averageMillis();
    }

    double packetChunkRewriteMaxMillis() {
        return packetChunkRewriteTiming.maxMillis();
    }

    long packetChunkRewriteSamples() {
        return packetChunkRewriteTiming.samples();
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

    long entityScanCandidates() {
        return entityScanCandidates.sum();
    }

    long entityScanCandidatesDeferred() {
        return entityScanCandidatesDeferred.sum();
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

    /**
     * Average over all samples since start, max over a rolling window. An
     * all-time max was pinned forever by the first GC pause (field report:
     * every max ~1.38 s), which made it useless for spotting current spikes.
     */
    static final class Timing {
        // Two alternating windows: the max covers the last 2.5-5 minutes.
        static final long WINDOW_MILLIS = 150_000L;

        private final LongAdder samples = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLongArray maxNanosBySlot = new AtomicLongArray(2);
        private final AtomicLongArray windowBySlot = new AtomicLongArray(new long[]{-1L, -1L});

        void record(long nanos) {
            record(nanos, System.currentTimeMillis());
        }

        void record(long nanos, long nowMillis) {
            if (nanos < 0L) {
                return;
            }
            samples.increment();
            totalNanos.add(nanos);
            long window = nowMillis / WINDOW_MILLIS;
            int slot = (int) (window & 1L);
            long slotWindow = windowBySlot.get(slot);
            if (slotWindow != window && windowBySlot.compareAndSet(slot, slotWindow, window)) {
                // First sample of a new window takes over the stale slot. A
                // concurrent sample may still land in the old value; that only
                // loses or keeps one sample's max for one window.
                maxNanosBySlot.set(slot, nanos);
                return;
            }
            maxNanosBySlot.accumulateAndGet(slot, nanos, Math::max);
        }

        long samples() {
            return samples.sum();
        }

        long totalNanos() {
            return totalNanos.sum();
        }

        double averageMillis() {
            long sampleCount = samples();
            if (sampleCount == 0L) {
                return 0.0D;
            }
            return totalNanos.sum() / 1_000_000.0D / sampleCount;
        }

        double maxMillis() {
            return maxMillis(System.currentTimeMillis());
        }

        /** Largest sample in the current and the previous window. */
        double maxMillis(long nowMillis) {
            long window = nowMillis / WINDOW_MILLIS;
            long max = 0L;
            for (int slot = 0; slot < 2; slot++) {
                long slotWindow = windowBySlot.get(slot);
                if (slotWindow == window || slotWindow == window - 1) {
                    max = Math.max(max, maxNanosBySlot.get(slot));
                }
            }
            return max / 1_000_000.0D;
        }
    }
}

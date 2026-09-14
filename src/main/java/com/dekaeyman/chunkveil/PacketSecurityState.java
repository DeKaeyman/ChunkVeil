package com.dekaeyman.chunkveil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

/** Immediate, thread-safe confidentiality state shared by all packet handlers. */
final class PacketSecurityState {
    enum ProtectedPath {
        CHUNK,
        BLOCK_CHANGE,
        MULTI_BLOCK_CHANGE,
        BLOCK_ENTITY,
        ENTITY_SPAWN,
        ENTITY_FOLLOW_UP,
        EXPLOSION,
        WORLD_EVENT,
        BLOCK_CRACK,
        POSITIONAL_SOUND,
        PARTICLE,
        VIBRATION,
        LIGHT
    }

    record Failure(ProtectedPath path, String reason) {
    }

    private final AtomicReference<Failure> failure = new AtomicReference<>();
    // Timestamps of recent tolerated failures; guarded by its own monitor.
    // Failures are rare by definition, so contention is negligible.
    private final Deque<Long> recentFailureMillis = new ArrayDeque<>();

    /**
     * Records one runtime protection failure and reports whether the
     * tolerance threshold is now crossed. A threshold of 1 preserves the
     * strict trip-on-first-failure behavior; larger thresholds tolerate rare
     * one-off failures (each failing packet is still cancelled by the caller)
     * while repeated failures within the window still quarantine.
     */
    boolean recordFailure(long nowMillis, int maxFailures, long windowMillis) {
        if (maxFailures <= 1) {
            return true;
        }
        synchronized (recentFailureMillis) {
            recentFailureMillis.addLast(nowMillis);
            pruneExpired(nowMillis, windowMillis);
            return recentFailureMillis.size() >= maxFailures;
        }
    }

    int recentFailureCount(long nowMillis, long windowMillis) {
        synchronized (recentFailureMillis) {
            pruneExpired(nowMillis, windowMillis);
            return recentFailureMillis.size();
        }
    }

    private void pruneExpired(long nowMillis, long windowMillis) {
        while (!recentFailureMillis.isEmpty() && nowMillis - recentFailureMillis.peekFirst() > windowMillis) {
            recentFailureMillis.removeFirst();
        }
    }

    boolean trip(ProtectedPath path, String reason) {
        return failure.compareAndSet(null, new Failure(path, reason));
    }

    boolean tripAndCancel(ProtectedPath path, String reason, Runnable cancelCurrentPacket) {
        boolean firstTrip = trip(path, reason);
        cancelCurrentPacket.run();
        return firstTrip;
    }

    boolean cancelIfTripped(Runnable cancelPacket) {
        if (!isTripped()) {
            return false;
        }
        cancelPacket.run();
        return true;
    }

    boolean isTripped() {
        return failure.get() != null;
    }

    Failure failure() {
        return failure.get();
    }
}

package com.dekaeyman.chunkveil;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Separates successful decoding from actual concealment enforcement. */
final class PacketProtectionHealth {
    enum Status { INITIALIZED, OBSERVED, ENFORCED, FAILED, PARTIAL, DISABLED }

    record Snapshot(Status status, long observed, long enforced, long lastObservedMillis,
                    long lastEnforcedMillis, long lastFailureMillis, String failure) {
        long successes() { return enforced; }
        long lastSuccessMillis() { return lastEnforcedMillis; }
    }

    private static final class Channel {
        private final LongAdder observed = new LongAdder();
        private final LongAdder enforced = new LongAdder();
        private final AtomicLong lastObservedMillis = new AtomicLong();
        private final AtomicLong lastEnforcedMillis = new AtomicLong();
        private final AtomicLong lastFailureMillis = new AtomicLong();
        private volatile String failure;
    }

    private final Map<PacketSecurityState.ProtectedPath, Channel> channels =
            new EnumMap<>(PacketSecurityState.ProtectedPath.class);

    PacketProtectionHealth() {
        for (PacketSecurityState.ProtectedPath path : PacketSecurityState.ProtectedPath.values()) {
            channels.put(path, new Channel());
        }
    }

    void observed(PacketSecurityState.ProtectedPath path) {
        Channel channel = channels.get(path);
        channel.observed.increment();
        channel.lastObservedMillis.set(System.currentTimeMillis());
    }

    void enforced(PacketSecurityState.ProtectedPath path) {
        Channel channel = channels.get(path);
        channel.enforced.increment();
        channel.lastEnforcedMillis.set(System.currentTimeMillis());
    }

    void failed(PacketSecurityState.ProtectedPath path, String reason) {
        Channel channel = channels.get(path);
        channel.failure = reason;
        channel.lastFailureMillis.set(System.currentTimeMillis());
    }

    Snapshot snapshot(PacketSecurityState.ProtectedPath path) {
        Channel channel = channels.get(path);
        long observed = channel.observed.sum();
        long enforced = channel.enforced.sum();
        Status status = channel.failure != null ? Status.FAILED
                : enforced > 0 ? Status.ENFORCED : observed > 0 ? Status.OBSERVED : Status.INITIALIZED;
        return new Snapshot(status, observed, enforced, channel.lastObservedMillis.get(),
                channel.lastEnforcedMillis.get(), channel.lastFailureMillis.get(), channel.failure);
    }

    Snapshot summarize(PacketSecurityState.ProtectedPath... paths) {
        if (paths == null || paths.length == 0) {
            return new Snapshot(Status.DISABLED, 0, 0, 0, 0, 0, null);
        }
        long observed = 0, enforced = 0, lastObserved = 0, lastEnforced = 0, lastFailure = 0;
        int initialized = 0, observedOnly = 0, enforcedPaths = 0;
        String failure = null;
        for (PacketSecurityState.ProtectedPath path : paths) {
            Snapshot snapshot = snapshot(path);
            observed += snapshot.observed();
            enforced += snapshot.enforced();
            lastObserved = Math.max(lastObserved, snapshot.lastObservedMillis());
            lastEnforced = Math.max(lastEnforced, snapshot.lastEnforcedMillis());
            lastFailure = Math.max(lastFailure, snapshot.lastFailureMillis());
            switch (snapshot.status()) {
                case FAILED -> failure = path + ": " + snapshot.failure();
                case ENFORCED -> enforcedPaths++;
                case OBSERVED -> observedOnly++;
                case INITIALIZED -> initialized++;
                default -> { }
            }
        }
        Status status = failure != null ? Status.FAILED
                : enforcedPaths == paths.length ? Status.ENFORCED
                : initialized == paths.length ? Status.INITIALIZED
                : observedOnly == paths.length ? Status.OBSERVED : Status.PARTIAL;
        return new Snapshot(status, observed, enforced, lastObserved, lastEnforced, lastFailure, failure);
    }
}

package com.dekaeyman.chunkveil;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Runtime evidence for each protected packet surface. */
final class PacketProtectionHealth {
    enum Status {
        INITIALIZED,
        EXERCISED,
        FAILED,
        PARTIAL,
        DISABLED
    }

    record Snapshot(Status status, long successes, long lastSuccessMillis, long lastFailureMillis, String failure) {
    }

    private static final class Channel {
        private final LongAdder successes = new LongAdder();
        private final AtomicLong lastSuccessMillis = new AtomicLong();
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

    void exercised(PacketSecurityState.ProtectedPath path) {
        Channel channel = channels.get(path);
        channel.successes.increment();
        channel.lastSuccessMillis.set(System.currentTimeMillis());
    }

    void failed(PacketSecurityState.ProtectedPath path, String reason) {
        Channel channel = channels.get(path);
        channel.failure = reason;
        channel.lastFailureMillis.set(System.currentTimeMillis());
    }

    Snapshot snapshot(PacketSecurityState.ProtectedPath path) {
        Channel channel = channels.get(path);
        Status status = channel.failure != null
                ? Status.FAILED
                : channel.successes.sum() > 0 ? Status.EXERCISED : Status.INITIALIZED;
        return new Snapshot(status, channel.successes.sum(), channel.lastSuccessMillis.get(),
                channel.lastFailureMillis.get(), channel.failure);
    }

    Snapshot summarize(PacketSecurityState.ProtectedPath... paths) {
        if (paths == null || paths.length == 0) {
            return new Snapshot(Status.DISABLED, 0L, 0L, 0L, null);
        }
        long successes = 0L;
        long lastSuccess = 0L;
        long lastFailure = 0L;
        String failure = null;
        int exercised = 0;
        for (PacketSecurityState.ProtectedPath path : paths) {
            Snapshot snapshot = snapshot(path);
            successes += snapshot.successes();
            lastSuccess = Math.max(lastSuccess, snapshot.lastSuccessMillis());
            lastFailure = Math.max(lastFailure, snapshot.lastFailureMillis());
            if (snapshot.status() == Status.FAILED) {
                failure = path + ": " + snapshot.failure();
            } else if (snapshot.status() == Status.EXERCISED) {
                exercised++;
            }
        }
        Status status = failure != null ? Status.FAILED
                : exercised == 0 ? Status.INITIALIZED
                : exercised == paths.length ? Status.EXERCISED : Status.PARTIAL;
        return new Snapshot(status, successes, lastSuccess, lastFailure, failure);
    }
}

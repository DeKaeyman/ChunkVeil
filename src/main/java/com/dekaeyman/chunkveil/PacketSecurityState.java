package com.dekaeyman.chunkveil;

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

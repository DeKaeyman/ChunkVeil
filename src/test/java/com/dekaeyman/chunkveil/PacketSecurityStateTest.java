package com.dekaeyman.chunkveil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PacketSecurityStateTest {
    @Test
    void everyProtectedPacketPathTripsImmediatelyUnderFaultInjection() {
        for (PacketSecurityState.ProtectedPath path : PacketSecurityState.ProtectedPath.values()) {
            PacketSecurityState state = new PacketSecurityState();
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

            assertFalse(state.isTripped(), path + " must begin active");
            assertTrue(state.tripAndCancel(path, "injected " + path, () -> {
                assertTrue(state.isTripped(), "TRIPPED must be published before the triggering handler returns");
                cancelled.set(true);
            }), path + " must win its first trip");
            assertTrue(cancelled.get(), path + " must cancel the triggering packet");
            assertTrue(state.isTripped(), path + " must be tripped immediately");
            assertNotNull(state.failure());
            assertEquals(path, state.failure().path());
            assertEquals("injected " + path, state.failure().reason());
        }
    }

    @Test
    void trippedStateQuarantinesLaterProtectedPackets() {
        PacketSecurityState state = new PacketSecurityState();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        assertFalse(state.cancelIfTripped(() -> cancelled.set(true)));
        assertFalse(cancelled.get());
        state.trip(PacketSecurityState.ProtectedPath.CHUNK, "fault");
        assertTrue(state.cancelIfTripped(() -> cancelled.set(true)));
        assertTrue(cancelled.get());
    }

    @Test
    void firstCriticalFailureIsRetained() {
        PacketSecurityState state = new PacketSecurityState();

        assertTrue(state.trip(PacketSecurityState.ProtectedPath.CHUNK, "first"));
        assertFalse(state.trip(PacketSecurityState.ProtectedPath.PARTICLE, "second"));
        assertEquals(PacketSecurityState.ProtectedPath.CHUNK, state.failure().path());
        assertEquals("first", state.failure().reason());
    }

    @Test
    void thresholdOfOneCrossesOnFirstFailure() {
        PacketSecurityState state = new PacketSecurityState();
        assertTrue(state.recordFailure(1_000L, 1, 600_000L), "strict threshold must trip immediately");
    }

    @Test
    void failuresBelowThresholdAreTolerated() {
        PacketSecurityState state = new PacketSecurityState();
        assertFalse(state.recordFailure(1_000L, 3, 600_000L));
        assertFalse(state.recordFailure(2_000L, 3, 600_000L));
        assertEquals(2, state.recentFailureCount(2_000L, 600_000L));
        assertTrue(state.recordFailure(3_000L, 3, 600_000L), "the third failure inside the window must cross");
    }

    @Test
    void failuresOutsideTheWindowExpire() {
        PacketSecurityState state = new PacketSecurityState();
        assertFalse(state.recordFailure(1_000L, 3, 10_000L));
        assertFalse(state.recordFailure(2_000L, 3, 10_000L));
        // Both earlier failures are older than the window by now.
        assertFalse(state.recordFailure(20_000L, 3, 10_000L), "expired failures must not count toward the threshold");
        assertEquals(1, state.recentFailureCount(20_000L, 10_000L));
    }
}

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
}

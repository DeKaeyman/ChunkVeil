package com.dekaeyman.chunkveil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PacketProtectionHealthTest {
    @Test
    void movesFromInitializedToExercised() {
        PacketProtectionHealth health = new PacketProtectionHealth();

        assertEquals(PacketProtectionHealth.Status.INITIALIZED,
                health.snapshot(PacketSecurityState.ProtectedPath.CHUNK).status());
        health.exercised(PacketSecurityState.ProtectedPath.CHUNK);
        PacketProtectionHealth.Snapshot snapshot = health.snapshot(PacketSecurityState.ProtectedPath.CHUNK);
        assertEquals(PacketProtectionHealth.Status.EXERCISED, snapshot.status());
        assertEquals(1L, snapshot.successes());
    }

    @Test
    void failureOverridesEarlierSuccess() {
        PacketProtectionHealth health = new PacketProtectionHealth();
        health.exercised(PacketSecurityState.ProtectedPath.PARTICLE);
        health.failed(PacketSecurityState.ProtectedPath.PARTICLE, "injected failure");

        PacketProtectionHealth.Snapshot snapshot = health.snapshot(PacketSecurityState.ProtectedPath.PARTICLE);
        assertEquals(PacketProtectionHealth.Status.FAILED, snapshot.status());
        assertEquals("injected failure", snapshot.failure());
        assertNotNull(snapshot);
    }

    @Test
    void summarizesPartialAndFailedGroups() {
        PacketProtectionHealth health = new PacketProtectionHealth();
        health.exercised(PacketSecurityState.ProtectedPath.BLOCK_CHANGE);

        PacketProtectionHealth.Snapshot partial = health.summarize(
                PacketSecurityState.ProtectedPath.BLOCK_CHANGE,
                PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
        assertEquals(PacketProtectionHealth.Status.PARTIAL, partial.status());

        health.failed(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE, "bad layout");
        assertEquals(PacketProtectionHealth.Status.FAILED, health.summarize(
                PacketSecurityState.ProtectedPath.BLOCK_CHANGE,
                PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE).status());
    }
}

package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerVeilStateEntityTest {
    @Test void replacingAnEntityIdRemovesTheOldUuid() {
        PlayerVeilState state = new PlayerVeilState();
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();
        state.markEntityHidden(42, oldUuid);
        state.markEntityHidden(42, newUuid);
        assertFalse(state.hiddenEntityUuids().contains(oldUuid));
        assertTrue(state.hiddenEntityUuids().contains(newUuid));
    }

    @Test void forgettingByUuidRemovesTheIntegerId() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(42, uuid);
        state.forgetEntity(uuid);
        assertFalse(state.isEntityHidden(42));
        assertFalse(state.hiddenEntityUuids().contains(uuid));
    }

    @Test void movingAUuidToANewIdRemovesTheOldId() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(42, uuid);
        state.markEntityHidden(84, uuid);
        assertFalse(state.isEntityHidden(42));
        assertTrue(state.isEntityHidden(84));
    }

    @Test void clientDestroyForgetsPacketOnlyHiddenEntity() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(42, uuid);

        assertTrue(state.forgetEntityDestroyedForClient(42));
        assertFalse(state.isEntityHidden(42));
        assertFalse(state.hiddenEntityUuids().contains(uuid));
    }

    @Test void clientDestroyKeepsApiHiddenEntity() {
        // Player#hideEntity itself sends the destroy packet; forgetting the
        // entity then would leave it hidden by Paper with nothing to show it.
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHiddenByApi(42, uuid);

        assertFalse(state.forgetEntityDestroyedForClient(42));
        assertTrue(state.isEntityHidden(42));
        assertTrue(state.isEntityHiddenByApi(uuid));
        assertTrue(state.hiddenEntityUuids().contains(uuid));
    }

    @Test void packetOnlyHiddenEntityCanBeUpgradedToApiHidden() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(42, uuid);
        assertFalse(state.isEntityHiddenByApi(uuid));

        state.markEntityHiddenByApi(42, uuid);
        assertTrue(state.isEntityHiddenByApi(uuid));
        assertFalse(state.forgetEntityDestroyedForClient(42));
    }

    @Test void explicitForgetClearsApiHiddenFlag() {
        // Our own reveal path forgets the entity and calls showEntity; a later
        // hide must start from a clean state and hide through the API again.
        PlayerVeilState byId = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        byId.markEntityHiddenByApi(42, uuid);
        byId.forgetEntity(42);
        assertFalse(byId.isEntityHiddenByApi(uuid));

        PlayerVeilState byUuid = new PlayerVeilState();
        byUuid.markEntityHiddenByApi(42, uuid);
        byUuid.forgetEntity(uuid);
        assertFalse(byUuid.isEntityHiddenByApi(uuid));
        assertFalse(byUuid.isEntityHidden(42));
    }

    @Test void clearingHiddenEntitiesClearsApiHiddenFlags() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHiddenByApi(42, uuid);
        state.clearHiddenEntities();
        assertFalse(state.isEntityHiddenByApi(uuid));
        assertFalse(state.isEntityHidden(42));
    }

    @Test void revealOfApiHiddenEntityNeedsOnlyShow() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHiddenByApi(42, uuid);

        assertTrue(state.forgetEntityForReveal(42, uuid));
        assertFalse(state.isEntityHidden(42));
        assertFalse(state.isEntityHiddenByApi(uuid));
    }

    @Test void revealOfPacketOnlyHiddenEntityRequiresRepair() {
        // Spawn was cancelled without an API hide: showEntity alone would be a
        // no-op, so the caller must hide + show to make Paper resend it.
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(42, uuid);

        assertFalse(state.forgetEntityForReveal(42, uuid));
        assertFalse(state.isEntityHidden(42), "forgotten before the resent spawn passes the listener");
    }

    @Test void entityScanIsRateLimitedByItsInterval() {
        PlayerVeilState state = new PlayerVeilState();
        assertTrue(state.shouldRefreshEntityScan(500L, 10_000L), "first scan is always due");
        assertFalse(state.shouldRefreshEntityScan(500L, 10_200L));
        assertTrue(state.shouldRefreshEntityScan(500L, 10_500L));
    }

    @Test void slowerIdleIntervalDelaysTheNextScan() {
        PlayerVeilState state = new PlayerVeilState();
        assertTrue(state.shouldRefreshEntityScan(500L, 10_000L));
        assertFalse(state.shouldRefreshEntityScan(2_000L, 11_000L));
        assertTrue(state.shouldRefreshEntityScan(2_000L, 12_000L));
    }

    @Test void requestedEntityScanIsDueImmediately() {
        // A chunk reveal must not wait for the interval to show its entities.
        PlayerVeilState state = new PlayerVeilState();
        assertTrue(state.shouldRefreshEntityScan(500L, 10_000L));
        assertFalse(state.shouldRefreshEntityScan(500L, 10_100L));

        state.requestEntityScan();
        assertTrue(state.shouldRefreshEntityScan(500L, 10_100L));
    }

    @Test void unknownEntityDestroyIsHarmless() {
        PlayerVeilState state = new PlayerVeilState();
        assertTrue(state.forgetEntityDestroyedForClient(7));
        assertFalse(state.isEntityHidden(7));
    }
}

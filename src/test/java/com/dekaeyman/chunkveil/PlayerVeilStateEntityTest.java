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
}

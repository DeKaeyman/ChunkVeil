package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

class PlayerVeilStateLifecycleTest {
    private static Entity entity(int id, UUID uuid) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEntityId" -> id;
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // --- API-hidden entity objects (item 35) ---

    @Test
    void apiHideRemembersTheEntityObjectUntilForgotten() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        Entity mob = entity(7, uuid);
        state.markEntityHiddenByApi(mob);

        assertTrue(state.isEntityHiddenByApi(uuid));
        assertSame(mob, state.apiHiddenEntity(uuid), "needed to clear Paper's mark once the mob is gone");

        state.forgetEntity(uuid);
        assertNull(state.apiHiddenEntity(uuid));
        assertFalse(state.isEntityHiddenByApi(uuid));
    }

    @Test
    void destroyPacketKeepsTheApiHiddenObject() {
        // hideEntity itself sends a destroy; the object must survive it.
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        Entity mob = entity(7, uuid);
        state.markEntityHiddenByApi(mob);

        assertFalse(state.forgetEntityDestroyedForClient(7));
        assertSame(mob, state.apiHiddenEntity(uuid));
    }

    @Test
    void revealAndClearDropTheObject() {
        PlayerVeilState state = new PlayerVeilState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.markEntityHiddenByApi(entity(1, first));
        state.markEntityHiddenByApi(entity(2, second));

        assertTrue(state.forgetEntityForReveal(1, first));
        assertNull(state.apiHiddenEntity(first));

        state.clearHiddenEntities();
        assertNull(state.apiHiddenEntity(second));
    }

    @Test
    void packetOnlyHideHasNoObject() {
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHidden(3, uuid);
        assertNull(state.apiHiddenEntity(uuid));
        assertTrue(state.forgetEntityDestroyedForClient(3), "packet-only entries go with the destroy");
    }

    @Test
    void revealOfAnEntityWithANewIdForgetsTheOldEntryToo() {
        // The mob's chunk reloaded (or it went through a portal): same UUID,
        // new entity id. The reveal must not leave the old entry behind, or
        // the next hide sees "already hidden" and never hides it again.
        PlayerVeilState state = new PlayerVeilState();
        UUID uuid = UUID.randomUUID();
        state.markEntityHiddenByApi(entity(10, uuid));

        state.forgetEntityForReveal(99, uuid);

        assertFalse(state.isEntityHiddenByApi(uuid));
        assertNull(state.apiHiddenEntity(uuid));
        assertFalse(state.isEntityHidden(10), "old id forgotten as well");
        assertEquals(0, state.hiddenEntityCount());
    }

    // --- Queue ordering and dedupe (item 34) ---

    @Test
    void priorityUpdatesComeBeforeRegularOnes() {
        ChunkKey regular = ChunkKey.of("world", 1, 0);
        ChunkKey priority = ChunkKey.of("world", 2, 0);
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(regular, VeilMode.HIDE);
        state.markApplied(priority, VeilMode.HIDE);

        state.enqueue(regular, VeilMode.REVEAL);
        state.enqueuePriority(priority, VeilMode.REVEAL);

        assertEquals(priority, state.poll().chunkKey());
        assertEquals(regular, state.poll().chunkKey());
        assertNull(state.poll());
    }

    @Test
    void repeatedRevealsOfOneChunkAreQueuedOnce() {
        ChunkKey chunk = ChunkKey.of("world", 1, 0);
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(chunk, VeilMode.HIDE);
        for (int i = 0; i < 10; i++) {
            state.enqueuePriority(chunk, VeilMode.REVEAL);
            state.enqueue(chunk, VeilMode.REVEAL);
        }
        assertEquals(1, state.queuedChunkCount());
        assertEquals(chunk, state.poll().chunkKey());
        assertNull(state.poll());
    }

    @Test
    void nothingIsQueuedForChunksThatNeedNoReveal() {
        ChunkKey neverHidden = ChunkKey.of("world", 1, 0);
        ChunkKey revealed = ChunkKey.of("world", 2, 0);
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(revealed, VeilMode.REVEAL);

        state.enqueuePriority(neverHidden, VeilMode.REVEAL);
        state.enqueuePriority(revealed, VeilMode.REVEAL);

        assertEquals(0, state.queuedChunkCount());
        assertNull(state.poll());
    }

    @Test
    void chunkLeavingViewIsHiddenAndItsPendingRevealDropped() {
        ChunkKey chunk = ChunkKey.of("world", 1, 0);
        PlayerVeilState state = new PlayerVeilState();
        state.markApplied(chunk, VeilMode.HIDE);
        state.enqueuePriority(chunk, VeilMode.REVEAL);

        state.markChunkHiddenWithoutSending(chunk);

        assertFalse(state.isChunkQueued(chunk));
        assertEquals(VeilMode.HIDE, state.appliedMode(chunk));
        assertNull(state.poll());
    }
}

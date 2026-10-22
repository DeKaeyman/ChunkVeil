package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VeilEngineEntityPacketTest {
    private final VeilEngine engine = new VeilEngine(null, null, null);
    private final UUID viewer = UUID.randomUUID();

    @Test void cancelledSpawnIsHiddenBeforeTheHandlerReturns() {
        // Metadata and equipment follow the spawn in the same flush; they are
        // only cancelled if the id is already hidden, with no tick in between.
        UUID entity = UUID.randomUUID();
        assertFalse(engine.isEntityHidden(viewer, 42));

        engine.markEntityHiddenFromPacket(viewer, 42, entity);

        assertTrue(engine.isEntityHidden(viewer, 42));
    }

    @Test void markingWorksBeforeTheViewerHasAnyVeilState() {
        // A spawn can be cancelled before the viewer's first scan created state.
        UUID otherViewer = UUID.randomUUID();
        engine.markEntityHiddenFromPacket(otherViewer, 7, UUID.randomUUID());
        assertTrue(engine.isEntityHidden(otherViewer, 7));
        assertFalse(engine.isEntityHidden(viewer, 7), "hidden state is per viewer");
    }

    @Test void unreadableEntityIdIsIgnored() {
        engine.markEntityHiddenFromPacket(viewer, -1, UUID.randomUUID());
        assertFalse(engine.isEntityHidden(viewer, -1));
    }

    @Test void followUpFastPathIsOnlyTakenWhileNothingIsHidden() {
        // The follow-up handler skips reading packets when this is false, so
        // it must turn true the moment a spawn is cancelled (same thread,
        // before the metadata/equipment packets behind it are processed).
        UUID entity = UUID.randomUUID();
        assertFalse(engine.hasHiddenEntities(viewer), "no state yet");

        engine.markEntityHiddenFromPacket(viewer, 42, entity);
        assertTrue(engine.hasHiddenEntities(viewer));
    }

    @Test void fastPathIsPerViewer() {
        UUID other = UUID.randomUUID();
        engine.markEntityHiddenFromPacket(viewer, 42, UUID.randomUUID());
        assertFalse(engine.hasHiddenEntities(other), "another viewer still takes the fast path");
    }

    @Test void stoppedEngineDoesNotRecordHiddenEntities() {
        VeilEngine stopped = new VeilEngine(null, null, null);
        stopped.stopConfidentialityFirst();
        stopped.markEntityHiddenFromPacket(viewer, 42, UUID.randomUUID());
        assertFalse(stopped.isEntityHidden(viewer, 42));
    }
}

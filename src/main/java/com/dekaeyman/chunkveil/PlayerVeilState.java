package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.bukkit.entity.Entity;

final class PlayerVeilState {
    // Mutated by the owning player's thread. The chunk collections are
    // concurrent because chunk-unload cleanup (forgetChunk) runs synchronously
    // on the unloading chunk's region thread on Folia; every cross-thread
    // interleaving is benign (a surviving stale update is skipped later by the
    // chunk-loaded check).
    private final Deque<VeilChunkUpdate> queuedUpdates = new ConcurrentLinkedDeque<>();
    private final Map<ChunkKey, VeilMode> queuedModes = new ConcurrentHashMap<>();
    private final Map<ChunkKey, VeilMode> appliedModes = new ConcurrentHashMap<>();
    private final Set<Integer> hiddenEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hiddenEntityUuids = ConcurrentHashMap.newKeySet();
    private final Map<Integer, UUID> hiddenEntityUuidsById = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hiddenEntityIdsByUuid = new ConcurrentHashMap<>();
    // Hidden entities additionally hidden through Player#hideEntity. Paper
    // keeps those hidden until showEntity is called, so they must never be
    // forgotten just because the client was told to destroy them — hideEntity
    // itself produces that destroy packet.
    private final Set<UUID> apiHiddenEntityUuids = ConcurrentHashMap.newKeySet();
    // The entity object each API hide was made with. Paper keeps its own
    // hidden mark (keyed by UUID) until showEntity; when the entity is gone
    // (died, or its chunk unloaded) this object is still needed to clear it.
    private final Map<UUID, Entity> apiHiddenEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Long> entityLastValidatedMillis = new ConcurrentHashMap<>();
    private final Set<ChunkKey> visibleChunks = ConcurrentHashMap.newKeySet();
    private volatile long lastViewRevealRefreshMillis;
    private volatile long lastEntityScanMillis;
    private int entityScanCursor;
    private int entityScanNumber;
    private volatile long lastFullValidationMillis;
    private String lastViewRevealWorldName;
    private double lastViewRevealX;
    private double lastViewRevealY;
    private double lastViewRevealZ;
    private float lastViewRevealYaw;
    private float lastViewRevealPitch;

    void enqueue(ChunkKey chunkKey, VeilMode mode) {
        enqueue(chunkKey, mode, false);
    }

    void enqueuePriority(ChunkKey chunkKey, VeilMode mode) {
        enqueue(chunkKey, mode, true);
    }

    /**
     * Puts an update that could not be processed yet (chunk owned by another
     * Folia region) back at the end of the queue, keeping its priority flag,
     * so it is retried later without blocking the updates behind it.
     */
    void requeueDeferred(VeilChunkUpdate update) {
        enqueue(update.chunkKey(), update.mode(), update.priority(), false);
    }

    private void enqueue(ChunkKey chunkKey, VeilMode mode, boolean priority) {
        enqueue(chunkKey, mode, priority, priority);
    }

    private void enqueue(ChunkKey chunkKey, VeilMode mode, boolean priority, boolean front) {
        if (mode == VeilMode.REVEAL && !appliedModes.containsKey(chunkKey)) {
            queuedModes.remove(chunkKey);
            return;
        }
        if (mode == appliedModes.get(chunkKey)) {
            queuedModes.remove(chunkKey);
            return;
        }

        // Invariant: a queuedModes entry always has a matching deque entry.
        // forgetChunk runs on another region thread on Folia; doing the map
        // update and the deque insert inside compute (which locks this key)
        // keeps it from removing the deque entry between the two, which used
        // to leave a mark with no update behind it: every later enqueue of
        // the chunk then returned early and it stayed fake for good.
        queuedModes.compute(chunkKey, (key, queuedMode) -> {
            if (queuedMode == mode) {
                return queuedMode;
            }
            VeilChunkUpdate update = new VeilChunkUpdate(key, mode, priority);
            if (front) {
                queuedUpdates.addFirst(update);
            } else {
                queuedUpdates.addLast(update);
            }
            return mode;
        });
    }

    VeilChunkUpdate poll() {
        VeilChunkUpdate update;
        while ((update = queuedUpdates.poll()) != null) {
            // Atomic claim: an entry whose mark was removed or replaced is stale.
            if (queuedModes.remove(update.chunkKey(), update.mode())) {
                return update;
            }
        }
        return null;
    }

    /** Test hook: every queue mark has a deque entry with the same mode (call only while quiescent). */
    boolean queueMarksConsistent() {
        for (Map.Entry<ChunkKey, VeilMode> mark : queuedModes.entrySet()) {
            boolean found = false;
            for (VeilChunkUpdate update : queuedUpdates) {
                if (update.chunkKey().equals(mark.getKey()) && update.mode() == mark.getValue()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Removes a chunk's queue mark and its deque entries as one step for that key. */
    private void unqueue(ChunkKey chunkKey) {
        queuedModes.computeIfPresent(chunkKey, (key, ignored) -> {
            queuedUpdates.removeIf(update -> update.chunkKey().equals(key));
            return null;
        });
    }

    void markApplied(ChunkKey chunkKey, VeilMode mode) {
        appliedModes.put(chunkKey, mode);
    }

    /**
     * Records a chunk that left the player's view as hidden again without
     * sending anything. Re-masking it would cost a full multi-block packet
     * per chunk for no security benefit: the client already received the
     * real data, and later block updates in a non-visible chunk are already
     * rewritten fake by the packet listener. Recording it as hidden makes a
     * later reveal resend the chunk, which also clears those fake updates.
     */
    void markChunkHiddenWithoutSending(ChunkKey chunkKey) {
        unqueue(chunkKey);
        appliedModes.put(chunkKey, VeilMode.HIDE);
    }

    VeilMode appliedMode(ChunkKey chunkKey) {
        return appliedModes.get(chunkKey);
    }

    boolean isChunkQueued(ChunkKey chunkKey) {
        return queuedModes.containsKey(chunkKey);
    }

    int queuedChunkCount() {
        return queuedModes.size();
    }

    int appliedChunkCount() {
        return appliedModes.size();
    }

    void markEntityHidden(int entityId, UUID uuid) {
        hiddenEntityIds.add(entityId);
        if (uuid == null) {
            UUID previousUuid = hiddenEntityUuidsById.remove(entityId);
            if (previousUuid != null) {
                hiddenEntityUuids.remove(previousUuid);
                hiddenEntityIdsByUuid.remove(previousUuid);
                forgetApiHidden(previousUuid);
            }
        } else {
            UUID previousUuid = hiddenEntityUuidsById.put(entityId, uuid);
            if (previousUuid != null && !previousUuid.equals(uuid)) {
                hiddenEntityUuids.remove(previousUuid);
                hiddenEntityIdsByUuid.remove(previousUuid);
                forgetApiHidden(previousUuid);
            }
            Integer previousId = hiddenEntityIdsByUuid.put(uuid, entityId);
            if (previousId != null && previousId != entityId) {
                hiddenEntityIds.remove(previousId);
                hiddenEntityUuidsById.remove(previousId);
            }
            hiddenEntityUuids.add(uuid);
        }
    }

    /** Marks an entity hidden that is also hidden through Player#hideEntity. */
    void markEntityHiddenByApi(int entityId, UUID uuid) {
        markEntityHidden(entityId, uuid);
        apiHiddenEntityUuids.add(uuid);
    }

    void markEntityHiddenByApi(Entity entity) {
        markEntityHiddenByApi(entity.getEntityId(), entity.getUniqueId());
        apiHiddenEntities.put(entity.getUniqueId(), entity);
    }

    /** The entity object an API hide was made with, or null. */
    Entity apiHiddenEntity(UUID uuid) {
        return uuid == null ? null : apiHiddenEntities.get(uuid);
    }

    private void forgetApiHidden(UUID uuid) {
        apiHiddenEntityUuids.remove(uuid);
        apiHiddenEntities.remove(uuid);
    }

    boolean isEntityHidden(int entityId) {
        return hiddenEntityIds.contains(entityId);
    }

    boolean isEntityHiddenByApi(UUID uuid) {
        return uuid != null && apiHiddenEntityUuids.contains(uuid);
    }

    /**
     * Handles a destroy packet sent to the client. Packet-only hidden entities
     * are forgotten (the client no longer tracks them). API-hidden entities are
     * kept: Paper still hides them, and only our own reveal may clear them.
     * Returns whether the entity was forgotten.
     */
    boolean forgetEntityDestroyedForClient(int entityId) {
        UUID uuid = hiddenEntityUuidsById.get(entityId);
        if (isEntityHiddenByApi(uuid)) {
            return false;
        }
        forgetEntity(entityId);
        return true;
    }

    /**
     * Forgets an entity that is about to be revealed and reports whether it
     * was hidden through the API. If not, the caller must force a re-pair
     * (hideEntity + showEntity): showEntity alone does nothing for an entity
     * Paper never hid, so its cancelled spawn would never be resent.
     */
    boolean forgetEntityForReveal(int entityId, UUID uuid) {
        boolean hiddenByApi = isEntityHiddenByApi(uuid);
        forgetEntity(entityId);
        // Also by UUID: an entity re-created with a new id (chunk reload,
        // portal) is still recorded under its old id. Leaving that entry
        // made the next hide think it was already hidden, so it never was.
        if (uuid != null) {
            forgetEntity(uuid);
        }
        return hiddenByApi;
    }

    /** Forgets an entity entirely; callers that hid it through the API must show it. */
    void forgetEntity(int entityId) {
        hiddenEntityIds.remove(entityId);
        UUID uuid = hiddenEntityUuidsById.remove(entityId);
        if (uuid != null) {
            hiddenEntityUuids.remove(uuid);
            hiddenEntityIdsByUuid.remove(uuid);
            forgetApiHidden(uuid);
        }
    }

    void forgetEntity(UUID uuid) {
        hiddenEntityUuids.remove(uuid);
        forgetApiHidden(uuid);
        Integer entityId = hiddenEntityIdsByUuid.remove(uuid);
        if (entityId != null) {
            hiddenEntityIds.remove(entityId);
            hiddenEntityUuidsById.remove(entityId, uuid);
        }
        entityLastValidatedMillis.remove(uuid);
    }

    /**
     * Scan bookkeeping for {@code /chunkveil inspect}'s "oldest validation"
     * age. When the whole candidate list was checked (the normal case) one
     * timestamp says it all; per-entity timestamps are only kept while the
     * per-scan limit defers some entities, which used to cost two UUID lists
     * and a set rebuild on every scan.
     */
    void recordEntityValidation(java.util.List<? extends org.bukkit.entity.Entity> candidates,
            java.util.List<? extends org.bukkit.entity.Entity> validated, boolean allValidated, long nowMillis) {
        if (allValidated) {
            if (!entityLastValidatedMillis.isEmpty()) {
                entityLastValidatedMillis.clear();
            }
            lastFullValidationMillis = nowMillis;
            return;
        }
        Set<UUID> current = new HashSet<>(candidates.size() * 2);
        for (org.bukkit.entity.Entity entity : candidates) {
            current.add(entity.getUniqueId());
        }
        entityLastValidatedMillis.keySet().retainAll(current);
        for (UUID uuid : current) {
            entityLastValidatedMillis.putIfAbsent(uuid, nowMillis);
        }
        for (org.bukkit.entity.Entity entity : validated) {
            entityLastValidatedMillis.put(entity.getUniqueId(), nowMillis);
        }
    }

    int nextEntityScanNumber() {
        return ++entityScanNumber;
    }

    void recordEntityValidationCandidates(Iterable<UUID> candidates, Iterable<UUID> validated) {
        Set<UUID> current = new HashSet<>();
        for (UUID uuid : candidates) current.add(uuid);
        entityLastValidatedMillis.keySet().retainAll(current);
        long now = System.currentTimeMillis();
        for (UUID uuid : current) entityLastValidatedMillis.putIfAbsent(uuid, now);
        for (UUID uuid : validated) entityLastValidatedMillis.put(uuid, now);
    }

    long oldestEntityValidationAgeMillis() {
        if (entityLastValidatedMillis.isEmpty() && lastFullValidationMillis > 0L) {
            return Math.max(0L, System.currentTimeMillis() - lastFullValidationMillis);
        }
        long oldest = Long.MAX_VALUE;
        for (long timestamp : entityLastValidatedMillis.values()) oldest = Math.min(oldest, timestamp);
        return oldest == Long.MAX_VALUE ? -1L : Math.max(0L, System.currentTimeMillis() - oldest);
    }

    Set<UUID> hiddenEntityUuids() {
        return hiddenEntityUuids;
    }

    int hiddenEntityCount() {
        return hiddenEntityIds.size();
    }

    boolean isChunkVisible(ChunkKey chunkKey) {
        return visibleChunks.contains(chunkKey);
    }

    Set<ChunkKey> visibleChunkSnapshot() {
        return Set.copyOf(visibleChunks);
    }

    int visibleChunkCount() {
        return visibleChunks.size();
    }

    long lastViewRevealRefreshMillis() {
        return lastViewRevealRefreshMillis;
    }

    /**
     * Visible chunks in the world that are still recorded as hidden and not
     * already queued: the ones a reveal still has to be sent for.
     */
    List<ChunkKey> visibleChunksAwaitingReveal(String worldName) {
        List<ChunkKey> awaiting = new ArrayList<>();
        for (ChunkKey chunkKey : visibleChunks) {
            if (appliedModes.get(chunkKey) == VeilMode.HIDE
                    && !queuedModes.containsKey(chunkKey)
                    && chunkKey.worldName().equals(worldName)) {
                awaiting.add(chunkKey);
            }
        }
        return awaiting;
    }

    void rememberVisibleChunks(Set<ChunkKey> chunkKeys) {
        visibleChunks.addAll(chunkKeys);
    }

    Set<ChunkKey> forgetVisibleChunksOutside(String worldName, int centerX, int centerZ, int radius) {
        Set<ChunkKey> forgottenChunks = new HashSet<>();
        visibleChunks.removeIf(chunkKey -> {
            boolean outside = !chunkKey.worldName().equals(worldName)
                    || Math.abs(chunkKey.x() - centerX) > radius
                    || Math.abs(chunkKey.z() - centerZ) > radius;
            if (outside) {
                forgottenChunks.add(chunkKey);
            }
            return outside;
        });
        return forgottenChunks;
    }

    /** Makes the next view scan run regardless of interval and movement thresholds. */
    void requestViewScan() {
        lastViewRevealRefreshMillis = 0L;
        lastViewRevealWorldName = null;
    }

    /** Cheap pre-check: whether the minimum interval since the last view scan has passed. */
    boolean viewRevealIntervalElapsed(long nowMillis, long minimumIntervalMillis) {
        return nowMillis - lastViewRevealRefreshMillis >= minimumIntervalMillis;
    }

    boolean shouldRefreshViewReveal(LocationSnapshot snapshot, VeilSettings settings) {
        long now = System.currentTimeMillis();
        long elapsedMillis = now - lastViewRevealRefreshMillis;
        if (elapsedMillis < settings.viewRevealRefreshMillis()) {
            return false;
        }

        if (lastViewRevealWorldName != null
                && lastViewRevealWorldName.equals(snapshot.worldName())
                && elapsedMillis < settings.viewRevealForceRefreshMillis()
                && distanceSquaredToLastScan(snapshot) < settings.viewRevealMoveThresholdBlocks() * settings.viewRevealMoveThresholdBlocks()
                && yawDelta(snapshot.yaw(), lastViewRevealYaw) < settings.viewRevealYawThresholdDegrees()
                && Math.abs(snapshot.pitch() - lastViewRevealPitch) < settings.viewRevealPitchThresholdDegrees()) {
            return false;
        }

        lastViewRevealWorldName = snapshot.worldName();
        lastViewRevealX = snapshot.x();
        lastViewRevealY = snapshot.y();
        lastViewRevealZ = snapshot.z();
        lastViewRevealYaw = snapshot.yaw();
        lastViewRevealPitch = snapshot.pitch();
        lastViewRevealRefreshMillis = now;
        return true;
    }

    boolean shouldRefreshEntityScan(VeilSettings settings) {
        return shouldRefreshEntityScan(settings.entityScanIntervalMillis(), System.currentTimeMillis());
    }

    /** Claims the next entity scan if at least {@code intervalMillis} passed since the last one. */
    boolean shouldRefreshEntityScan(long intervalMillis, long nowMillis) {
        if (nowMillis - lastEntityScanMillis < intervalMillis) {
            return false;
        }
        lastEntityScanMillis = nowMillis;
        return true;
    }

    /** Makes the next entity scan due immediately, e.g. after a chunk was revealed. */
    void requestEntityScan() {
        lastEntityScanMillis = 0L;
    }

    int entityScanCursor() {
        return entityScanCursor;
    }

    void entityScanCursor(int cursor) {
        entityScanCursor = Math.max(0, cursor);
    }

    private double distanceSquaredToLastScan(LocationSnapshot snapshot) {
        double dx = snapshot.x() - lastViewRevealX;
        double dy = snapshot.y() - lastViewRevealY;
        double dz = snapshot.z() - lastViewRevealZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private float yawDelta(float yaw, float previousYaw) {
        float delta = Math.abs(yaw - previousYaw) % 360.0F;
        return delta > 180.0F ? 360.0F - delta : delta;
    }

    void forgetChunk(ChunkKey chunkKey) {
        appliedModes.remove(chunkKey);
        unqueue(chunkKey);
    }

    void clearQueuedUpdates() {
        // Deque first: a mark cleared after its entry is harmless, the
        // other order could strand a mark added in between.
        queuedUpdates.clear();
        queuedModes.clear();
    }

    void clearHiddenEntities() {
        hiddenEntityIds.clear();
        hiddenEntityUuids.clear();
        apiHiddenEntityUuids.clear();
        apiHiddenEntities.clear();
        hiddenEntityUuidsById.clear();
        hiddenEntityIdsByUuid.clear();
        entityLastValidatedMillis.clear();
    }

    void clear() {
        clearQueuedUpdates();
        appliedModes.clear();
        clearHiddenEntities();
        visibleChunks.clear();
        lastViewRevealRefreshMillis = 0L;
        lastEntityScanMillis = 0L;
        entityScanCursor = 0;
        lastViewRevealWorldName = null;
    }

    record LocationSnapshot(String worldName, double x, double y, double z, float yaw, float pitch) {
    }
}

package com.dekaeyman.chunkveil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlayerVeilState {
    private final Deque<VeilChunkUpdate> queuedUpdates = new ArrayDeque<>();
    private final Map<ChunkKey, VeilMode> queuedModes = new HashMap<>();
    private final Map<ChunkKey, VeilMode> appliedModes = new HashMap<>();
    private final Set<Integer> hiddenEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hiddenEntityUuids = ConcurrentHashMap.newKeySet();
    private final Map<Integer, UUID> hiddenEntityUuidsById = new ConcurrentHashMap<>();
    private final Set<ChunkKey> visibleChunks = ConcurrentHashMap.newKeySet();
    private volatile long lastViewRevealRefreshMillis;
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

    private void enqueue(ChunkKey chunkKey, VeilMode mode, boolean priority) {
        if (mode == VeilMode.REVEAL && !appliedModes.containsKey(chunkKey)) {
            queuedModes.remove(chunkKey);
            return;
        }
        if (mode == appliedModes.get(chunkKey)) {
            queuedModes.remove(chunkKey);
            return;
        }

        VeilMode queuedMode = queuedModes.get(chunkKey);
        if (queuedMode == mode) {
            return;
        }

        queuedModes.put(chunkKey, mode);
        VeilChunkUpdate update = new VeilChunkUpdate(chunkKey, mode, priority);
        if (priority) {
            queuedUpdates.addFirst(update);
        } else {
            queuedUpdates.addLast(update);
        }
    }

    VeilChunkUpdate poll() {
        while (!queuedUpdates.isEmpty()) {
            VeilChunkUpdate update = queuedUpdates.poll();
            if (queuedModes.get(update.chunkKey()) == update.mode()) {
                queuedModes.remove(update.chunkKey());
                return update;
            }
        }
        return null;
    }

    void markApplied(ChunkKey chunkKey, VeilMode mode) {
        appliedModes.put(chunkKey, mode);
    }

    int queuedChunkCount() {
        return queuedModes.size();
    }

    int appliedChunkCount() {
        return appliedModes.size();
    }

    void markEntityHidden(int entityId, UUID uuid) {
        hiddenEntityIds.add(entityId);
        if (uuid != null) {
            hiddenEntityUuids.add(uuid);
            hiddenEntityUuidsById.put(entityId, uuid);
        }
    }

    boolean isEntityHidden(int entityId) {
        return hiddenEntityIds.contains(entityId);
    }

    void forgetEntity(int entityId) {
        hiddenEntityIds.remove(entityId);
        UUID uuid = hiddenEntityUuidsById.remove(entityId);
        if (uuid != null) {
            hiddenEntityUuids.remove(uuid);
        }
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
        queuedModes.remove(chunkKey);
        queuedUpdates.removeIf(update -> update.chunkKey().equals(chunkKey));
    }

    void clearQueuedUpdates() {
        queuedUpdates.clear();
        queuedModes.clear();
    }

    void clearHiddenEntities() {
        hiddenEntityIds.clear();
        hiddenEntityUuids.clear();
        hiddenEntityUuidsById.clear();
    }

    void clear() {
        clearQueuedUpdates();
        appliedModes.clear();
        clearHiddenEntities();
        visibleChunks.clear();
        lastViewRevealRefreshMillis = 0L;
        lastViewRevealWorldName = null;
    }

    record LocationSnapshot(String worldName, double x, double y, double z, float yaw, float pitch) {
    }
}

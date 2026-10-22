package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class VeilEngine {
    private final Plugin plugin;
    private final VeilSettings settings;
    private final VeilMetrics metrics;
    private final Map<UUID, PlayerVeilState> states = new ConcurrentHashMap<>();
    private final Map<DirectionCacheKey, List<Vector>> viewRevealDirectionCache = new ConcurrentHashMap<>();
    // One repeating worker per online player, on that player's entity
    // scheduler. On Paper they all run on the main thread, preserving the old
    // single-worker semantics; on Folia each runs on its owning region thread.
    private final Map<UUID, VeilScheduler.TaskHandle> playerWorkers = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    static final String BYPASS_PERMISSION = "chunkveil.bypass";
    static final long BYPASS_REFRESH_MILLIS = 1000L;
    // Kept apart from PlayerVeilState: bypassed players have no veil state.
    private final Map<UUID, BypassEntry> bypassCache = new ConcurrentHashMap<>();
    private final EntityTrackingRange trackingRange = new EntityTrackingRange();
    // Folia: API hides (Paper's per-viewer hidden mark) that could not be
    // undone yet because the entity is in another region. Paper's mark lives
    // in a plain HashMap on the viewer, so showEntity must run on the
    // viewer's thread, and with a live entity also where that entity is
    // owned. The viewer's worker releases them once that holds or the entity
    // is gone. viewer UUID -> (entity UUID -> entity object).
    private final Map<UUID, Map<UUID, Entity>> pendingReleases = new ConcurrentHashMap<>();

    // Worker-driven entity scans for viewers with nothing hidden run this many
    // times less often than entity-scan-interval-millis.
    private static final int IDLE_ENTITY_SCAN_MULTIPLIER = 4;
    // Entity scans only look this far above and below the concealed band:
    // only entities inside the band can be hidden, and hidden ones that climb
    // out are caught while still within the margin (or by the hidden-entity
    // pass, which does not depend on this box).
    static final int ENTITY_BAND_MARGIN_BLOCKS = 16;
    // Folia only: every Nth scan still covers the full height, as a safety
    // net for packet-only hidden entities that left the margin between scans.
    static final int FULL_HEIGHT_ENTITY_SCAN_EVERY = 8;
    // -Dchunkveil.disableEntityBandScan=true: always scan the full height
    // (the previous behaviour; A/B tests, emergencies).
    static final boolean FULL_HEIGHT_ENTITY_SCANS = Boolean.getBoolean("chunkveil.disableEntityBandScan");
    // Folia: queued reveals skipped per player tick because their chunk is
    // owned by another region, before the tick's queue pass stops.
    static final int MAX_DEFERRED_CHUNK_UPDATES_PER_TICK = 16;

    VeilEngine(Plugin plugin, VeilSettings settings, VeilMetrics metrics) {
        this.plugin = plugin;
        this.settings = settings;
        this.metrics = metrics;
    }

    void start() {
        PlayerChunkRefresher.initialize(plugin.getLogger());
        trackingRange.prime(Bukkit.getWorlds());
        if (FastTerrain.DISABLED_BY_PROPERTY) {
            plugin.getLogger().info("Fast block access for reveal scans disabled by -Dchunkveil.disableFastTerrain.");
        } else if (FastTerrain.available()) {
            plugin.getLogger().info("Fast block access for reveal scans enabled.");
        } else {
            plugin.getLogger().warning("Fast block access for reveal scans unavailable ("
                    + FastTerrain.unavailableReason() + "); using the slower Bukkit path.");
        }
        if (VeilScheduler.folia() && settings.adaptiveScanQualityEnabled()) {
            FoliaRegionTps.initialize(plugin.getLogger());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            startWorker(player);
            VeilScheduler.runOwned(plugin, player, () -> {
                adoptSentChunks(player);
                refreshPlayer(player);
            });
        }
    }

    /**
     * A fresh engine (reload, /chunkveil enable) does not know what the client
     * already holds. Recording every chunk the client has as hidden, without
     * sending anything, means a chunk the player can see gets a normal
     * per-player reveal, which also replaces any fake data left from before.
     * Chunks the player cannot see keep whatever the client already has —
     * the same "already disclosed" rule as chunks leaving view.
     */
    private void adoptSentChunks(Player player) {
        World world = player.getWorld();
        if (stopped || !settings.isEnabledWorld(world) || !settings.hasConcealedBand(world) || isBypassed(player)) {
            return;
        }
        PlayerVeilState state = stateFor(player);
        String worldName = world.getName();
        for (Chunk chunk : player.getSentChunks()) {
            if (chunk.getWorld().equals(world)) {
                state.markChunkHiddenWithoutSending(ChunkKey.of(worldName, chunk.getX(), chunk.getZ()));
            }
        }
    }

    /**
     * Resends, per player and spread over ticks, every sent chunk in worlds
     * whose concealment settings changed. Without this the client could keep
     * fake blocks where the new settings expose real ones (e.g. a world
     * disabled or a lower cutoff), which would show up as ghost blocks.
     */
    void resendChunksInWorlds(Set<String> worldNames) {
        if (worldNames.isEmpty()) {
            return;
        }
        VeilScheduler.forEachOnlinePlayerOwned(plugin, player -> SentChunkResender.resend(
                plugin, player, world -> worldNames.contains(world.getName())));
    }

    void stop() {
        stopped = true;
        cancelAllWorkers();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerVeilState state = states.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (!VeilScheduler.folia() || Bukkit.isOwnedByCurrentRegion(player)) {
                revealHiddenEntities(player, state);
            } else if (plugin.isEnabled()) {
                VeilScheduler.runOnEntity(plugin, player, () -> revealHiddenEntities(player, state));
            }
            // Unowned player while the plugin is already disabled (Folia
            // shutdown): scheduling would throw. Documented Folia limitation —
            // those viewers keep entities hidden until re-track or rejoin.
        }
        states.clear();
        bypassCache.clear();
        pendingReleases.clear();
    }

    /** Stops background work after a security trip without revealing anything. */
    void stopConfidentialityFirst() {
        stopped = true;
        cancelAllWorkers();
        states.clear();
        bypassCache.clear();
        pendingReleases.clear();
    }

    VeilRestoreResult restoreAllPlayersToRealChunks() {
        stopped = true;
        cancelAllWorkers();

        int restoredPlayers = 0;
        AtomicInteger refreshedChunks = new AtomicInteger();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerVeilState state = states.get(player.getUniqueId());
            restoredPlayers++;
            // Inline on Paper, so the scheduled chunk count is exact; on
            // Folia each restore runs on its owning region and the returned
            // count stays at whatever was scheduled before this returns.
            VeilScheduler.runOwned(plugin, player, () -> {
                if (state != null) {
                    revealHiddenEntities(player, state);
                    state.clear();
                }
                // Per player and spread over ticks; the packet listener is
                // already removed, so these resends carry real data.
                refreshedChunks.addAndGet(SentChunkResender.resend(plugin, player, settings::isEnabledWorld));
            });
        }
        states.clear();
        bypassCache.clear();
        pendingReleases.clear();
        return new VeilRestoreResult(restoredPlayers, refreshedChunks.get());
    }

    private void startWorker(Player player) {
        if (stopped) {
            return;
        }
        UUID playerId = player.getUniqueId();
        // A null mapping result (player scheduler already retired) stores
        // nothing, so a rejoin can register a fresh worker. The retired
        // callback removes by key; it fires during entity removal, before any
        // successor worker can exist.
        playerWorkers.computeIfAbsent(playerId, ignored -> VeilScheduler.runOnEntityTimer(
                plugin,
                player,
                () -> processPlayerTick(player),
                () -> cancelWorker(playerId),
                1L,
                1L
        ));
        if (stopped) {
            // stop() may have set the flag and cancelled all workers between
            // the check above and this registration; sweep it up.
            cancelWorker(playerId);
        }
    }

    /** Single choke point creating veil state: any player with state has a worker. */
    private PlayerVeilState stateFor(Player player) {
        startWorker(player);
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVeilState());
    }

    private void cancelWorker(UUID playerId) {
        VeilScheduler.TaskHandle worker = playerWorkers.remove(playerId);
        if (worker != null) {
            worker.cancel();
        }
    }

    private void cancelAllWorkers() {
        for (UUID playerId : playerWorkers.keySet().toArray(UUID[]::new)) {
            cancelWorker(playerId);
        }
    }

    void refreshPlayer(Player player) {
        if (stopped) {
            return;
        }
        startWorker(player);
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            dropState(player);
            return;
        }

        refreshVisibleChunks(player);
        refreshEntityVisibility(player);
    }

    void resetPlayer(Player player) {
        if (stopped) {
            return;
        }
        // Join, world change and cross-world teleport: start from a live value.
        refreshBypass(player, System.currentTimeMillis());
        startWorker(player);
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            dropState(player);
            return;
        }

        PlayerVeilState state = stateFor(player);
        revealHiddenEntities(player, state);
        state.clear();
        refreshPlayer(player);
    }

    void rescanPlayer(Player player) {
        if (stopped) {
            return;
        }
        refreshBypass(player, System.currentTimeMillis());
        startWorker(player);
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            dropState(player);
            return;
        }

        PlayerVeilState state = stateFor(player);
        revealHiddenEntities(player, state);
        state.clearQueuedUpdates();
        state.clearHiddenEntities();
        // A new position: do not let the old scan's throttle skip this one.
        state.requestViewScan();
        refreshPlayer(player);
    }

    /**
     * Same-world teleport. PlayerTeleportEvent fires before the player
     * moves, so the rescan runs one tick later on the player's own scheduler
     * (which follows the player to its new region on Folia).
     */
    void rescanPlayerAfterTeleport(Player player) {
        if (stopped) {
            return;
        }
        VeilScheduler.runOnEntityLater(plugin, player, () -> {
            if (player.isOnline()) {
                rescanPlayer(player);
            }
        }, 1L);
    }

    /**
     * Quit. The state is dropped without revealing hidden entities: the
     * client is gone, and Paper keeps hideEntity state on the CraftPlayer,
     * which is discarded with the session (a rejoin gets a fresh one). The
     * reveal used to cost a showEntity per hidden entity and, on Folia, a
     * full chunk sweep, all for a player who could not see the result.
     */
    void removePlayer(Player player) {
        cancelWorker(player.getUniqueId());
        states.remove(player.getUniqueId());
        bypassCache.remove(player.getUniqueId());
        pendingReleases.remove(player.getUniqueId());
    }

    /** Drops veil state without stopping the player's worker, which keeps polling for re-adoption. */
    private void dropState(Player player) {
        PlayerVeilState state = states.remove(player.getUniqueId());
        if (state != null) {
            revealHiddenEntities(player, state);
        }
    }

    void forgetChunk(World world, int x, int z) {
        // Runs on the unloading chunk's region thread on Folia. The chunk
        // bookkeeping in PlayerVeilState is concurrent, so removing the
        // markers synchronously is safe and — crucially — ordered before any
        // reload of the same chunk can re-mark it as hidden. Deferring this
        // could erase a fresh hidden-marker set by a fast unload/reload cycle.
        ChunkKey chunkKey = ChunkKey.of(world.getName(), x, z);
        for (PlayerVeilState state : states.values()) {
            state.forgetChunk(chunkKey);
        }
    }

    void markChunkHiddenByPacketRewrite(Player player, int chunkX, int chunkZ) {
        if (stopped) {
            return;
        }
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            return;
        }

        ChunkKey chunkKey = ChunkKey.of(player.getWorld().getName(), chunkX, chunkZ);
        PlayerVeilState state = stateFor(player);
        state.markApplied(chunkKey, VeilMode.HIDE);
        if (state.isChunkVisible(chunkKey)) {
            state.enqueuePriority(chunkKey, VeilMode.REVEAL);
        }
    }

    boolean shouldHideChunk(Player player, int chunkX, int chunkZ) {
        // A world whose concealed band is empty has nothing to hide; treating
        // its chunks as hidden would make every rewrite "fail" and quarantine.
        if (!settings.isEnabledWorld(player.getWorld()) || !settings.hasConcealedBand(player.getWorld())
                || isBypassed(player)) {
            return false;
        }

        return modeFor(player, chunkX, chunkZ) == VeilMode.HIDE;
    }

    boolean shouldHideBlock(Player player, int blockX, int blockY, int blockZ) {
        // Cheapest first: most block changes are above the cutoff. The bypass
        // check happens once, inside shouldHideChunk.
        World world = player.getWorld();
        if (!settings.isEnabledWorld(world)) {
            return false;
        }
        if (blockY < settings.hideMinY(world) || blockY >= settings.hideBelowY(world)) {
            return false;
        }

        return shouldHideChunk(player, blockX >> 4, blockZ >> 4);
    }

    boolean shouldHideEntity(Player viewer, Entity entity) {
        if (entity == null || entity == viewer) {
            return false;
        }

        return shouldHideEntityAt(viewer, entity.getType(), entity.getLocation());
    }

    boolean shouldHideEntityAt(Player viewer, EntityType entityType, Location location) {
        if (location == null || location.getWorld() == null || !settings.hideEntities(location.getWorld())) {
            return false;
        }
        if (!settings.isEnabledWorld(viewer.getWorld()) || !viewer.getWorld().equals(location.getWorld())) {
            return false;
        }
        if (entityType == EntityType.PLAYER && !settings.hidePlayers(location.getWorld())) {
            return false;
        }

        int blockY = location.getBlockY();
        if (blockY < settings.hideMinY(location.getWorld()) || blockY >= settings.hideBelowY(location.getWorld())) {
            return false;
        }

        // Bypass is checked inside shouldHideChunk, after the cheap tests.
        return shouldHideChunk(viewer, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    void markEntityHidden(Player viewer, int entityId, UUID entityUuid) {
        if (stopped || !settings.hideEntities(viewer.getWorld()) || entityId < 0) {
            return;
        }

        PlayerVeilState state = stateFor(viewer);
        state.markEntityHidden(entityId, entityUuid);
    }

    void markEntityHidden(Player viewer, Entity entity) {
        if (stopped || entity == null) {
            return;
        }

        PlayerVeilState state = stateFor(viewer);
        // Already hidden through this very object: nothing to do. The same
        // UUID with another object (chunk reloaded, portal copy, new entity
        // id) is hidden again, otherwise the new id would never be marked.
        if (state.isEntityHiddenByApi(entity.getUniqueId()) && state.apiHiddenEntity(entity.getUniqueId()) == entity) {
            return;
        }

        if (VeilScheduler.canAccessEntity(entity)) {
            cancelPendingRelease(viewer.getUniqueId(), entity.getUniqueId());
            // Mark before hiding: hideEntity synchronously sends a destroy
            // packet, and the destroy handler must already see this entity as
            // API-hidden so it keeps tracking it instead of forgetting it.
            state.markEntityHiddenByApi(entity);
            viewer.hideEntity(plugin, entity);
        } else {
            // Unowned entity (Folia only): packet cancellation alone keeps it
            // concealed; its ids are marked hidden, so spawn and follow-up
            // packets for it are dropped until it can be re-validated.
            state.markEntityHidden(entity.getEntityId(), entity.getUniqueId());
        }
    }

    /**
     * Marks an entity hidden synchronously from the packet thread at the moment
     * its spawn packet is cancelled. Spawn packets are followed immediately
     * (same flush or bundle) by metadata, equipment, and similar packets;
     * deferring this mark to the next tick let those follow-ups through and
     * leaked names, held items, item-frame contents, and armor. The caller has
     * already decided the entity must be hidden in this world. Only the
     * concurrent hidden-entity sets are touched here; the per-player worker and
     * any API hide are handled by the regular next-tick bounce.
     */
    void markEntityHiddenFromPacket(UUID viewerId, int entityId, UUID entityUuid) {
        if (stopped || entityId < 0) {
            return;
        }
        PlayerVeilState state = states.get(viewerId);
        if (state == null) {
            // A spawn sent after quit must not re-create state that quit
            // removed (it would never be cleaned up). The packet itself is
            // cancelled by the caller either way.
            if (!playerWorkers.containsKey(viewerId) && !isOnline(viewerId)) {
                return;
            }
            state = states.computeIfAbsent(viewerId, ignored -> new PlayerVeilState());
        }
        state.markEntityHidden(entityId, entityUuid);
    }

    private static boolean isOnline(UUID playerId) {
        org.bukkit.Server server = Bukkit.getServer();
        // No server only in unit tests.
        return server == null || server.getPlayer(playerId) != null;
    }

    boolean isEntityHidden(Player viewer, int entityId) {
        return isEntityHidden(viewer.getUniqueId(), entityId);
    }

    /**
     * Fast-path check for entity follow-up packets (movement, metadata,
     * equipment, destroy...): with nothing hidden for this viewer none of them
     * can need cancelling, so the packet does not have to be read at all.
     * Safe because a cancelled spawn marks its entity hidden synchronously.
     */
    boolean hasHiddenEntities(Player viewer) {
        return hasHiddenEntities(viewer.getUniqueId());
    }

    boolean hasHiddenEntities(UUID viewerId) {
        PlayerVeilState state = states.get(viewerId);
        return state != null && state.hiddenEntityCount() > 0;
    }

    /** Whether entity spawns can be hidden for this viewer's current world at all. */
    boolean entityHidingActive(Player viewer) {
        World world = viewer.getWorld();
        return settings.isEnabledWorld(world) && settings.hideEntities(world) && settings.hasConcealedBand(world);
    }

    boolean isEntityHidden(UUID viewerId, int entityId) {
        PlayerVeilState state = states.get(viewerId);
        return state != null && state.isEntityHidden(entityId);
    }

    /** Called after the client was told to destroy an entity we track as hidden. */
    void forgetEntityDestroyedForClient(Player viewer, int entityId) {
        PlayerVeilState state = states.get(viewer.getUniqueId());
        if (state != null) {
            state.forgetEntityDestroyedForClient(entityId);
        }
    }

    private void processPlayerTick(Player player) {
        if (stopped) {
            return;
        }
        // The worker runs on the player's own thread (main on Paper, owning
        // region on Folia): the right place for the periodic live check.
        refreshBypassIfStale(player, System.currentTimeMillis());
        // Before the early outs: a bypassed player or one in a disabled world
        // can still have Folia API hides left to undo.
        processPendingReleases(player);
        // Cheap early outs, without timing samples: players in disabled worlds,
        // in worlds with nothing to conceal, and bypassed players have nothing
        // to do here.
        if (!settings.isEnabledWorld(player.getWorld()) || !settings.hasConcealedBand(player.getWorld())) {
            return;
        }
        if (isBypassed(player)) {
            // Bypass granted since the last tick: reveal and drop.
            if (states.containsKey(player.getUniqueId())) {
                dropState(player);
            }
            return;
        }
        if (!states.containsKey(player.getUniqueId())) {
            refreshPlayer(player);
        }
        PlayerVeilState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        metrics.countTrackedWorkerTick();

        // The view scan is driven here, not by movement events: riding a
        // vehicle fires no PlayerMoveEvent, and a player standing still while
        // mining into a cave needs the periodic force refresh. The scan is
        // rate limited and skipped when barely moved or turned. It runs
        // before the queue so its reveals go out this same tick.
        refreshVisibleChunks(player);

        // Only ticks that have queued work are timed, so the queue metric
        // averages real work instead of being diluted by empty ticks.
        if (state.queuedChunkCount() > 0) {
            long startNanos = System.nanoTime();
            try {
                processQueuedChunks(player);
            } finally {
                metrics.recordQueueProcessingNanos(System.nanoTime() - startNanos);
            }
        }

        // Entities move independently of the viewer: a mob climbing out of a
        // hidden cave, or one inside a chunk that was just revealed, must be
        // re-evaluated even while the viewer stands still.
        if (settings.hideEntities(player.getWorld())) {
            // Hidden entities need timely reveals; with none hidden, only new
            // hides are pending, which spawn-packet cancelling already covers
            // for entities entering tracking range, so scan less often.
            long interval = state.hiddenEntityCount() > 0
                    ? settings.entityScanIntervalMillis()
                    : settings.entityScanIntervalMillis() * IDLE_ENTITY_SCAN_MULTIPLIER;
            if (state.shouldRefreshEntityScan(interval, System.currentTimeMillis())) {
                runEntityScan(player, state);
            }
        }
    }

    private void processQueuedChunks(Player player) {
        PlayerVeilState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (!settings.isEnabledWorld(player.getWorld())) {
            return;
        }
        if (isBypassed(player)) {
            dropState(player);
            return;
        }

        List<VeilChunkUpdate> deferred = new ArrayList<>(0);
        try {
            drainQueuedChunks(player, state, deferred);
        } finally {
            // Re-added only now, so this tick's loop cannot meet them again.
            for (VeilChunkUpdate update : deferred) {
                state.requeueDeferred(update);
            }
        }
    }

    /** Processes queued updates, collecting those whose chunk is owned by another region into {@code deferred}. */
    private void drainQueuedChunks(Player player, PlayerVeilState state, List<VeilChunkUpdate> deferred) {
        int processedRegularChunks = 0;
        int processedPriorityChunks = 0;

        while (processedRegularChunks < settings.maxRegularChunkUpdatesPerPlayerPerTick()
                || processedPriorityChunks < settings.maxPriorityChunkUpdatesPerPlayerPerTick()) {
            VeilChunkUpdate update = state.poll();
            if (update == null) {
                break;
            }

            if (update.priority()) {
                if (processedPriorityChunks >= settings.maxPriorityChunkUpdatesPerPlayerPerTick()) {
                    state.enqueuePriority(update.chunkKey(), update.mode());
                    break;
                }
            } else if (processedRegularChunks >= settings.maxRegularChunkUpdatesPerPlayerPerTick()) {
                state.enqueue(update.chunkKey(), update.mode());
                break;
            }

            ChunkKey chunkKey = update.chunkKey();
            if (!chunkKey.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            if (update.mode() != VeilMode.REVEAL) {
                // Hiding is recorded without sending a mask; nothing queues
                // HIDE anymore, but stay correct and free if anything does.
                state.markChunkHiddenWithoutSending(chunkKey);
                continue;
            }

            World world = Bukkit.getWorld(chunkKey.worldName());
            if (world == null || !world.isChunkLoaded(chunkKey.x(), chunkKey.z())) {
                continue;
            }
            if (!VeilScheduler.canAccessChunk(world, chunkKey.x(), chunkKey.z())) {
                // Loaded but owned by a neighboring region (Folia): unlike an
                // unloaded chunk, nothing re-sends it later, so it must be
                // retried, but without blocking the updates behind it. It
                // goes back at the end of the queue after this tick; the skip
                // budget bounds the ownership checks when many are unowned.
                deferred.add(update);
                if (deferred.size() >= MAX_DEFERRED_CHUNK_UPDATES_PER_TICK) {
                    break;
                }
                continue;
            }

            Chunk chunk = world.getChunkAt(chunkKey.x(), chunkKey.z());
            if (!player.isChunkSent(chunk)) {
                continue;
            }

            if (revealChunk(player, chunk)) {
                state.markApplied(chunkKey, VeilMode.REVEAL);
                metrics.countChunkUpdatePacketSent();
                // Entities hidden inside a just-revealed chunk should appear
                // with it, not on the next interval.
                state.requestEntityScan();
            }
            if (update.priority()) {
                processedPriorityChunks++;
            } else {
                processedRegularChunks++;
            }
        }
    }

    private int effectiveScanRadius(Player player) {
        int sendViewDistance;
        try {
            sendViewDistance = player.getSendViewDistance();
        } catch (UnsupportedOperationException ignored) {
            // Some server forks do not implement per-player send distance;
            // the view distance is the next best measure.
            sendViewDistance = -1;
        }
        return effectiveScanRadius(sendViewDistance, player.getViewDistance(), player.getClientViewDistance());
    }

    /**
     * Radius, in chunks, of the area the client actually holds: the player's
     * own send distance (per-world/per-player on Paper, falling back to the
     * view distance when unset), never beyond what the client renders. The
     * old global server view distance was wrong whenever a world or player
     * used a different distance: chunks the client had stayed outside the
     * scan, so they were never revealed or kept churning between reveal and
     * forget.
     */
    static int effectiveScanRadius(int sendViewDistance, int viewDistance, int clientViewDistance) {
        int serverRadius = sendViewDistance > 0 ? sendViewDistance : viewDistance;
        if (serverRadius <= 0) {
            serverRadius = clientViewDistance;
        }
        int radius = clientViewDistance > 0 ? Math.min(serverRadius, clientViewDistance) : serverRadius;
        return Math.max(2, radius);
    }

    int trackedPlayerCount() {
        return states.size();
    }

    int queuedChunkCount() {
        int queuedChunks = 0;
        for (PlayerVeilState state : states.values()) {
            queuedChunks += state.queuedChunkCount();
        }
        return queuedChunks;
    }

    PlayerInspection inspectPlayer(Player player) {
        PlayerVeilState state = states.get(player.getUniqueId());
        ChunkKey currentChunk = ChunkKey.of(
                player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4
        );
        boolean enabledWorld = settings.isEnabledWorld(player.getWorld());
        // Live, not cached: inspect is a rare command and should show the truth.
        boolean bypassed = player.hasPermission(BYPASS_PERMISSION);
        boolean visible = state != null && state.isChunkVisible(currentChunk);
        String currentChunkState;
        if (!enabledWorld) {
            currentChunkState = "world disabled";
        } else if (bypassed) {
            currentChunkState = "bypassed";
        } else {
            currentChunkState = visible ? "revealed" : "hidden";
        }

        return new PlayerInspection(
                player.getName(),
                player.getWorld().getName(),
                bypassed,
                player.getClientViewDistance(),
                effectiveScanRadius(player),
                state == null ? 0 : state.visibleChunkCount(),
                state == null ? 0 : state.queuedChunkCount(),
                state == null ? 0 : state.hiddenEntityCount(),
                state == null ? 0L : state.lastViewRevealRefreshMillis(),
                currentChunkState,
                state == null ? 0 : state.appliedChunkCount(),
                state == null ? -1L : state.oldestEntityValidationAgeMillis()
        );
    }

    record PlayerInspection(
            String playerName,
            String worldName,
            boolean bypassed,
            int clientViewDistance,
            int effectiveScanRadius,
            int visibleChunkCount,
            int queuedChunkCount,
            int hiddenEntityCount,
            long lastViewRevealRefreshMillis,
            String currentChunkState,
            int appliedChunkCount,
            long oldestEntityValidationAgeMillis
    ) {
        long lastViewRevealAgeMillis() {
            if (lastViewRevealRefreshMillis <= 0L) {
                return -1L;
            }
            return Math.max(0L, System.currentTimeMillis() - lastViewRevealRefreshMillis);
        }
    }

    /**
     * Cached bypass permission. The packet path asks this per chunk packet and
     * per block of every multi-block change, on netty/region threads; a live
     * permission lookup each time added up to millions of lookups. The value
     * is refreshed on join, teleport and world change and by the player's own
     * worker about once a second, so a granted or revoked bypass applies
     * within ~1 s. A player without an entry yet (packets sent before the
     * join event) is checked live once and cached.
     */
    boolean isBypassed(Player player) {
        BypassEntry cached = bypassCache.get(player.getUniqueId());
        if (cached != null) {
            return cached.bypassed();
        }
        boolean bypassed = player.hasPermission(BYPASS_PERMISSION);
        // A late packet after quit must not re-create the entry quit removed.
        if (player.isOnline()) {
            BypassEntry entry = new BypassEntry(bypassed, System.currentTimeMillis());
            bypassCache.put(player.getUniqueId(), entry);
            // Quit may have removed the entry between the check and the put.
            if (!player.isOnline()) {
                bypassCache.remove(player.getUniqueId(), entry);
            }
        }
        return bypassed;
    }

    private boolean refreshBypass(Player player, long nowMillis) {
        boolean bypassed = player.hasPermission(BYPASS_PERMISSION);
        bypassCache.put(player.getUniqueId(), new BypassEntry(bypassed, nowMillis));
        return bypassed;
    }

    /** Worker-side refresh: re-checks the permission at most once per {@link #BYPASS_REFRESH_MILLIS}. */
    private void refreshBypassIfStale(Player player, long nowMillis) {
        BypassEntry entry = bypassCache.get(player.getUniqueId());
        if (entry == null || bypassEntryStale(entry.checkedAtMillis(), nowMillis)) {
            refreshBypass(player, nowMillis);
        }
    }

    static boolean bypassEntryStale(long checkedAtMillis, long nowMillis) {
        // A clock step backwards also counts as stale.
        return nowMillis - checkedAtMillis >= BYPASS_REFRESH_MILLIS || nowMillis < checkedAtMillis;
    }

    private record BypassEntry(boolean bypassed, long checkedAtMillis) {
    }

    private void refreshEntityVisibility(Player viewer) {
        if (!settings.hideEntities(viewer.getWorld()) || !settings.isEnabledWorld(viewer.getWorld())) {
            return;
        }

        PlayerVeilState state = stateFor(viewer);
        if (!state.shouldRefreshEntityScan(settings)) {
            return;
        }
        runEntityScan(viewer, state);
    }

    /** One entity visibility pass for the viewer; the caller has already claimed the scan slot. */
    private void runEntityScan(Player viewer, PlayerVeilState state) {
        World world = viewer.getWorld();
        if (!settings.isEnabledWorld(world)) {
            return;
        }
        long startNanos = System.nanoTime();
        try {
            boolean fullHeight = FULL_HEIGHT_ENTITY_SCANS || (VeilScheduler.folia()
                    && state.nextEntityScanNumber() % FULL_HEIGHT_ENTITY_SCAN_EVERY == 0);
            List<Entity> candidates = collectEntityCandidates(viewer, entityScanBlocks(viewer), fullHeight);
            int limit = settings.entityScanMaxEntitiesPerPlayer();
            List<Entity> selected;
            int deferred;
            if (candidates.size() <= limit) {
                // Everything fits: no ordering needed, no rotating window.
                selected = candidates;
                deferred = 0;
                state.entityScanCursor(0);
            } else {
                // A stable order lets the rotating window reach every entity.
                candidates.sort(Comparator.comparing(Entity::getUniqueId));
                FairScanWindow.Selection<Entity> selection = FairScanWindow.select(candidates, state.entityScanCursor(), limit);
                state.entityScanCursor(selection.nextCursor());
                selected = selection.items();
                deferred = selection.deferred();
            }
            metrics.recordEntityScanCandidates(selected.size(), deferred);
            state.recordEntityValidation(candidates, selected, deferred == 0, System.currentTimeMillis());

            Location scratch = new Location(world, 0, 0, 0);
            for (Entity entity : selected) {
                if (shouldHideEntityForScan(viewer, entity, scratch)) {
                    markEntityHidden(viewer, entity);
                } else if (state.isEntityHidden(entity.getEntityId())) {
                    revealEntity(viewer, state, entity);
                }
            }

            if (state.hiddenEntityUuids().isEmpty()) {
                return;
            }
            // Only the entity lookup differs per platform: Paper may resolve
            // any UUID globally (and forgets vanished entities), while Folia
            // sees only entities observed in owned chunks this scan (plus
            // API-hidden ones through their stored object when owned) and
            // keeps everything else hidden (fail-closed) until it is
            // reachable. The reveal decision itself is shared.
            Map<UUID, Entity> candidatesByUuid = null;
            if (VeilScheduler.folia()) {
                candidatesByUuid = new HashMap<>(candidates.size() * 2);
                for (Entity entity : candidates) {
                    candidatesByUuid.put(entity.getUniqueId(), entity);
                }
            }
            for (UUID uuid : state.hiddenEntityUuids().toArray(UUID[]::new)) {
                Entity entity;
                if (candidatesByUuid == null) {
                    entity = Bukkit.getEntity(uuid);
                    if (entity == null) {
                        // Died or its chunk unloaded.
                        releaseRemovedEntity(viewer, state, uuid);
                        continue;
                    }
                } else {
                    entity = candidatesByUuid.get(uuid);
                    if (entity == null) {
                        Entity hiddenWith = state.apiHiddenEntity(uuid);
                        if (hiddenWith != null && !hiddenWith.isValid()) {
                            // Died, unloaded or changed dimension.
                            releaseRemovedEntity(viewer, state, uuid);
                            continue;
                        }
                        if (hiddenWith != null && VeilScheduler.canAccessEntity(hiddenWith)) {
                            // Outside this scan's box (for example it climbed
                            // out of the band) but owned here: decide with it.
                            entity = hiddenWith;
                        } else {
                            // May just be in another region: stays hidden.
                            continue;
                        }
                    }
                }
                if (!entity.getWorld().equals(world) || !shouldHideEntityForScan(viewer, entity, scratch)) {
                    revealEntity(viewer, state, entity);
                }
            }
        } finally {
            metrics.recordEntityScanNanos(System.nanoTime() - startNanos);
        }
    }

    /** {@link #shouldHideEntity} for scan loops: reads the location into a reused object. */
    private boolean shouldHideEntityForScan(Player viewer, Entity entity, Location scratch) {
        if (entity == viewer) {
            return false;
        }
        return shouldHideEntityAt(viewer, entity.getType(), entity.getLocation(scratch));
    }

    /**
     * Makes a hidden entity visible to the viewer again. Must run on the
     * entity's owning thread (always the case on Paper's main thread).
     *
     * API-hidden entities only need showEntity. Packet-only hidden entities
     * (spawn packet cancelled, never hidden through the API) are different:
     * the server still believes the client tracks them, and showEntity is a
     * no-op for an entity Paper never hid, so the spawn packet would never be
     * resent. hideEntity followed by showEntity forces the tracker to re-pair
     * and send a fresh spawn. The entity is forgotten first so the resent
     * spawn and its follow-up packets are not cancelled again.
     */
    private void revealEntity(Player viewer, PlayerVeilState state, Entity entity) {
        if (!state.forgetEntityForReveal(entity.getEntityId(), entity.getUniqueId())) {
            viewer.hideEntity(plugin, entity);
        }
        viewer.showEntity(plugin, entity);
    }

    /**
     * Entities within {@code scanBlocks} horizontally and, unless
     * {@code fullHeight}, within the concealed band plus a margin vertically.
     * Tracking ranges are horizontal, so entities far below the viewer are
     * still sent to the client; but only entities inside the band can need
     * hiding, so scanning the whole height mostly walked surface mobs for
     * nothing.
     */
    private List<Entity> collectEntityCandidates(Player viewer, int scanBlocks, boolean fullHeight) {
        World world = viewer.getWorld();
        Location center = viewer.getLocation();
        double minY = world.getMinHeight();
        double maxY = world.getMaxHeight();
        if (!fullHeight) {
            minY = Math.max(minY, settings.hideMinY(world) - ENTITY_BAND_MARGIN_BLOCKS);
            maxY = Math.min(maxY, settings.hideBelowY(world) + ENTITY_BAND_MARGIN_BLOCKS);
            if (maxY <= minY) {
                return new ArrayList<>();
            }
        }
        if (!VeilScheduler.folia()) {
            java.util.Collection<Entity> nearby = world.getNearbyEntities(new BoundingBox(
                    center.getX() - scanBlocks, minY, center.getZ() - scanBlocks,
                    center.getX() + scanBlocks, maxY, center.getZ() + scanBlocks));
            return nearby instanceof ArrayList<Entity> list ? list : new ArrayList<>(nearby);
        }

        // Folia: a wide getNearbyEntities would touch unowned regions.
        // Collect from owned, loaded chunks in the same radius instead;
        // entities in unowned chunks stay hidden until their chunk is owned.
        int minChunkX = (center.getBlockX() - scanBlocks) >> 4;
        int maxChunkX = (center.getBlockX() + scanBlocks) >> 4;
        int minChunkZ = (center.getBlockZ() - scanBlocks) >> 4;
        int maxChunkZ = (center.getBlockZ() + scanBlocks) >> 4;
        List<Entity> candidates = new ArrayList<>();
        Location scratch = new Location(world, 0, 0, 0);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)
                        || !VeilScheduler.canAccessChunk(world, chunkX, chunkZ)) {
                    continue;
                }
                Entity[] entities = world.getChunkAt(chunkX, chunkZ).getEntities();
                if (fullHeight) {
                    candidates.addAll(Arrays.asList(entities));
                    continue;
                }
                for (Entity entity : entities) {
                    double y = entity.getLocation(scratch).getY();
                    if (y >= minY && y <= maxY) {
                        candidates.add(entity);
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Forgets a hidden entity that no longer exists in the world. For an
     * API-hidden one, Paper's own hidden mark (keyed by UUID, kept until
     * showEntity) is cleared first through the stored entity object; for a
     * removed entity that only clears the mark and sends nothing. Without
     * this, a mob whose chunk unloaded stayed invisible to the viewer after
     * the chunk loaded again, until they rejoined.
     */
    /** Worker side (viewer's thread): undo parked Folia API hides that are now safe to undo. */
    private void processPendingReleases(Player viewer) {
        Map<UUID, Entity> pending = pendingReleases.get(viewer.getUniqueId());
        if (pending == null) {
            return;
        }
        pending.entrySet().removeIf(entry -> {
            Entity entity = entry.getValue();
            if (!entity.isValid()) {
                // Gone: showEntity only clears the mark, nothing is sent.
                viewer.showEntity(plugin, entity);
                return true;
            }
            if (VeilScheduler.canAccessEntity(entity)) {
                // Now in this viewer's region: a normal show (Paper re-tracks
                // it; a spawn below the cutoff is cancelled again as usual).
                viewer.showEntity(plugin, entity);
                return true;
            }
            return false;
        });
        if (pending.isEmpty()) {
            pendingReleases.remove(viewer.getUniqueId(), pending);
        }
    }

    private void cancelPendingRelease(UUID viewerId, UUID entityId) {
        Map<UUID, Entity> pending = pendingReleases.get(viewerId);
        if (pending != null) {
            pending.remove(entityId);
        }
    }

    private void releaseRemovedEntity(Player viewer, PlayerVeilState state, UUID uuid) {
        Entity hiddenWith = state.apiHiddenEntity(uuid);
        state.forgetEntity(uuid);
        if (hiddenWith != null && !hiddenWith.isValid()) {
            viewer.showEntity(plugin, hiddenWith);
        }
    }

    private void revealHiddenEntities(Player viewer, PlayerVeilState state) {
        if (state.hiddenEntityUuids().isEmpty()) {
            // The common case (quit, bypass toggle): skip the Folia chunk
            // sweep entirely.
            return;
        }
        if (!VeilScheduler.folia()) {
            for (UUID uuid : state.hiddenEntityUuids().toArray(UUID[]::new)) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null) {
                    revealEntity(viewer, state, entity);
                } else {
                    releaseRemovedEntity(viewer, state, uuid);
                }
            }
            return;
        }

        // Folia: reveal what is reachable through owned chunks (candidates
        // are owned, so revealEntity is safe). An entity hidden in a
        // now-unowned region stays hidden for this viewer until it is
        // re-tracked or the viewer rejoins.
        // The full view radius, not the tracking range: this is the last
        // chance to reach API-hidden entities that wandered farther away.
        for (Entity entity : collectEntityCandidates(viewer, viewScanBlocks(viewer), true)) {
            if (state.isEntityHidden(entity.getEntityId())) {
                revealEntity(viewer, state, entity);
            }
        }
        // API hides not reached above: undo now if the entity is gone,
        // otherwise park them for the worker. Dropping them would leave
        // Paper's mark in place: invisible to this viewer until rejoin.
        for (UUID uuid : state.hiddenEntityUuids().toArray(UUID[]::new)) {
            Entity hiddenWith = state.apiHiddenEntity(uuid);
            if (hiddenWith == null) {
                continue;
            }
            if (!hiddenWith.isValid()) {
                releaseRemovedEntity(viewer, state, uuid);
            } else {
                pendingReleases.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                        .put(uuid, hiddenWith);
            }
        }
        state.clearHiddenEntities();
    }

    /** View-distance radius in blocks; the +2 chunk margin keeps hide and reveal sweeps congruent. */
    private int viewScanBlocks(Player viewer) {
        return (effectiveScanRadius(viewer) + 2) * 16;
    }

    /**
     * Horizontal entity scan radius: the view radius capped at the farthest
     * entity tracking range (+ margin). Entities beyond it are not sent to
     * the client at all; when one comes into range its spawn packet goes
     * through the spawn handler, and a hidden one is found by the next scan.
     */
    private int entityScanBlocks(Player viewer) {
        return trackingRange.scanBlocks(viewer.getWorld(), viewScanBlocks(viewer));
    }

    private VeilMode modeFor(Player player, int chunkX, int chunkZ) {
        PlayerVeilState state = states.get(player.getUniqueId());
        ChunkKey chunkKey = ChunkKey.of(player.getWorld().getName(), chunkX, chunkZ);
        return state != null && state.isChunkVisible(chunkKey) ? VeilMode.REVEAL : VeilMode.HIDE;
    }

    void refreshVisibleChunks(Player player) {
        if (stopped || !settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            return;
        }

        PlayerVeilState state = stateFor(player);
        // Runs every worker tick: skip the location lookup inside the
        // minimum interval.
        if (!state.viewRevealIntervalElapsed(System.currentTimeMillis(), settings.viewRevealRefreshMillis())) {
            return;
        }
        Location eye = player.getEyeLocation();
        if (!state.shouldRefreshViewReveal(locationSnapshot(eye), settings)) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            Location location = player.getLocation();
            Set<ChunkKey> forgottenChunks = state.forgetVisibleChunksOutside(player.getWorld().getName(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4, effectiveScanRadius(player));
            state.rememberVisibleChunks(collectVisibleChunks(player, eye, state));
            enqueueVisibilityDelta(player, state, forgottenChunks);
        } finally {
            metrics.recordRevealScanNanos(System.nanoTime() - startNanos);
        }
    }

    private PlayerVeilState.LocationSnapshot locationSnapshot(Location eye) {
        return new PlayerVeilState.LocationSnapshot(
                eye.getWorld().getName(),
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                eye.getYaw(),
                eye.getPitch()
        );
    }

    private Set<ChunkKey> collectVisibleChunks(Player player, Location eye, PlayerVeilState state) {
        Set<ChunkKey> visibleChunks = new HashSet<>();
        World world = player.getWorld();
        String worldName = world.getName();
        int maxDistanceBlocks = effectiveScanRadius(player) * 16;
        // Resolved once per scan instead of once per block per ray.
        ViewRay.Bounds bounds = new ViewRay.Bounds(
                world.getMinHeight(),
                world.getMaxHeight(),
                settings.hideMinY(world),
                settings.hideBelowY(world),
                settings.viewRevealOcclusionGraceBlocks());
        // Fast path: block states straight from chunk sections, one chunk
        // lookup per chunk instead of per voxel (shared by all rays of the
        // scan). Falls back to the Bukkit path if server internals differ.
        ViewRay.Terrain fastTerrain = FastTerrain.create(world, plugin.getLogger());
        ViewRay.Terrain terrain = fastTerrain != null ? fastTerrain : new ViewRay.Terrain() {
            @Override
            public boolean canEnterChunk(int chunkX, int chunkZ) {
                return world.isChunkLoaded(chunkX, chunkZ) && VeilScheduler.canAccessChunk(world, chunkX, chunkZ);
            }

            @Override
            public boolean isOccluding(int x, int y, int z) {
                // Material lookup without allocating a Block object.
                return world.getType(x, y, z).isOccluding();
            }
        };
        ViewRay.ChunkSink sink = new ViewRay.ChunkSink() {
            @Override
            public boolean alreadyVisible(int chunkX, int chunkZ) {
                // The live concurrent set: no per-scan snapshot copy.
                return state.isChunkVisible(ChunkKey.of(worldName, chunkX, chunkZ));
            }

            @Override
            public void reveal(int chunkX, int chunkZ) {
                visibleChunks.add(ChunkKey.of(worldName, chunkX, chunkZ));
            }
        };
        double originX = eye.getX();
        double originY = eye.getY();
        double originZ = eye.getZ();
        for (Vector direction : buildViewRevealDirections(eye.getYaw())) {
            ViewRay.walk(originX, originY, originZ, direction.getX(), direction.getY(), direction.getZ(),
                    maxDistanceBlocks, bounds, terrain, sink);
        }

        return visibleChunks;
    }

    private void enqueueVisibilityDelta(Player player, PlayerVeilState state, Set<ChunkKey> forgottenChunks) {
        // Chunks that left the view are recorded as hidden without sending a
        // mask (see PlayerVeilState#markChunkHiddenWithoutSending). This used
        // to rebuild the whole concealed band block by block on the main
        // thread, ~13 ms per chunk, for no security gain.
        for (ChunkKey chunkKey : forgottenChunks) {
            state.markChunkHiddenWithoutSending(chunkKey);
        }

        // Only visible chunks the client still holds fake: newly seen ones,
        // and earlier reveals that did not go through (not loaded or not sent
        // yet, a failed resend), which are retried this way. Already revealed
        // chunks cost one map lookup instead of an isChunkLoaded and enqueue.
        World world = player.getWorld();
        String worldName = world.getName();
        for (ChunkKey chunkKey : state.visibleChunksAwaitingReveal(worldName)) {
            if (world.isChunkLoaded(chunkKey.x(), chunkKey.z())) {
                state.enqueuePriority(chunkKey, VeilMode.REVEAL);
            }
        }
    }

    private List<Vector> buildViewRevealDirections(float playerYaw) {
        int yawBucket = yawBucket(playerYaw);
        RayProfile rayProfile = rayProfile();
        DirectionCacheKey cacheKey = new DirectionCacheKey(
                yawBucket,
                rayProfile.frontHorizontalRays(),
                rayProfile.sideHorizontalRays(),
                rayProfile.backHorizontalRays(),
                rayProfile.verticalRays()
        );
        return viewRevealDirectionCache.computeIfAbsent(cacheKey, this::buildViewRevealDirectionsForKey);
    }

    private List<Vector> buildViewRevealDirectionsForKey(DirectionCacheKey cacheKey) {
        int horizontalRays = cacheKey.frontHorizontalRays()
                + cacheKey.sideHorizontalRays() * 2
                + cacheKey.backHorizontalRays();
        List<Vector> directions = new ArrayList<>(horizontalRays * cacheKey.verticalRays());
        float bucketYaw = cacheKey.yawBucket() * settings.viewRevealYawCacheBucketDegrees();
        addViewRevealDirections(directions, bucketYaw, -70.0D, 70.0D, cacheKey.frontHorizontalRays(), cacheKey.verticalRays());
        addViewRevealDirections(directions, bucketYaw, 70.0D, 145.0D, cacheKey.sideHorizontalRays(), cacheKey.verticalRays());
        addViewRevealDirections(directions, bucketYaw, -145.0D, -70.0D, cacheKey.sideHorizontalRays(), cacheKey.verticalRays());
        addViewRevealDirections(directions, bucketYaw, 145.0D, 215.0D, cacheKey.backHorizontalRays(), cacheKey.verticalRays());
        return List.copyOf(directions);
    }

    private RayProfile rayProfile() {
        if (!settings.adaptiveScanQualityEnabled()) {
            return new RayProfile(
                    settings.viewRevealFrontHorizontalRays(),
                    settings.viewRevealSideHorizontalRays(),
                    settings.viewRevealBackHorizontalRays(),
                    settings.viewRevealVerticalRays()
            );
        }

        double tps = recentTps();
        double threshold = settings.adaptiveScanReduceBelowTps();
        if (tps >= threshold) {
            return new RayProfile(
                    settings.viewRevealFrontHorizontalRays(),
                    settings.viewRevealSideHorizontalRays(),
                    settings.viewRevealBackHorizontalRays(),
                    settings.viewRevealVerticalRays()
            );
        }

        double scale = Math.max(0.0D, Math.min(1.0D, tps / threshold));
        return new RayProfile(
                scaledRayCount(settings.viewRevealFrontHorizontalRays(), settings.adaptiveScanMinimumFrontHorizontalRays(), scale),
                scaledRayCount(settings.viewRevealSideHorizontalRays(), settings.adaptiveScanMinimumSideHorizontalRays(), scale),
                scaledRayCount(settings.viewRevealBackHorizontalRays(), settings.adaptiveScanMinimumBackHorizontalRays(), scale),
                scaledRayCount(settings.viewRevealVerticalRays(), settings.adaptiveScanMinimumVerticalRays(), scale)
        );
    }

    private double recentTps() {
        if (VeilScheduler.folia()) {
            // No global TPS on Folia: the scan runs on the player's region
            // thread, so use that region's TPS (falls back to 20 if unknown).
            return FoliaRegionTps.currentRegionTps(plugin.getLogger());
        }
        double[] tps = Bukkit.getTPS();
        return tps.length == 0 ? 20.0D : Math.min(20.0D, Math.max(0.0D, tps[0]));
    }

    private int scaledRayCount(int configured, int minimum, double scale) {
        int boundedMinimum = Math.min(configured, minimum);
        int reducedRange = configured - boundedMinimum;
        return boundedMinimum + (int) Math.round(reducedRange * scale);
    }

    private int yawBucket(float playerYaw) {
        int bucketDegrees = settings.viewRevealYawCacheBucketDegrees();
        int normalizedYaw = Math.floorMod(Math.round(playerYaw), 360);
        int bucketCount = Math.max(1, Math.floorDiv(360 + bucketDegrees - 1, bucketDegrees));
        return Math.floorMod(Math.floorDiv(normalizedYaw + bucketDegrees / 2, bucketDegrees), bucketCount);
    }

    private void addViewRevealDirections(List<Vector> directions, float playerYaw, double minYawOffset, double maxYawOffset, int horizontalRays, int verticalRays) {
        for (int horizontal = 0; horizontal < horizontalRays; horizontal++) {
            double yawOffset = horizontalOffset(horizontal, horizontalRays, minYawOffset, maxYawOffset);
            double yaw = Math.toRadians(playerYaw + yawOffset);
            for (int vertical = 0; vertical < verticalRays; vertical++) {
                double pitch = Math.toRadians(verticalPitch(vertical, verticalRays));
                double horizontalLength = Math.cos(pitch);
                directions.add(new Vector(
                        -Math.sin(yaw) * horizontalLength,
                        -Math.sin(pitch),
                        Math.cos(yaw) * horizontalLength
                ));
            }
        }
    }

    private double horizontalOffset(int index, int total, double minYawOffset, double maxYawOffset) {
        if (total <= 1) {
            return (minYawOffset + maxYawOffset) / 2.0D;
        }
        return minYawOffset + (maxYawOffset - minYawOffset) * index / (total - 1);
    }

    private float verticalPitch(int index, int total) {
        if (total <= 1) {
            return 0.0F;
        }

        return -89.0F + 178.0F * index / (total - 1);
    }

    /** Resends a revealed chunk so the client receives its real data. */
    private boolean revealChunk(Player player, Chunk chunk) {
        boolean refreshed = resendChunkTo(player, chunk.getWorld(), chunk.getX(), chunk.getZ());
        if (refreshed) {
            scheduleFollowUpRevealRefresh(player, chunk);
        }
        return refreshed;
    }

    /**
     * Resends one chunk to one player. The broadcast fallback resends it to
     * every player tracking it, which is what made reveals scale with the
     * number of nearby players.
     */
    private boolean resendChunkTo(Player player, World world, int chunkX, int chunkZ) {
        if (PlayerChunkRefresher.refresh(plugin.getLogger(), player, world, chunkX, chunkZ)) {
            return true;
        }
        return world.refreshChunk(chunkX, chunkZ);
    }

    private void scheduleFollowUpRevealRefresh(Player player, Chunk chunk) {
        UUID playerId = player.getUniqueId();
        String worldName = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        VeilScheduler.runOnEntityLater(plugin, player, () -> {
            World world = Bukkit.getWorld(worldName);
            if (!player.isOnline() || world == null || !player.getWorld().equals(world)) {
                return;
            }
            if (!settings.isEnabledWorld(world) || isBypassed(player)) {
                return;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)
                    || !VeilScheduler.canAccessChunk(world, chunkX, chunkZ)) {
                return;
            }

            ChunkKey chunkKey = ChunkKey.of(worldName, chunkX, chunkZ);
            PlayerVeilState state = states.get(playerId);
            if (state == null || !state.isChunkVisible(chunkKey)) {
                return;
            }

            Chunk refreshedChunk = world.getChunkAt(chunkX, chunkZ);
            if (player.isChunkSent(refreshedChunk)) {
                resendChunkTo(player, world, chunkX, chunkZ);
            }
        }, 2L);
    }

    record RayProfile(int frontHorizontalRays, int sideHorizontalRays, int backHorizontalRays, int verticalRays) {
    }

    record DirectionCacheKey(int yawBucket, int frontHorizontalRays, int sideHorizontalRays, int backHorizontalRays, int verticalRays) {
    }
}

package com.dekaeyman.chunkveil;

import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;

final class VeilEngine {
    private final Plugin plugin;
    private final VeilSettings settings;
    private final VeilMetrics metrics;
    private final Map<UUID, PlayerVeilState> states = new ConcurrentHashMap<>();
    private final Map<Material, BlockData> fakeBlockData = new ConcurrentHashMap<>();
    private final Map<DirectionCacheKey, List<Vector>> viewRevealDirectionCache = new ConcurrentHashMap<>();
    private BukkitTask workerTask;

    VeilEngine(Plugin plugin, VeilSettings settings, VeilMetrics metrics) {
        this.plugin = plugin;
        this.settings = settings;
        this.metrics = metrics;
    }

    void start() {
        workerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processQueuedChunks, 1L, 1L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    void stop() {
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerVeilState state = states.get(player.getUniqueId());
            if (state != null) {
                revealHiddenEntities(player, state);
            }
        }
        states.clear();
    }

    /** Stops background work after a security trip without revealing anything. */
    void stopConfidentialityFirst() {
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }
        states.clear();
    }

    VeilRestoreResult restoreAllPlayersToRealChunks() {
        if (workerTask != null) {
            workerTask.cancel();
            workerTask = null;
        }

        int restoredPlayers = 0;
        int refreshedChunks = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerVeilState state = states.get(player.getUniqueId());
            if (state != null) {
                revealHiddenEntities(player, state);
                state.clear();
            }
            restoredPlayers++;
            refreshedChunks += refreshSentChunks(player);
        }
        states.clear();
        return new VeilRestoreResult(restoredPlayers, refreshedChunks);
    }

    void refreshPlayer(Player player) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            PlayerVeilState state = states.remove(player.getUniqueId());
            if (state != null) {
                revealHiddenEntities(player, state);
            }
            return;
        }

        refreshVisibleChunks(player);
        refreshEntityVisibility(player);
    }

    void resetPlayer(Player player) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            PlayerVeilState state = states.remove(player.getUniqueId());
            if (state != null) {
                revealHiddenEntities(player, state);
            }
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVeilState());
        revealHiddenEntities(player, state);
        state.clear();
        refreshPlayer(player);
    }

    void rescanPlayer(Player player) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            PlayerVeilState state = states.remove(player.getUniqueId());
            if (state != null) {
                revealHiddenEntities(player, state);
            }
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVeilState());
        revealHiddenEntities(player, state);
        state.clearQueuedUpdates();
        state.clearHiddenEntities();
        refreshPlayer(player);
    }

    void removePlayer(Player player) {
        PlayerVeilState state = states.remove(player.getUniqueId());
        if (state != null) {
            revealHiddenEntities(player, state);
        }
    }

    void forgetChunk(World world, int x, int z) {
        ChunkKey chunkKey = ChunkKey.of(world.getName(), x, z);
        for (PlayerVeilState state : states.values()) {
            state.forgetChunk(chunkKey);
        }
    }

    void markChunkHiddenByPacketRewrite(Player player, int chunkX, int chunkZ) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            return;
        }

        ChunkKey chunkKey = ChunkKey.of(player.getWorld().getName(), chunkX, chunkZ);
        PlayerVeilState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVeilState());
        state.markApplied(chunkKey, VeilMode.HIDE);
        if (state.isChunkVisible(chunkKey)) {
            state.enqueuePriority(chunkKey, VeilMode.REVEAL);
        }
    }

    boolean shouldHideChunk(Player player, int chunkX, int chunkZ) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            return false;
        }

        return modeFor(player, chunkX, chunkZ) == VeilMode.HIDE;
    }

    boolean shouldHideBlock(Player player, int blockX, int blockY, int blockZ) {
        if (!settings.isEnabledWorld(player.getWorld()) || isBypassed(player)) {
            return false;
        }
        if (blockY < settings.minY(player.getWorld()) || blockY >= settings.hideBelowY(player.getWorld())) {
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
        if (isBypassed(viewer)) {
            return false;
        }
        if (!settings.isEnabledWorld(viewer.getWorld()) || !viewer.getWorld().equals(location.getWorld())) {
            return false;
        }
        if (entityType == EntityType.PLAYER && !settings.hidePlayers(location.getWorld())) {
            return false;
        }

        int blockY = location.getBlockY();
        if (blockY < settings.minY(location.getWorld()) || blockY >= settings.hideBelowY(location.getWorld())) {
            return false;
        }

        return shouldHideChunk(viewer, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    void markEntityHidden(Player viewer, int entityId, UUID entityUuid) {
        if (!settings.hideEntities(viewer.getWorld()) || entityId < 0) {
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(viewer.getUniqueId(), ignored -> new PlayerVeilState());
        state.markEntityHidden(entityId, entityUuid);
    }

    void markEntityHidden(Player viewer, Entity entity) {
        if (entity == null) {
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(viewer.getUniqueId(), ignored -> new PlayerVeilState());
        if (state.isEntityHidden(entity.getEntityId())) {
            return;
        }

        state.markEntityHidden(entity.getEntityId(), entity.getUniqueId());
        viewer.hideEntity(plugin, entity);
    }

    boolean isEntityHidden(Player viewer, int entityId) {
        PlayerVeilState state = states.get(viewer.getUniqueId());
        return state != null && state.isEntityHidden(entityId);
    }

    void forgetEntity(Player viewer, int entityId) {
        PlayerVeilState state = states.get(viewer.getUniqueId());
        if (state != null) {
            state.forgetEntity(entityId);
        }
    }

    private void processQueuedChunks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!states.containsKey(player.getUniqueId())
                    && settings.isEnabledWorld(player.getWorld())
                    && !isBypassed(player)) {
                refreshPlayer(player);
            }
        }

        long startNanos = System.nanoTime();
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                int processedRegularChunks = 0;
                int processedPriorityChunks = 0;

                PlayerVeilState state = states.get(player.getUniqueId());
                if (state == null) {
                    continue;
                }
                if (!settings.isEnabledWorld(player.getWorld())) {
                    continue;
                }
                if (isBypassed(player)) {
                    removePlayer(player);
                    continue;
                }

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

                    World world = Bukkit.getWorld(chunkKey.worldName());
                    if (world == null || !world.isChunkLoaded(chunkKey.x(), chunkKey.z())) {
                        continue;
                    }

                    Chunk chunk = world.getChunkAt(chunkKey.x(), chunkKey.z());
                    if (!player.isChunkSent(chunk)) {
                        continue;
                    }

                    if (sendChunkMode(player, chunk, update.mode())) {
                        state.markApplied(chunkKey, update.mode());
                        metrics.countChunkUpdatePacketSent();
                    }
                    if (update.priority()) {
                        processedPriorityChunks++;
                    } else {
                        processedRegularChunks++;
                    }
                }
            }
        } finally {
            metrics.recordQueueProcessingNanos(System.nanoTime() - startNanos);
        }
    }

    private int effectiveScanRadius(Player player) {
        int serverViewDistance = plugin.getServer().getViewDistance();
        int clientViewDistance = player.getClientViewDistance();
        return Math.min(serverViewDistance, clientViewDistance);
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
        boolean bypassed = isBypassed(player);
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

    private boolean isBypassed(Player player) {
        return player.hasPermission("chunkveil.bypass");
    }

    private void refreshEntityVisibility(Player viewer) {
        if (!settings.hideEntities(viewer.getWorld()) || !settings.isEnabledWorld(viewer.getWorld())) {
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(viewer.getUniqueId(), ignored -> new PlayerVeilState());
        if (!state.shouldRefreshEntityScan(settings)) {
            return;
        }
        long startNanos = System.nanoTime();
        int scanBlocks = (effectiveScanRadius(viewer) + 2) * 16;
        try {
            List<Entity> candidates = new ArrayList<>(viewer.getWorld().getNearbyEntities(
                    viewer.getLocation(), scanBlocks, scanBlocks, scanBlocks));
            candidates.sort(Comparator.comparing(Entity::getUniqueId));
            FairScanWindow.Selection<Entity> selection = FairScanWindow.select(
                    candidates, state.entityScanCursor(), settings.entityScanMaxEntitiesPerPlayer());
            state.entityScanCursor(selection.nextCursor());
            metrics.recordEntityScanCandidates(selection.items().size(), selection.deferred());
            state.recordEntityValidationCandidates(
                    candidates.stream().map(Entity::getUniqueId).toList(),
                    selection.items().stream().map(Entity::getUniqueId).toList());
            for (Entity entity : selection.items()) {
                if (shouldHideEntity(viewer, entity)) {
                    markEntityHidden(viewer, entity);
                } else {
                    if (state.isEntityHidden(entity.getEntityId())) {
                        state.forgetEntity(entity.getEntityId());
                        viewer.showEntity(plugin, entity);
                    }
                }
            }

            Set<UUID> hiddenUuids = state.hiddenEntityUuids();
            for (UUID uuid : hiddenUuids.toArray(UUID[]::new)) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null) {
                    state.forgetEntity(uuid);
                    continue;
                }
                if (!entity.getWorld().equals(viewer.getWorld()) || !shouldHideEntity(viewer, entity)) {
                    state.forgetEntity(entity.getEntityId());
                    viewer.showEntity(plugin, entity);
                }
            }
        } finally {
            metrics.recordEntityScanNanos(System.nanoTime() - startNanos);
        }
    }

    private void revealHiddenEntities(Player viewer, PlayerVeilState state) {
        for (UUID uuid : state.hiddenEntityUuids().toArray(UUID[]::new)) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                viewer.showEntity(plugin, entity);
                state.forgetEntity(entity.getEntityId());
            }
        }
    }

    private int refreshSentChunks(Player player) {
        int refreshedChunks = 0;
        for (Chunk chunk : player.getSentChunks()) {
            World world = chunk.getWorld();
            if (!settings.isEnabledWorld(world)) {
                continue;
            }
            if (!world.isChunkLoaded(chunk.getX(), chunk.getZ()) || !player.isChunkSent(chunk)) {
                continue;
            }
            if (world.refreshChunk(chunk.getX(), chunk.getZ())) {
                refreshedChunks++;
            }
        }
        return refreshedChunks;
    }

    private VeilMode modeFor(Player player, int chunkX, int chunkZ) {
        PlayerVeilState state = states.get(player.getUniqueId());
        ChunkKey chunkKey = ChunkKey.of(player.getWorld().getName(), chunkX, chunkZ);
        return state != null && state.isChunkVisible(chunkKey) ? VeilMode.REVEAL : VeilMode.HIDE;
    }

    void refreshVisibleChunks(Player player) {
        if (!settings.isEnabledWorld(player.getWorld())) {
            return;
        }

        PlayerVeilState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerVeilState());
        Location eye = player.getEyeLocation();
        if (!state.shouldRefreshViewReveal(locationSnapshot(eye), settings)) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            Set<ChunkKey> forgottenChunks = state.forgetVisibleChunksOutside(player.getWorld().getName(), player.getLocation().getChunk().getX(), player.getLocation().getChunk().getZ(), effectiveScanRadius(player));
            state.rememberVisibleChunks(collectVisibleChunks(player, eye, state.visibleChunkSnapshot()));
            Set<ChunkKey> visibleChunks = state.visibleChunkSnapshot();
            enqueueVisibilityDelta(player, state, visibleChunks, forgottenChunks);
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

    private Set<ChunkKey> collectVisibleChunks(Player player, Location eye, Set<ChunkKey> alreadyVisibleChunks) {
        Set<ChunkKey> visibleChunks = new HashSet<>();
        int maxDistanceBlocks = effectiveScanRadius(player) * 16;
        for (Vector direction : buildViewRevealDirections(eye.getYaw())) {
            collectVisibleChunksOnRay(player.getWorld(), eye, direction, maxDistanceBlocks, visibleChunks, alreadyVisibleChunks);
        }

        return visibleChunks;
    }

    private void collectVisibleChunksOnRay(
            World world,
            Location eye,
            Vector direction,
            int maxDistanceBlocks,
            Set<ChunkKey> visibleChunks,
            Set<ChunkKey> alreadyVisibleChunks
    ) {
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        int previousZ = Integer.MIN_VALUE;
        int checkedChunkX = Integer.MIN_VALUE;
        int checkedChunkZ = Integer.MIN_VALUE;
        int addedChunkX = Integer.MIN_VALUE;
        int addedChunkZ = Integer.MIN_VALUE;
        boolean currentChunkAlreadyVisible = false;
        String worldName = world.getName();
        boolean reachedHiddenLayer = false;
        int occludingBlocks = 0;
        for (double distance = 0.0D; distance <= maxDistanceBlocks; distance += 1.0D) {
            int blockX = NumberConversions.floor(eye.getX() + direction.getX() * distance);
            int blockY = NumberConversions.floor(eye.getY() + direction.getY() * distance);
            int blockZ = NumberConversions.floor(eye.getZ() + direction.getZ() * distance);
            if (blockX == previousX && blockY == previousY && blockZ == previousZ) {
                continue;
            }
            previousX = blockX;
            previousY = blockY;
            previousZ = blockZ;

            if (blockY < world.getMinHeight() || blockY >= world.getMaxHeight()) {
                break;
            }
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            if (chunkX != checkedChunkX || chunkZ != checkedChunkZ) {
                checkedChunkX = chunkX;
                checkedChunkZ = chunkZ;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    break;
                }
                currentChunkAlreadyVisible = alreadyVisibleChunks.contains(ChunkKey.of(worldName, chunkX, chunkZ));
            }

            Block block = world.getBlockAt(blockX, blockY, blockZ);
            if (blockY >= settings.minY(world) && blockY < settings.hideBelowY(world)) {
                reachedHiddenLayer = true;
                if (chunkX != addedChunkX || chunkZ != addedChunkZ) {
                    if (!currentChunkAlreadyVisible) {
                        visibleChunks.add(ChunkKey.of(worldName, chunkX, chunkZ));
                    }
                    addedChunkX = chunkX;
                    addedChunkZ = chunkZ;
                }
            } else if (reachedHiddenLayer) {
                if (chunkX != addedChunkX || chunkZ != addedChunkZ) {
                    if (!currentChunkAlreadyVisible) {
                        visibleChunks.add(ChunkKey.of(worldName, chunkX, chunkZ));
                    }
                    addedChunkX = chunkX;
                    addedChunkZ = chunkZ;
                }
            }

            if (block.getType().isOccluding()) {
                occludingBlocks++;
                if (occludingBlocks > settings.viewRevealOcclusionGraceBlocks()) {
                    break;
                }
            } else {
                occludingBlocks = 0;
            }
        }
    }

    private void enqueueVisibilityDelta(Player player, PlayerVeilState state, Set<ChunkKey> visibleChunks, Set<ChunkKey> forgottenChunks) {
        for (ChunkKey chunkKey : forgottenChunks) {
            if (!chunkKey.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            if (player.getWorld().isChunkLoaded(chunkKey.x(), chunkKey.z())) {
                state.enqueuePriority(chunkKey, VeilMode.HIDE);
            }
        }

        for (ChunkKey chunkKey : visibleChunks) {
            if (!chunkKey.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            if (player.getWorld().isChunkLoaded(chunkKey.x(), chunkKey.z())) {
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

    private boolean sendChunkMode(Player player, Chunk chunk, VeilMode mode) {
        if (mode == VeilMode.REVEAL) {
            boolean refreshed = chunk.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
            if (refreshed) {
                scheduleFollowUpRevealRefresh(player, chunk);
            }
            return refreshed;
        }

        long startNanos = System.nanoTime();
        int minY = Math.max(chunk.getWorld().getMinHeight(), settings.minY(chunk.getWorld()));
        int maxY = Math.min(settings.hideBelowY(chunk.getWorld()), chunk.getWorld().getMaxHeight());
        if (minY >= maxY) {
            return false;
        }

        Material defaultFakeBlock = settings.defaultFakeBlock(chunk.getWorld());
        BlockData defaultFakeBlockData = fakeBlockData.computeIfAbsent(defaultFakeBlock, Material::createBlockData);
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        boolean sent = false;

        for (int sectionMinY = minY; sectionMinY < maxY; sectionMinY += 16) {
            int sectionMaxY = Math.min(sectionMinY + 16, maxY);
            Map<Position, BlockData> changes = new HashMap<>(16 * 16 * (sectionMaxY - sectionMinY));

            for (int y = sectionMinY; y < sectionMaxY; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Block block = chunk.getBlock(x, y, z);
                        Position position = Position.block(baseX + x, y, baseZ + z);
                        Material replacement = settings.replacementFor(chunk.getWorld(), block.getType());
                        if (replacement != null) {
                            BlockData blockData = replacement == defaultFakeBlock
                                    ? defaultFakeBlockData
                                    : fakeBlockData.computeIfAbsent(replacement, Material::createBlockData);
                            changes.put(position, blockData);
                        }
                    }
                }
            }

            if (!changes.isEmpty()) {
                player.sendMultiBlockChange(changes);
                sent = true;
            }
        }
        metrics.recordChunkUpdateMaskNanos(System.nanoTime() - startNanos);
        return sent;
    }

    private void scheduleFollowUpRevealRefresh(Player player, Chunk chunk) {
        UUID playerId = player.getUniqueId();
        String worldName = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            World world = Bukkit.getWorld(worldName);
            if (onlinePlayer == null || world == null || !onlinePlayer.getWorld().equals(world)) {
                return;
            }
            if (!settings.isEnabledWorld(world) || isBypassed(onlinePlayer)) {
                return;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }

            ChunkKey chunkKey = ChunkKey.of(worldName, chunkX, chunkZ);
            PlayerVeilState state = states.get(playerId);
            if (state == null || !state.isChunkVisible(chunkKey)) {
                return;
            }

            Chunk refreshedChunk = world.getChunkAt(chunkX, chunkZ);
            if (onlinePlayer.isChunkSent(refreshedChunk)) {
                world.refreshChunk(chunkX, chunkZ);
            }
        }, 2L);
    }

    record RayProfile(int frontHorizontalRays, int sideHorizontalRays, int backHorizontalRays, int verticalRays) {
    }

    record DirectionCacheKey(int yawBucket, int frontHorizontalRays, int sideHorizontalRays, int backHorizontalRays, int verticalRays) {
    }
}

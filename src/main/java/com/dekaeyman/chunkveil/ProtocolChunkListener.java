package com.dekaeyman.chunkveil;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

final class ProtocolChunkListener {
    private final ChunkVeilPlugin plugin;
    private final ProtocolManager protocolManager;
    private final VeilMetrics metrics;
    private final VeilSettings settings;
    private final boolean sectionBlockCountIsLeInt;
    private final AtomicBoolean packetBlockRewriteBroken = new AtomicBoolean();
    private final AtomicBoolean multiBlockChangeRewriteBroken = new AtomicBoolean();
    private final AtomicBoolean failClosedScheduled = new AtomicBoolean();
    private final PacketSecurityState securityState = new PacketSecurityState();
    private final PacketProtectionHealth protectionHealth = new PacketProtectionHealth();
    private final Map<Material, ChunkPacketBlockRewriter> blockRewriters = new ConcurrentHashMap<>();
    private final Map<Material, WrappedBlockData> fakeBlockData = new ConcurrentHashMap<>();
    private int airBlockStateId;
    private volatile String lastChunkPacketFormat;
    private volatile String lastChunkWorld;

    private ProtocolChunkListener(ChunkVeilPlugin plugin, ProtocolManager protocolManager, VeilMetrics metrics, VeilSettings settings) {
        this.plugin = plugin;
        this.protocolManager = protocolManager;
        this.metrics = metrics;
        this.settings = settings;
        this.sectionBlockCountIsLeInt = sectionBlockCountIsLeInt();
    }

    static ProtocolChunkListener start(ChunkVeilPlugin plugin, VeilEngine veilEngine, VeilSettings settings, VeilMetrics metrics) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        if (protocolManager == null) {
            throw new IllegalStateException("ProtocolLib did not provide a protocol manager.");
        }
        if (!PacketType.Play.Server.MAP_CHUNK.isSupported()) {
            throw new IllegalStateException("ProtocolLib does not support outgoing chunk packets on this server version.");
        }

        ProtocolChunkListener listener = new ProtocolChunkListener(plugin, protocolManager, metrics, settings);
        listener.initializeBlockRewriter(settings);
        listener.register(veilEngine, settings);
        plugin.getLogger().info("ProtocolLib chunk listener enabled with fail-closed packet rewriting.");
        return listener;
    }

    private void initializeBlockRewriter(VeilSettings settings) {
        try {
            this.airBlockStateId = NmsBlockStateIds.defaultStateId(Material.AIR);
            for (VeilWorldSettings worldSettings : settings.worlds().values()) {
                rewriterFor(worldSettings.defaultFakeBlock());
            }
            plugin.getLogger().info("Packet section rewrite enabled with per-world fake block support.");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not initialize packet section rewrite: " + exception.getMessage(), exception);
        }
    }

    void stop() {
        protocolManager.removePacketListeners(plugin);
    }

    boolean packetRewriteActive() {
        return !packetBlockRewriteBroken.get() && !securityState.isTripped();
    }

    boolean securityTripped() {
        return securityState.isTripped();
    }

    String securityTripPath() {
        PacketSecurityState.Failure failure = securityState.failure();
        return failure == null ? null : failure.path().name();
    }

    PacketProtectionHealth.Snapshot health(PacketSecurityState.ProtectedPath... paths) {
        return protectionHealth.summarize(paths);
    }

    String lastChunkPacketFormat() {
        return lastChunkPacketFormat;
    }

    String lastChunkWorld() {
        return lastChunkWorld;
    }

    private void register(VeilEngine veilEngine, VeilSettings settings) {
        protocolManager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                supportedServerPackets()
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (securityState.cancelIfTripped(() -> event.setCancelled(true))) {
                    metrics.countSecurityPacketCancelled();
                    return;
                }
                if (isEntitySpawnPacket(event.getPacketType())) {
                    cancelHiddenEntitySpawn(event, veilEngine);
                    return;
                }
                if (isEntityFollowUpPacket(event.getPacketType())) {
                    cancelHiddenEntityPacket(event, veilEngine);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
                    rewriteBlockChange(event, veilEngine);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
                    rewriteMultiBlockChange(event, veilEngine);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.TILE_ENTITY_DATA) {
                    cancelHiddenBlockEntityUpdate(event, veilEngine);
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
                    if (settings.cancelExplosionsInHiddenZones()) {
                        cancelHiddenExplosion(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.WORLD_EVENT) {
                    if (settings.cancelWorldEventsInHiddenZones()) {
                        cancelHiddenWorldEvent(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
                    if (settings.cancelBlockCrackInHiddenZones()) {
                        cancelHiddenBlockCrack(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.CUSTOM_SOUND_EFFECT
                        || event.getPacketType() == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                    if (settings.cancelPositionalSoundsInHiddenZones()) {
                        cancelHiddenPositionalSound(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.WORLD_PARTICLES) {
                    if (settings.cancelParticlesInHiddenZones()) {
                        cancelHiddenParticle(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.ADD_VIBRATION_SIGNAL) {
                    if (settings.cancelVibrationsInHiddenZones()) {
                        cancelHiddenVibration(event, veilEngine);
                    }
                    return;
                }
                if (event.getPacketType() == PacketType.Play.Server.LIGHT_UPDATE) {
                    if (settings.sanitizeLightInHiddenZones()) {
                        sanitizeStandaloneLightUpdate(event, veilEngine);
                    }
                    return;
                }

                Player player = event.getPlayer();
                int chunkX;
                int chunkZ;

                try {
                    chunkX = event.getPacket().getIntegers().read(0);
                    chunkZ = event.getPacket().getIntegers().read(1);
                } catch (RuntimeException exception) {
                    trip(event, PacketSecurityState.ProtectedPath.CHUNK,
                            packetCompatibilityFailure("Could not read outgoing chunk packet coordinates", exception));
                    return;
                }

                boolean hidden = veilEngine.shouldHideChunk(player, chunkX, chunkZ);
                if (!hidden) {
                    metrics.countChunkPacket(false, false);
                    return;
                }

                // Work on a deep clone and publish it only after block states,
                // block entities, and light have all succeeded. A failure can
                // therefore never send a partially protected chunk.
                PacketContainer originalPacket = event.getPacket();
                try {
                    event.setPacket(originalPacket.deepClone());
                    int rewrittenSections = rewriteHiddenChunkSections(event, player, chunkX, chunkZ, settings);
                    if (rewrittenSections <= 0) {
                        throw new IllegalArgumentException("no concealed chunk sections were rewritten");
                    }
                    stripHiddenBlockEntities(event, settings.hideBelowY(player.getWorld()));
                    if (settings.sanitizeLightInHiddenZones()) {
                        sanitizeLightData(event, player.getWorld());
                        protectionHealth.observed(PacketSecurityState.ProtectedPath.LIGHT);
                    }
                    protectionHealth.observed(PacketSecurityState.ProtectedPath.CHUNK);
                    protectionHealth.enforced(PacketSecurityState.ProtectedPath.CHUNK);
                    lastChunkPacketFormat = sectionBlockCountIsLeInt ? "26.x little-endian count" : "1.21.x big-endian count";
                    lastChunkWorld = player.getWorld().getName();
                    metrics.countChunkPacket(true, true);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!securityState.isTripped()) {
                            veilEngine.markChunkHiddenByPacketRewrite(player, chunkX, chunkZ);
                        }
                    });
                } catch (Throwable throwable) {
                    event.setPacket(originalPacket);
                    packetBlockRewriteBroken.set(true);
                    trip(event, PacketSecurityState.ProtectedPath.CHUNK, packetCompatibilityFailure(
                            "Could not atomically protect chunk " + chunkX + "," + chunkZ, throwable));
                    return;
                }
            }
        });
    }

    private static boolean sectionBlockCountIsLeInt() {
        String v = Bukkit.getMinecraftVersion();
        if (v == null) return false;
        try {
            return Integer.parseInt(v.split("\\.")[0]) >= 26;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private List<PacketType> supportedServerPackets() {
        List<PacketType> packetTypes = new ArrayList<>();
        addIfSupported(packetTypes, PacketType.Play.Server.MAP_CHUNK);
        addIfSupported(packetTypes, PacketType.Play.Server.BLOCK_CHANGE);
        addIfSupported(packetTypes, PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        addIfSupported(packetTypes, PacketType.Play.Server.TILE_ENTITY_DATA);
        addIfSupported(packetTypes, PacketType.Play.Server.SPAWN_ENTITY);
        addIfSupported(packetTypes, PacketType.Play.Server.SPAWN_ENTITY_LIVING);
        addIfSupported(packetTypes, PacketType.Play.Server.SPAWN_ENTITY_EXPERIENCE_ORB);
        addIfSupported(packetTypes, PacketType.Play.Server.SPAWN_ENTITY_PAINTING);
        addIfSupported(packetTypes, PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_METADATA);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_EQUIPMENT);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_VELOCITY);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_TELEPORT);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_POSITION_SYNC);
        addIfSupported(packetTypes, PacketType.Play.Server.REL_ENTITY_MOVE);
        addIfSupported(packetTypes, PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        addIfSupported(packetTypes, PacketType.Play.Server.MOVE_MINECART);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_LOOK);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_STATUS);
        addIfSupported(packetTypes, PacketType.Play.Server.ANIMATION);
        addIfSupported(packetTypes, PacketType.Play.Server.ATTACH_ENTITY);
        addIfSupported(packetTypes, PacketType.Play.Server.MOUNT);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_DESTROY);
        addIfSupported(packetTypes, PacketType.Play.Server.UPDATE_ATTRIBUTES);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_EFFECT);
        addIfSupported(packetTypes, PacketType.Play.Server.REMOVE_ENTITY_EFFECT);
        addIfSupported(packetTypes, PacketType.Play.Server.ENTITY_SOUND);
        addIfSupported(packetTypes, PacketType.Play.Server.COLLECT);
        addIfSupported(packetTypes, PacketType.Play.Server.EXPLOSION);
        addIfSupported(packetTypes, PacketType.Play.Server.WORLD_EVENT);
        addIfSupported(packetTypes, PacketType.Play.Server.BLOCK_BREAK_ANIMATION);
        addIfSupported(packetTypes, PacketType.Play.Server.CUSTOM_SOUND_EFFECT);
        addIfSupported(packetTypes, PacketType.Play.Server.NAMED_SOUND_EFFECT);
        addIfSupported(packetTypes, PacketType.Play.Server.WORLD_PARTICLES);
        addIfSupported(packetTypes, PacketType.Play.Server.ADD_VIBRATION_SIGNAL);
        addIfSupported(packetTypes, PacketType.Play.Server.LIGHT_UPDATE);
        return packetTypes;
    }

    private void addIfSupported(List<PacketType> packetTypes, PacketType packetType) {
        if (packetType.isSupported()) {
            packetTypes.add(packetType);
        }
    }

    private boolean isEntitySpawnPacket(PacketType packetType) {
        return packetType == PacketType.Play.Server.SPAWN_ENTITY
                || packetType == PacketType.Play.Server.SPAWN_ENTITY_LIVING
                || packetType == PacketType.Play.Server.SPAWN_ENTITY_EXPERIENCE_ORB
                || packetType == PacketType.Play.Server.SPAWN_ENTITY_PAINTING
                || packetType == PacketType.Play.Server.NAMED_ENTITY_SPAWN;
    }

    private boolean isEntityFollowUpPacket(PacketType packetType) {
        return packetType == PacketType.Play.Server.ENTITY_METADATA
                || packetType == PacketType.Play.Server.ENTITY_EQUIPMENT
                || packetType == PacketType.Play.Server.ENTITY_VELOCITY
                || packetType == PacketType.Play.Server.ENTITY_TELEPORT
                || packetType == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || packetType == PacketType.Play.Server.REL_ENTITY_MOVE
                || packetType == PacketType.Play.Server.REL_ENTITY_MOVE_LOOK
                || packetType == PacketType.Play.Server.MOVE_MINECART
                || packetType == PacketType.Play.Server.ENTITY_LOOK
                || packetType == PacketType.Play.Server.ENTITY_HEAD_ROTATION
                || packetType == PacketType.Play.Server.ENTITY_STATUS
                || packetType == PacketType.Play.Server.ANIMATION
                || packetType == PacketType.Play.Server.ATTACH_ENTITY
                || packetType == PacketType.Play.Server.MOUNT
                || packetType == PacketType.Play.Server.ENTITY_DESTROY
                || packetType == PacketType.Play.Server.UPDATE_ATTRIBUTES
                || packetType == PacketType.Play.Server.ENTITY_EFFECT
                || packetType == PacketType.Play.Server.REMOVE_ENTITY_EFFECT
                || packetType == PacketType.Play.Server.ENTITY_SOUND
                || packetType == PacketType.Play.Server.COLLECT;
    }

    private void cancelHiddenEntitySpawn(PacketEvent event, VeilEngine veilEngine) {
        Player viewer = event.getPlayer();

        try {
            Entity entity = readEntity(event);
            int entityId = readInteger(event, 0, entity == null ? -1 : entity.getEntityId());
            UUID entityUuid = readUuid(event, entity == null ? null : entity.getUniqueId());

            boolean hidden;
            if (entity != null) {
                hidden = veilEngine.shouldHideEntity(viewer, entity);
            } else {
                Location location = readEntityLocation(event, viewer);
                if (location == null) {
                    throw new IllegalArgumentException("entity spawn packet has no readable position");
                }
                EntityType entityType = readEntityType(event, event.getPacketType() == PacketType.Play.Server.NAMED_ENTITY_SPAWN
                        ? EntityType.PLAYER
                        : null);
                hidden = veilEngine.shouldHideEntityAt(viewer, entityType, location);
            }

            if (!hidden) {
                protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_SPAWN);
                return;
            }

            event.setCancelled(true);
            metrics.countEntitySpawnCancelled();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (securityState.isTripped()) {
                    return;
                }
                if (entity != null) {
                    veilEngine.markEntityHidden(viewer, entity);
                } else {
                    veilEngine.markEntityHidden(viewer, entityId, entityUuid);
                }
            });
            protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_SPAWN);
            protectionHealth.enforced(PacketSecurityState.ProtectedPath.ENTITY_SPAWN);
        } catch (Throwable throwable) {
            String reason = packetCompatibilityFailure("Could not inspect entity spawn packet", throwable);
            trip(event, PacketSecurityState.ProtectedPath.ENTITY_SPAWN, reason);
        }
    }

    private void cancelHiddenEntityPacket(PacketEvent event, VeilEngine veilEngine) {
        try {
            if (!packetContainsHiddenEntity(event, veilEngine)) {
                protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP);
                return;
            }

            if (event.getPacketType() == PacketType.Play.Server.ENTITY_DESTROY) {
                Player viewer = event.getPlayer();
                for (int entityId : entityIdsInPacket(event)) {
                    Bukkit.getScheduler().runTask(plugin, () -> veilEngine.forgetEntity(viewer, entityId));
                }
                protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP);
                return;
            }

            event.setCancelled(true);
            metrics.countEntityPacketCancelled();
            protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP);
            protectionHealth.enforced(PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP);
        } catch (Throwable throwable) {
            String reason = packetCompatibilityFailure("Could not inspect entity packet " + event.getPacketType(), throwable);
            trip(event, PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP, reason);
        }
    }

    private boolean packetContainsHiddenEntity(PacketEvent event, VeilEngine veilEngine) {
        Player viewer = event.getPlayer();
        List<Integer> entityIds = entityIdsInPacket(event);
        if (entityIds.isEmpty()) {
            throw new IllegalArgumentException("entity packet has no readable entity id");
        }
        for (int entityId : entityIds) {
            if (veilEngine.isEntityHidden(viewer, entityId)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> entityIdsInPacket(PacketEvent event) {
        List<Integer> entityIds = new ArrayList<>();
        PacketType packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.ENTITY_DESTROY) {
            for (int i = 0; i < event.getPacket().getIntLists().size(); i++) {
                addEntityIds(entityIds, event.getPacket().getIntLists().readSafely(i));
            }
            for (int i = 0; i < event.getPacket().getIntegerArrays().size(); i++) {
                addEntityIds(entityIds, event.getPacket().getIntegerArrays().readSafely(i));
            }
            return entityIds;
        }

        addEntityId(entityIds, event.getPacket().getIntegers().readSafely(0));

        if (packetType == PacketType.Play.Server.MOUNT) {
            for (int i = 0; i < event.getPacket().getIntegerArrays().size(); i++) {
                addEntityIds(entityIds, event.getPacket().getIntegerArrays().readSafely(i));
            }
        } else if (packetType == PacketType.Play.Server.ATTACH_ENTITY
                || packetType == PacketType.Play.Server.COLLECT) {
            for (int i = 1; i < Math.min(2, event.getPacket().getIntegers().size()); i++) {
                addEntityId(entityIds, event.getPacket().getIntegers().readSafely(i));
            }
        }

        return entityIds;
    }

    private void addEntityIds(List<Integer> entityIds, int[] values) {
        if (values == null) {
            return;
        }
        for (int value : values) {
            addEntityId(entityIds, value);
        }
    }

    private void addEntityIds(List<Integer> entityIds, List<Integer> values) {
        if (values == null) {
            return;
        }
        for (Integer value : values) {
            addEntityId(entityIds, value);
        }
    }

    private void addEntityId(List<Integer> entityIds, Integer value) {
        if (value != null && value >= 0) {
            entityIds.add(value);
        }
    }

    private Entity readEntity(PacketEvent event) {
        try {
            return event.getPacket().getEntityModifier(event).readSafely(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int readInteger(PacketEvent event, int index, int fallback) {
        Integer value = event.getPacket().getIntegers().readSafely(index);
        return value == null ? fallback : value;
    }

    private UUID readUuid(PacketEvent event, UUID fallback) {
        UUID value = event.getPacket().getUUIDs().readSafely(0);
        return value == null ? fallback : value;
    }

    private EntityType readEntityType(PacketEvent event, EntityType fallback) {
        EntityType value = event.getPacket().getEntityTypeModifier().readSafely(0);
        return value == null ? fallback : value;
    }

    private Location readEntityLocation(PacketEvent event, Player viewer) {
        Double x = event.getPacket().getDoubles().readSafely(0);
        Double y = event.getPacket().getDoubles().readSafely(1);
        Double z = event.getPacket().getDoubles().readSafely(2);
        if (x == null || y == null || z == null) {
            return null;
        }

        return new Location(viewer.getWorld(), x, y, z);
    }

    private void rewriteBlockChange(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().read(0);
            if (position == null) {
                throw new IllegalArgumentException("block change packet has no readable position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.getPacket().getBlockData().write(0, fakeBlockData(event.getPlayer().getWorld()));
                metrics.countBlockChangeRewritten();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.BLOCK_CHANGE);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.BLOCK_CHANGE);
        } catch (Throwable throwable) {
            String reason = packetCompatibilityFailure("Could not rewrite block change packet", throwable);
            trip(event, PacketSecurityState.ProtectedPath.BLOCK_CHANGE, reason);
        }
    }

    private void rewriteMultiBlockChange(PacketEvent event, VeilEngine veilEngine) {
        if (multiBlockChangeRewriteBroken.get()) {
            return;
        }

        if (rewriteModernMultiBlockChange(event, veilEngine)) {
            protectionHealth.observed(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
            return;
        }
        rewriteLegacyMultiBlockChange(event, veilEngine);
    }

    private boolean rewriteModernMultiBlockChange(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition sectionPosition = event.getPacket().getSectionPositions().read(0);
            short[] positions = event.getPacket().getShortArrays().read(0);
            WrappedBlockData[] blockData = event.getPacket().getBlockDataArrays().read(0);
            if (sectionPosition == null || positions == null || blockData == null) {
                return false;
            }

            int length = Math.min(positions.length, blockData.length);
            boolean changed = false;
            for (int i = 0; i < length; i++) {
                PacketCoordinates.BlockCoordinate block = PacketCoordinates.modernMultiBlock(
                        sectionPosition.getX(), sectionPosition.getY(), sectionPosition.getZ(), positions[i]);
                if (veilEngine.shouldHideBlock(event.getPlayer(), block.x(), block.y(), block.z())) {
                    blockData[i] = fakeBlockData(event.getPlayer().getWorld());
                    changed = true;
                }
            }

            if (changed) {
                event.getPacket().getBlockDataArrays().write(0, blockData);
                metrics.countMultiBlockChangeRewritten();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void rewriteLegacyMultiBlockChange(PacketEvent event, VeilEngine veilEngine) {
        try {
            com.comphenix.protocol.wrappers.MultiBlockChangeInfo[] changes =
                    event.getPacket().getMultiBlockChangeInfoArrays().read(0);
            if (changes == null) {
                throw new IllegalArgumentException("multi-block change packet has no readable changes");
            }

            boolean changed = false;
            for (com.comphenix.protocol.wrappers.MultiBlockChangeInfo change : changes) {
                if (change == null) {
                    continue;
                }
                if (veilEngine.shouldHideBlock(event.getPlayer(), change.getAbsoluteX(), change.getY(), change.getAbsoluteZ())) {
                    change.setData(fakeBlockData(event.getPlayer().getWorld()));
                    changed = true;
                }
            }

            if (changed) {
                event.getPacket().getMultiBlockChangeInfoArrays().write(0, changes);
                metrics.countMultiBlockChangeRewritten();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
            }
        } catch (Throwable throwable) {
            if (multiBlockChangeRewriteBroken.compareAndSet(false, true)) {
                String reason = packetCompatibilityFailure("Could not rewrite multi-block change packets", throwable);
                trip(event, PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE, reason);
            }
        }
    }

    private void cancelHiddenBlockEntityUpdate(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().read(0);
            if (position == null) {
                throw new IllegalArgumentException("block entity update has no readable position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.setCancelled(true);
                metrics.countBlockEntityUpdateCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.BLOCK_ENTITY);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.BLOCK_ENTITY);
        } catch (Throwable throwable) {
            String reason = packetCompatibilityFailure("Could not inspect block entity update packet", throwable);
            trip(event, PacketSecurityState.ProtectedPath.BLOCK_ENTITY, reason);
        }
    }

    private void cancelHiddenExplosion(PacketEvent event, VeilEngine veilEngine) {
        try {
            // The explosion center is the packet's only Vec3 field on 1.21.2+,
            // so vector index 0 is unambiguous there; the doubles fallback
            // covers the flattened pre-1.21.2 layout.
            PacketCoordinates.BlockCoordinate block = PacketCoordinates.explosionCenter(
                    event.getPacket().getVectors().readSafely(0),
                    event.getPacket().getDoubles().readSafely(0),
                    event.getPacket().getDoubles().readSafely(1),
                    event.getPacket().getDoubles().readSafely(2));
            if (block == null) {
                throw new IllegalArgumentException("explosion packet has no readable center");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), block.x(), block.y(), block.z())) {
                event.setCancelled(true);
                metrics.countExplosionPacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.EXPLOSION);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.EXPLOSION);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.EXPLOSION,
                    packetCompatibilityFailure("Could not inspect explosion packet", throwable));
        }
    }

    private void cancelHiddenWorldEvent(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
            if (position == null) {
                throw new IllegalArgumentException("world event packet has no readable position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.setCancelled(true);
                metrics.countWorldEventPacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.WORLD_EVENT);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.WORLD_EVENT);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.WORLD_EVENT,
                    packetCompatibilityFailure("Could not inspect world event packet", throwable));
        }
    }

    private void cancelHiddenBlockCrack(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
            if (position == null) {
                throw new IllegalArgumentException("block crack packet has no readable position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.setCancelled(true);
                metrics.countBlockBreakAnimationPacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.BLOCK_CRACK);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.BLOCK_CRACK);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.BLOCK_CRACK,
                    packetCompatibilityFailure("Could not inspect block crack packet", throwable));
        }
    }

    private void cancelHiddenPositionalSound(PacketEvent event, VeilEngine veilEngine) {
        try {
            // Position is encoded as fixed-point integers: block_coord * 8.
            // The sound entry and source category are exposed via specialised modifiers,
            // so getIntegers() indices 0/1/2 are the x/y/z position fields.
            Integer fixedX = event.getPacket().getIntegers().readSafely(0);
            Integer fixedY = event.getPacket().getIntegers().readSafely(1);
            Integer fixedZ = event.getPacket().getIntegers().readSafely(2);
            if (fixedX == null || fixedY == null || fixedZ == null) {
                throw new IllegalArgumentException("positional sound packet has no readable position");
            }
            PacketCoordinates.BlockCoordinate block = PacketCoordinates.fixedPointSound(fixedX, fixedY, fixedZ);
            if (veilEngine.shouldHideBlock(event.getPlayer(), block.x(), block.y(), block.z())) {
                event.setCancelled(true);
                metrics.countSoundPacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.POSITIONAL_SOUND);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.POSITIONAL_SOUND);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.POSITIONAL_SOUND,
                    packetCompatibilityFailure("Could not inspect positional sound packet", throwable));
        }
    }

    private void cancelHiddenParticle(PacketEvent event, VeilEngine veilEngine) {
        try {
            Location location = readPacketLocation(event);
            if (location == null) {
                throw new IllegalArgumentException("particle packet has no readable position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                event.setCancelled(true);
                metrics.countParticlePacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.PARTICLE);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.PARTICLE);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.PARTICLE,
                    packetCompatibilityFailure("Could not inspect particle packet", throwable));
        }
    }

    private void cancelHiddenVibration(PacketEvent event, VeilEngine veilEngine) {
        try {
            Location location = readPacketLocation(event);
            if (location == null) {
                throw new IllegalArgumentException("vibration packet has no readable source position");
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                event.setCancelled(true);
                metrics.countVibrationPacketCancelled();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.VIBRATION);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.VIBRATION);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.VIBRATION,
                    packetCompatibilityFailure("Could not inspect vibration packet", throwable));
        }
    }

    private Location readPacketLocation(PacketEvent event) {
        BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
        if (position != null) {
            return new Location(event.getPlayer().getWorld(), position.getX(), position.getY(), position.getZ());
        }

        Double x = event.getPacket().getDoubles().readSafely(0);
        Double y = event.getPacket().getDoubles().readSafely(1);
        Double z = event.getPacket().getDoubles().readSafely(2);
        return x == null || y == null || z == null
                ? null
                : new Location(event.getPlayer().getWorld(), x, y, z);
    }

    private void sanitizeStandaloneLightUpdate(PacketEvent event, VeilEngine veilEngine) {
        try {
            Integer chunkX = event.getPacket().getIntegers().readSafely(0);
            Integer chunkZ = event.getPacket().getIntegers().readSafely(1);
            if (chunkX == null || chunkZ == null) {
                throw new IllegalArgumentException("light update packet has no readable chunk coordinates");
            }
            if (veilEngine.shouldHideChunk(event.getPlayer(), chunkX, chunkZ)) {
                sanitizeLightData(event, event.getPlayer().getWorld());
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.LIGHT);
        } catch (Throwable throwable) {
            trip(event, PacketSecurityState.ProtectedPath.LIGHT,
                    packetCompatibilityFailure("Could not sanitize light update packet", throwable));
        }
    }

    private void sanitizeLightData(PacketEvent event, World world) {
        try {
            WrappedLevelChunkData.LightData light = event.getPacket().getLightUpdateData().readSafely(0);
            if (light == null) {
                throw new IllegalArgumentException("packet exposes no readable light-data wrapper");
            }
            int changed = LightPacketSanitizer.sanitize(
                    light.getSkyYMask(),
                    light.getSkyUpdates(),
                    light.getBlockYMask(),
                    light.getBlockUpdates(),
                    world.getMinHeight(),
                    settings.hideBelowY(world)
            );
            if (changed > 0) {
                event.getPacket().getLightUpdateData().write(0, light);
                metrics.countLightPacketSanitized();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.LIGHT);
            }
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("could not rewrite concealed light arrays", throwable);
        }
    }

    private int rewriteHiddenChunkSections(
            PacketEvent event,
            Player player,
            int chunkX,
            int chunkZ,
            VeilSettings settings
    ) {
        World world = player.getWorld();
        long startNanos = System.nanoTime();
        int rewrittenSections = rewriterFor(settings.defaultFakeBlock(world))
                .rewriteHiddenSections(event, world, settings.hideBelowY(world), settings.hideAir(world));
        metrics.recordPacketChunkRewriteNanos(System.nanoTime() - startNanos);
        return rewrittenSections;
    }

    private void stripHiddenBlockEntities(PacketEvent event, int hideBelowY) {
        WrappedLevelChunkData.ChunkData chunkData = event.getPacket().getLevelChunkData().read(0);
        List<WrappedLevelChunkData.BlockEntityInfo> blockEntities = chunkData.getBlockEntityInfo();
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        List<WrappedLevelChunkData.BlockEntityInfo> visibleBlockEntities = new ArrayList<>(blockEntities.size());
        for (WrappedLevelChunkData.BlockEntityInfo blockEntity : blockEntities) {
            if (blockEntity.getY() >= hideBelowY) {
                visibleBlockEntities.add(blockEntity);
            }
        }

        if (visibleBlockEntities.size() != blockEntities.size()) {
            chunkData.setBlockEntityInfo(visibleBlockEntities);
            event.getPacket().getLevelChunkData().write(0, chunkData);
        }
    }

    private WrappedBlockData fakeBlockData(World world) {
        return fakeBlockData.computeIfAbsent(settingsDefaultFakeBlock(world), WrappedBlockData::createData);
    }

    private Material settingsDefaultFakeBlock(World world) {
        return settings.defaultFakeBlock(world);
    }

    private ChunkPacketBlockRewriter rewriterFor(Material fakeBlock) {
        return blockRewriters.computeIfAbsent(fakeBlock, material -> {
            int fakeBlockStateId = NmsBlockStateIds.defaultStateId(material);
            return new ChunkPacketBlockRewriter(fakeBlockStateId, airBlockStateId, sectionBlockCountIsLeInt);
        });
    }

    private void trip(PacketEvent event, PacketSecurityState.ProtectedPath path, String reason) {
        metrics.countSecurityPacketCancelled();
        protectionHealth.failed(path, reason);
        boolean firstTrip = securityState.tripAndCancel(path, reason, () -> event.setCancelled(true));
        if (firstTrip && failClosedScheduled.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.failClosed(reason));
        }
    }

    private String packetCompatibilityFailure(String action, Throwable throwable) {
        return action + ": " + describeThrowable(throwable);
    }

    private String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }

        StringBuilder description = new StringBuilder(throwable.getClass().getSimpleName());
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            description.append(": ").append(throwable.getMessage());
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            description.append(" caused by ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                description.append(": ").append(cause.getMessage());
            }
        }
        return description.toString();
    }
}

package com.dekaeyman.chunkveil;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
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
import org.bukkit.plugin.Plugin;

final class ProtocolChunkListener {
    private final Plugin plugin;
    private final ProtocolManager protocolManager;
    private final VeilMetrics metrics;
    private final VeilSettings settings;
    private final AtomicBoolean chunkDataWrappersBroken = new AtomicBoolean();
    private final AtomicBoolean packetBlockRewriteBroken = new AtomicBoolean();
    private final AtomicBoolean multiBlockChangeRewriteBroken = new AtomicBoolean();
    private final Map<Material, ChunkPacketBlockRewriter> blockRewriters = new ConcurrentHashMap<>();
    private final Map<Material, WrappedBlockData> fakeBlockData = new ConcurrentHashMap<>();
    private int airBlockStateId;

    private ProtocolChunkListener(Plugin plugin, ProtocolManager protocolManager, VeilMetrics metrics, VeilSettings settings) {
        this.plugin = plugin;
        this.protocolManager = protocolManager;
        this.metrics = metrics;
        this.settings = settings;
    }

    static ProtocolChunkListener start(Plugin plugin, VeilEngine veilEngine, VeilSettings settings, VeilMetrics metrics) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        ProtocolChunkListener listener = new ProtocolChunkListener(plugin, protocolManager, metrics, settings);
        listener.initializeBlockRewriter(settings);
        listener.register(veilEngine, settings);
        plugin.getLogger().info("ProtocolLib chunk listener enabled.");
        plugin.getLogger().info("For newer Paper 1.21.x builds, use a compatible ProtocolLib dev build or 5.4.1 runtime jar; "
                + "5.4.0 stable only officially supports up to 1.21.8 chunk wrappers.");
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
            packetBlockRewriteBroken.set(true);
            plugin.getLogger().warning("Could not initialize packet section rewrite: " + exception.getMessage());
        }
    }

    void stop() {
        protocolManager.removePacketListeners(plugin);
    }

    boolean packetRewriteActive() {
        return !packetBlockRewriteBroken.get();
    }

    private void register(VeilEngine veilEngine, VeilSettings settings) {
        protocolManager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                supportedServerPackets()
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
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

                Player player = event.getPlayer();
                int chunkX;
                int chunkZ;

                try {
                    chunkX = event.getPacket().getIntegers().read(0);
                    chunkZ = event.getPacket().getIntegers().read(1);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Could not read outgoing chunk packet coordinates: " + exception.getMessage());
                    return;
                }

                boolean hidden = veilEngine.shouldHideChunk(player, chunkX, chunkZ);
                boolean packetRewritten = false;
                if (hidden && !packetBlockRewriteBroken.get()) {
                    packetRewritten = rewriteHiddenChunkSections(event, player, chunkX, chunkZ, settings) > 0;
                }
                if (hidden && !chunkDataWrappersBroken.get()) {
                    stripHiddenBlockEntities(event, settings.hideBelowY(player.getWorld()));
                }

                metrics.countChunkPacket(hidden, packetRewritten);
                if (packetRewritten) {
                    Bukkit.getScheduler().runTask(plugin, () -> veilEngine.markChunkHiddenByPacketRewrite(player, chunkX, chunkZ));
                } else if (hidden) {
                    event.setCancelled(true);
                }
            }
        });
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
                EntityType entityType = readEntityType(event, event.getPacketType() == PacketType.Play.Server.NAMED_ENTITY_SPAWN
                        ? EntityType.PLAYER
                        : null);
                hidden = veilEngine.shouldHideEntityAt(viewer, entityType, location);
            }

            if (!hidden) {
                return;
            }

            event.setCancelled(true);
            metrics.countEntitySpawnCancelled();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (entity != null) {
                    veilEngine.markEntityHidden(viewer, entity);
                } else {
                    veilEngine.markEntityHidden(viewer, entityId, entityUuid);
                }
            });
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not inspect entity spawn packet: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private void cancelHiddenEntityPacket(PacketEvent event, VeilEngine veilEngine) {
        try {
            if (!packetContainsHiddenEntity(event, veilEngine)) {
                return;
            }

            if (event.getPacketType() == PacketType.Play.Server.ENTITY_DESTROY) {
                Player viewer = event.getPlayer();
                for (int entityId : entityIdsInPacket(event)) {
                    Bukkit.getScheduler().runTask(plugin, () -> veilEngine.forgetEntity(viewer, entityId));
                }
                return;
            }

            event.setCancelled(true);
            metrics.countEntityPacketCancelled();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not inspect entity packet " + event.getPacketType() + ": "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private boolean packetContainsHiddenEntity(PacketEvent event, VeilEngine veilEngine) {
        Player viewer = event.getPlayer();
        for (int entityId : entityIdsInPacket(event)) {
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
                return;
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.getPacket().getBlockData().write(0, fakeBlockData(event.getPlayer().getWorld()));
                metrics.countBlockChangeRewritten();
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not rewrite block change packet: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private void rewriteMultiBlockChange(PacketEvent event, VeilEngine veilEngine) {
        if (multiBlockChangeRewriteBroken.get()) {
            return;
        }

        if (rewriteModernMultiBlockChange(event, veilEngine)) {
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
            int baseX = sectionPosition.getX() << 4;
            int baseY = sectionPosition.getY() << 4;
            int baseZ = sectionPosition.getZ() << 4;

            for (int i = 0; i < length; i++) {
                int encoded = positions[i] & 0xFFFF;
                int blockX = baseX + ((encoded >>> 8) & 0xF);
                int blockY = baseY + (encoded & 0xF);
                int blockZ = baseZ + ((encoded >>> 4) & 0xF);
                if (veilEngine.shouldHideBlock(event.getPlayer(), blockX, blockY, blockZ)) {
                    blockData[i] = fakeBlockData(event.getPlayer().getWorld());
                    changed = true;
                }
            }

            if (changed) {
                event.getPacket().getBlockDataArrays().write(0, blockData);
                metrics.countMultiBlockChangeRewritten();
            }
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
                return;
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
            }
        } catch (Throwable throwable) {
            if (multiBlockChangeRewriteBroken.compareAndSet(false, true)) {
                plugin.getLogger().warning("Could not rewrite multi-block change packets: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                plugin.getLogger().warning("Disabling multi-block update rewrite for this run.");
            }
        }
    }

    private void cancelHiddenBlockEntityUpdate(PacketEvent event, VeilEngine veilEngine) {
        try {
            BlockPosition position = event.getPacket().getBlockPositionModifier().read(0);
            if (position == null) {
                return;
            }
            if (veilEngine.shouldHideBlock(event.getPlayer(), position.getX(), position.getY(), position.getZ())) {
                event.setCancelled(true);
                metrics.countBlockEntityUpdateCancelled();
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not inspect block entity update packet: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private int rewriteHiddenChunkSections(
            PacketEvent event,
            Player player,
            int chunkX,
            int chunkZ,
            VeilSettings settings
    ) {
        try {
            World world = player.getWorld();
            return rewriterFor(settings.defaultFakeBlock(world))
                    .rewriteHiddenSections(event, world, settings.hideBelowY(world), settings.hideAir(world));
        } catch (Throwable throwable) {
            if (packetBlockRewriteBroken.compareAndSet(false, true)) {
                plugin.getLogger().warning("Could not rewrite chunk packet sections for chunk "
                        + chunkX + "," + chunkZ + ": "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                plugin.getLogger().warning("Disabling packet section rewrite for this run; hidden chunk packets will be cancelled instead.");
            }
            return 0;
        }
    }

    private void stripHiddenBlockEntities(PacketEvent event, int hideBelowY) {
        try {
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
        } catch (Throwable throwable) {
            disableChunkDataWrapperFeatures("Could not strip hidden block entities from chunk packet", throwable);
        }
    }

    private void disableChunkDataWrapperFeatures(String reason, Throwable throwable) {
        if (!chunkDataWrappersBroken.compareAndSet(false, true)) {
            return;
        }

        plugin.getLogger().warning(reason + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        plugin.getLogger().warning("Disabling ProtocolLib WrappedLevelChunkData features for this run. "
                + "Your ProtocolLib build appears incompatible with this Paper 1.21.x chunk-data format.");
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
            return new ChunkPacketBlockRewriter(fakeBlockStateId, airBlockStateId);
        });
    }
}

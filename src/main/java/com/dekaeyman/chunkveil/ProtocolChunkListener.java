package com.dekaeyman.chunkveil;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.utility.MinecraftReflection;
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
    private final boolean dataArrayLengthPrefixed;
    private final AtomicBoolean packetBlockRewriteBroken = new AtomicBoolean();
    private final AtomicBoolean failClosedScheduled = new AtomicBoolean();
    private final PacketSecurityState securityState = new PacketSecurityState();
    private final PacketProtectionHealth protectionHealth = new PacketProtectionHealth();
    private final Map<Material, ChunkPacketBlockRewriter> blockRewriters = new ConcurrentHashMap<>();
    private final Map<Material, WrappedBlockData> fakeBlockData = new ConcurrentHashMap<>();
    private int[] airStateIds;
    private volatile PacketAdapter adapter;
    private int globalPaletteBits;
    private volatile String lastChunkPacketFormat;
    private volatile String lastChunkWorld;

    private ProtocolChunkListener(ChunkVeilPlugin plugin, ProtocolManager protocolManager, VeilMetrics metrics, VeilSettings settings) {
        this.plugin = plugin;
        this.protocolManager = protocolManager;
        this.metrics = metrics;
        this.settings = settings;
        this.sectionBlockCountIsLeInt = sectionBlockCountIsLeInt();
        this.dataArrayLengthPrefixed = ChunkPacketBlockRewriter.dataArrayLengthPrefixed(Bukkit.getMinecraftVersion());
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
        listener.probeChunkDataCompatibility();
        listener.initializeBlockRewriter(settings);
        listener.register(veilEngine, settings);
        plugin.getLogger().info("ProtocolLib chunk listener enabled with fail-closed packet rewriting.");
        return listener;
    }

    /**
     * Forces the ProtocolLib reflection chain the concealed-chunk path needs.
     * On a ProtocolLib build that does not support this Minecraft version,
     * these lookups are the first thing to fail (for example 5.4.0 cannot
     * find MinecraftKey on 26.x), but only when the first chunk carrying a
     * block entity is rewritten — long after a healthy-looking boot. Probing
     * here turns that confusing first-join security trip into a clear
     * startup refusal that names the incompatible build.
     */
    private void probeChunkDataCompatibility() {
        try {
            MinecraftReflection.getMinecraftKeyClass();
            Class.forName("com.comphenix.protocol.wrappers.WrappedLevelChunkData$BlockEntityInfo",
                    true, ProtocolLibrary.class.getClassLoader());
        } catch (Throwable throwable) {
            throw new IllegalStateException("ProtocolLib " + installedProtocolLibVersion()
                    + " cannot decode chunk data on Minecraft " + Bukkit.getMinecraftVersion()
                    + " (" + describeThrowable(throwable) + "). Install a ProtocolLib build that lists Minecraft "
                    + Bukkit.getMinecraftVersion() + " as supported. Minecraft 1.21.9+ and 26.x need the current"
                    + " ProtocolLib development build; 5.4.0 supports only up to 1.21.8:"
                    + " https://github.com/dmulloy2/ProtocolLib/releases/tag/dev-build", throwable);
        }
    }

    private String installedProtocolLibVersion() {
        org.bukkit.plugin.Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        return protocolLib == null ? "(not installed)" : protocolLib.getPluginMeta().getVersion();
    }

    private void initializeBlockRewriter(VeilSettings settings) {
        try {
            // Air-like blocks have a single block state each, so the default
            // state id is the only id they ever appear as in chunk data.
            this.airStateIds = new int[]{
                    NmsBlockStateIds.defaultStateId(Material.AIR),
                    NmsBlockStateIds.defaultStateId(Material.CAVE_AIR),
                    NmsBlockStateIds.defaultStateId(Material.VOID_AIR)
            };
            this.globalPaletteBits = NmsBlockStateIds.globalPaletteBits();
            for (VeilWorldSettings worldSettings : settings.worlds().values()) {
                rewriterFor(worldSettings.defaultFakeBlock());
            }
            plugin.getLogger().info("Packet section rewrite enabled with per-world fake block support (global palette "
                    + globalPaletteBits + " bits).");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not initialize packet section rewrite: " + exception.getMessage(), exception);
        }
    }

    void stop() {
        // Only this listener: on reload the next one is already registered.
        PacketAdapter registered = adapter;
        if (registered != null) {
            protocolManager.removePacketListener(registered);
            adapter = null;
        }
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

    /** {hits, misses} of the chunk rewrite caches of all fake-block rewriters. */
    long[] chunkRewriteCacheStats() {
        long hits = 0;
        long misses = 0;
        for (ChunkPacketBlockRewriter rewriter : blockRewriters.values()) {
            hits += rewriter.cacheHits();
            misses += rewriter.cacheMisses();
        }
        return new long[]{hits, misses};
    }

    private void register(VeilEngine veilEngine, VeilSettings settings) {
        adapter = new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                supportedServerPackets(settings)
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                long started = System.nanoTime();
                try {
                    handleSending(event, veilEngine, settings);
                } finally {
                    metrics.recordPacketHandlingNanos(pathOf(event.getPacketType()), System.nanoTime() - started);
                }
            }
        };
        protocolManager.addPacketListener(adapter);
    }

    /** Which protected path a packet type is handled (and timed) under. */
    private PacketSecurityState.ProtectedPath pathOf(PacketType type) {
        if (isEntitySpawnPacket(type)) {
            return PacketSecurityState.ProtectedPath.ENTITY_SPAWN;
        }
        if (isEntityFollowUpPacket(type)) {
            return PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP;
        }
        if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            return PacketSecurityState.ProtectedPath.BLOCK_CHANGE;
        }
        if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            return PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE;
        }
        if (type == PacketType.Play.Server.TILE_ENTITY_DATA) {
            return PacketSecurityState.ProtectedPath.BLOCK_ENTITY;
        }
        if (type == PacketType.Play.Server.EXPLOSION) {
            return PacketSecurityState.ProtectedPath.EXPLOSION;
        }
        if (type == PacketType.Play.Server.WORLD_EVENT) {
            return PacketSecurityState.ProtectedPath.WORLD_EVENT;
        }
        if (type == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
            return PacketSecurityState.ProtectedPath.BLOCK_CRACK;
        }
        if (type == PacketType.Play.Server.CUSTOM_SOUND_EFFECT || type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
            return PacketSecurityState.ProtectedPath.POSITIONAL_SOUND;
        }
        if (type == PacketType.Play.Server.WORLD_PARTICLES) {
            return PacketSecurityState.ProtectedPath.PARTICLE;
        }
        if (type == PacketType.Play.Server.ADD_VIBRATION_SIGNAL) {
            return PacketSecurityState.ProtectedPath.VIBRATION;
        }
        if (type == PacketType.Play.Server.LIGHT_UPDATE) {
            return PacketSecurityState.ProtectedPath.LIGHT;
        }
        return PacketSecurityState.ProtectedPath.CHUNK;
    }

    private void handleSending(PacketEvent event, VeilEngine veilEngine, VeilSettings settings) {
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
            // One read and one write of the chunk data wrapper: each
            // read converts the whole structure, block entities too.
            WrappedLevelChunkData.ChunkData chunkData = event.getPacket().getLevelChunkData().read(0);
            int rewrittenSections = rewriteHiddenChunkSections(chunkData, player, settings);
            if (rewrittenSections <= 0) {
                throw new IllegalArgumentException("no concealed chunk sections were rewritten");
            }
            stripHiddenBlockEntities(chunkData, settings.hideMinY(player.getWorld()), settings.hideBelowY(player.getWorld()));
            event.getPacket().getLevelChunkData().write(0, chunkData);
            if (settings.sanitizeLightInHiddenZones()) {
                sanitizeLightData(event, player.getWorld());
                protectionHealth.observed(PacketSecurityState.ProtectedPath.LIGHT);
            }
            protectionHealth.observed(PacketSecurityState.ProtectedPath.CHUNK);
            protectionHealth.enforced(PacketSecurityState.ProtectedPath.CHUNK);
            lastChunkPacketFormat = sectionBlockCountIsLeInt ? "26.x little-endian count"
                    : dataArrayLengthPrefixed ? "1.21-1.21.4 length-prefixed arrays"
                    : "1.21.5+ big-endian count";
            lastChunkWorld = player.getWorld().getName();
            metrics.countChunkPacket(true, true);
            VeilScheduler.runOnEntity(plugin, player, () -> {
                if (!securityState.isTripped()) {
                    veilEngine.markChunkHiddenByPacketRewrite(player, chunkX, chunkZ);
                }
            });
        } catch (Throwable throwable) {
            event.setPacket(originalPacket);
            trip(event, PacketSecurityState.ProtectedPath.CHUNK, packetCompatibilityFailure(
                    "Could not atomically protect chunk " + chunkX + "," + chunkZ, throwable),
                    player.getWorld(), chunkX, chunkZ);
            return;
        }
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

    /**
     * Packet types to listen to. ProtocolLib builds a PacketEvent for every
     * packet of every subscribed type, so types whose feature is switched
     * off are not subscribed at all (the listener is re-created on reload).
     * Terrain and block updates are always protected. While a security trip
     * is active only subscribed types are cancelled; unsubscribed types carry
     * nothing ChunkVeil was configured to protect.
     */
    private List<PacketType> supportedServerPackets(VeilSettings settings) {
        List<PacketType> packetTypes = new ArrayList<>();
        addIfSupported(packetTypes, PacketType.Play.Server.MAP_CHUNK);
        addIfSupported(packetTypes, PacketType.Play.Server.BLOCK_CHANGE);
        addIfSupported(packetTypes, PacketType.Play.Server.MULTI_BLOCK_CHANGE);
        addIfSupported(packetTypes, PacketType.Play.Server.TILE_ENTITY_DATA);
        if (settings.anyWorldHidesEntities()) {
            // Spawns and follow-ups belong together: a cancelled spawn relies
            // on its metadata/equipment/movement packets being cancelled too.
            for (PacketType packetType : ENTITY_PACKET_TYPES) {
                addIfSupported(packetTypes, packetType);
            }
        }
        if (settings.cancelExplosionsInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.EXPLOSION);
        }
        if (settings.cancelWorldEventsInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.WORLD_EVENT);
        }
        if (settings.cancelBlockCrackInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.BLOCK_BREAK_ANIMATION);
        }
        if (settings.cancelPositionalSoundsInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.CUSTOM_SOUND_EFFECT);
            addIfSupported(packetTypes, PacketType.Play.Server.NAMED_SOUND_EFFECT);
        }
        if (settings.cancelParticlesInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.WORLD_PARTICLES);
        }
        if (settings.cancelVibrationsInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.ADD_VIBRATION_SIGNAL);
        }
        if (settings.sanitizeLightInHiddenZones()) {
            addIfSupported(packetTypes, PacketType.Play.Server.LIGHT_UPDATE);
        }
        return packetTypes;
    }

    private static final PacketType[] ENTITY_PACKET_TYPES = {
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.SPAWN_ENTITY_LIVING,
            PacketType.Play.Server.SPAWN_ENTITY_EXPERIENCE_ORB,
            PacketType.Play.Server.SPAWN_ENTITY_PAINTING,
            PacketType.Play.Server.NAMED_ENTITY_SPAWN,
            PacketType.Play.Server.ENTITY_METADATA,
            PacketType.Play.Server.ENTITY_EQUIPMENT,
            PacketType.Play.Server.ENTITY_VELOCITY,
            PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.ENTITY_POSITION_SYNC,
            PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
            PacketType.Play.Server.MOVE_MINECART,
            PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.ENTITY_HEAD_ROTATION,
            PacketType.Play.Server.ENTITY_STATUS,
            PacketType.Play.Server.ANIMATION,
            PacketType.Play.Server.ATTACH_ENTITY,
            PacketType.Play.Server.MOUNT,
            PacketType.Play.Server.ENTITY_DESTROY,
            PacketType.Play.Server.UPDATE_ATTRIBUTES,
            PacketType.Play.Server.ENTITY_EFFECT,
            PacketType.Play.Server.REMOVE_ENTITY_EFFECT,
            PacketType.Play.Server.ENTITY_SOUND,
            PacketType.Play.Server.COLLECT,
    };

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
        if (!veilEngine.entityHidingActive(viewer)) {
            // hide-entities off (or nothing concealed) in this world: no spawn
            // can be hidden, so skip reading the packet entirely.
            return;
        }

        try {
            // Resolving the live entity makes ProtocolLib run a server-internal
            // entity lookup, and regionized servers reject that on foreign
            // threads: any plugin that sends a packet from its own async thread
            // (TAB, holograms, chat from the global thread) flushes queued
            // spawn packets there and would hit a thread-check for every one.
            // The packet itself carries the id, uuid, position, and type, so on
            // Folia-family servers we never resolve the entity at all; Paper
            // keeps the live path it has always used.
            final Entity entity = VeilScheduler.folia() ? null : readEntity(event);
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
            // Hidden before this handler returns, so the metadata/equipment
            // packets queued right behind this spawn are cancelled too.
            veilEngine.markEntityHiddenFromPacket(viewer.getUniqueId(), entityId, entityUuid);
            VeilScheduler.runOnEntity(plugin, viewer, () -> {
                if (securityState.isTripped()) {
                    return;
                }
                // Ownership can change between packet time and this tick on
                // the viewer's thread; only touch the live entity while owned.
                if (entity != null && VeilScheduler.canAccessEntity(entity)) {
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
        if (!veilEngine.hasHiddenEntities(event.getPlayer())) {
            // The vast majority of entity packets: nothing is hidden for this
            // viewer, so none can match. Skips ProtocolLib field reads and an
            // id list per packet (275M such packets in one field report).
            return;
        }
        try {
            if (!packetContainsHiddenEntity(event, veilEngine)) {
                protectionHealth.observed(PacketSecurityState.ProtectedPath.ENTITY_FOLLOW_UP);
                return;
            }

            if (event.getPacketType() == PacketType.Play.Server.ENTITY_DESTROY) {
                Player viewer = event.getPlayer();
                for (int entityId : entityIdsInPacket(event)) {
                    VeilScheduler.runOnEntity(plugin, viewer,
                            () -> veilEngine.forgetEntityDestroyedForClient(viewer, entityId));
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
                // Broadcast packets are one shared instance delivered to every
                // tracking player; a per-player rewrite must never mutate it.
                PacketContainer rewritten = event.getPacket().shallowClone();
                rewritten.getBlockData().write(0, fakeBlockData(event.getPlayer().getWorld()));
                event.setPacket(rewritten);
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
                // Broadcast packets are one shared instance delivered to every
                // tracking player; a per-player rewrite must never mutate it.
                PacketContainer rewritten = event.getPacket().shallowClone();
                rewritten.getBlockDataArrays().write(0, blockData);
                event.setPacket(rewritten);
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
                // Broadcast packets are one shared instance delivered to every
                // tracking player; a per-player rewrite must never mutate it.
                PacketContainer rewritten = event.getPacket().shallowClone();
                rewritten.getMultiBlockChangeInfoArrays().write(0, changes);
                event.setPacket(rewritten);
                metrics.countMultiBlockChangeRewritten();
                protectionHealth.enforced(PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE);
            }
        } catch (Throwable throwable) {
            // Every failing packet is cancelled by trip(); repeated failures
            // cross the tolerance threshold and quarantine. Skipping rewrites
            // after a failure would silently send real block data instead.
            String reason = packetCompatibilityFailure("Could not rewrite multi-block change packets", throwable);
            trip(event, PacketSecurityState.ProtectedPath.MULTI_BLOCK_CHANGE, reason);
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
                // The light wrapper exposes the packet's live byte arrays, so
                // in-place sanitizing would corrupt the shared broadcast
                // instance for the other recipients. Work on a deep clone.
                event.setPacket(event.getPacket().deepClone());
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
                    settings.hideMinY(world),
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
            WrappedLevelChunkData.ChunkData chunkData,
            Player player,
            VeilSettings settings
    ) {
        World world = player.getWorld();
        long startNanos = System.nanoTime();
        int rewrittenSections = rewriterFor(settings.defaultFakeBlock(world))
                .rewriteHiddenSections(chunkData, world, settings.hideMinY(world), settings.hideBelowY(world), settings.hideAir(world));
        metrics.recordPacketChunkRewriteNanos(System.nanoTime() - startNanos);
        return rewrittenSections;
    }

    private void stripHiddenBlockEntities(WrappedLevelChunkData.ChunkData chunkData, int hideMinY, int hideBelowY) {
        List<WrappedLevelChunkData.BlockEntityInfo> blockEntities = chunkData.getBlockEntityInfo();
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        List<WrappedLevelChunkData.BlockEntityInfo> visibleBlockEntities = new ArrayList<>(blockEntities.size());
        for (WrappedLevelChunkData.BlockEntityInfo blockEntity : blockEntities) {
            int y = blockEntity.getY();
            // Below the concealed band the real blocks are sent, so their
            // block entities (chest contents, signs) must be kept too.
            if (y < hideMinY || y >= hideBelowY) {
                visibleBlockEntities.add(blockEntity);
            }
        }

        if (visibleBlockEntities.size() != blockEntities.size()) {
            chunkData.setBlockEntityInfo(visibleBlockEntities);
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
            return new ChunkPacketBlockRewriter(fakeBlockStateId, airStateIds, sectionBlockCountIsLeInt,
                    dataArrayLengthPrefixed, globalPaletteBits);
        });
    }

    private void trip(PacketEvent event, PacketSecurityState.ProtectedPath path, String reason) {
        trip(event, path, reason, null, 0, 0);
    }

    /**
     * Handles a runtime protection failure. The failing packet is always
     * cancelled, so nothing unprotected is ever sent. Below the configured
     * tolerance threshold the incident is logged (and the chunk re-requested
     * when coordinates are known) while protection keeps running; only
     * repeated failures within the window quarantine, because that pattern
     * means something is systematically wrong rather than one bad packet from
     * another plugin.
     */
    private void trip(PacketEvent event, PacketSecurityState.ProtectedPath path, String reason,
            World chunkWorld, int chunkX, int chunkZ) {
        metrics.countSecurityPacketCancelled();
        protectionHealth.failed(path, reason);
        event.setCancelled(true);

        int maxFailures = settings.runtimeFailureToleranceMaxFailures();
        long windowMillis = settings.runtimeFailureToleranceWindowMillis();
        long now = System.currentTimeMillis();
        if (!securityState.isTripped() && !securityState.recordFailure(now, maxFailures, windowMillis)) {
            logToleratedFailure(path, reason, securityState.recentFailureCount(now, windowMillis), maxFailures);
            if (chunkWorld != null) {
                requestChunkResend(chunkWorld, chunkX, chunkZ);
            }
            return;
        }

        String tripReason = maxFailures <= 1 ? reason
                : "Repeated packet protection failures (" + securityState.recentFailureCount(now, windowMillis)
                + " within " + settings.runtimeFailureToleranceWindowMinutes() + " min); last: " + reason;
        boolean firstTrip = securityState.tripAndCancel(path, tripReason, () -> event.setCancelled(true));
        if (path == PacketSecurityState.ProtectedPath.CHUNK) {
            packetBlockRewriteBroken.set(true);
        }
        if (firstTrip && failClosedScheduled.compareAndSet(false, true)) {
            VeilScheduler.runGlobal(plugin, () -> plugin.failClosed(tripReason));
        }
    }

    private void logToleratedFailure(PacketSecurityState.ProtectedPath path, String reason,
            int failureCount, int maxFailures) {
        plugin.getLogger().warning("Dropped one unprotectable " + path + " packet instead of sending it: " + reason);
        plugin.getLogger().warning("Failure " + failureCount + " of " + maxFailures + " tolerated per "
                + settings.runtimeFailureToleranceWindowMinutes()
                + " minutes. Protection stays active; repeated failures will quarantine.");
        if (path == PacketSecurityState.ProtectedPath.CHUNK) {
            String rewriters = describeOtherChunkPacketPlugins();
            if (!rewriters.isEmpty()) {
                plugin.getLogger().warning("Likely cause: these plugins also modify chunk packets and one of them"
                        + " probably produced this unreadable data: " + rewriters
                        + ". The chunk was re-requested; if this repeats, test without them.");
            } else {
                plugin.getLogger().warning("The chunk was re-requested. If this repeats, please open an issue"
                        + " with /chunkveil report attached.");
            }
        }
    }

    /**
     * Names plugins with actual evidence of touching chunk packets: other
     * ProtocolLib listeners registered for MAP_CHUNK, plus known chunk
     * rewriters that hook the pipeline directly. Only used when a chunk
     * failure has actually happened — never as a speculative startup warning.
     */
    private String describeOtherChunkPacketPlugins() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        try {
            for (com.comphenix.protocol.events.PacketListener registered : protocolManager.getPacketListeners()) {
                org.bukkit.plugin.Plugin owner = registered.getPlugin();
                if (owner == null || owner.equals(plugin)) {
                    continue;
                }
                if (registered.getSendingWhitelist() != null
                        && registered.getSendingWhitelist().getTypes().contains(PacketType.Play.Server.MAP_CHUNK)) {
                    names.add(owner.getName());
                }
            }
        } catch (Throwable ignored) {
            // Best-effort diagnostics only.
        }
        for (String known : new String[] {"Orebfuscator", "RayTraceAntiXray", "raytraced-antixray"}) {
            org.bukkit.plugin.Plugin candidate = Bukkit.getPluginManager().getPlugin(known);
            if (candidate != null) {
                names.add(candidate.getName());
            }
        }
        return String.join(", ", names);
    }

    private void requestChunkResend(World world, int chunkX, int chunkZ) {
        VeilScheduler.runOnRegion(plugin, world, chunkX, chunkZ, () -> {
            if (!securityState.isTripped() && world.isChunkLoaded(chunkX, chunkZ)) {
                // The re-sent packet is freshly serialized and passes through
                // the rewrite again, so the viewer gets the chunk properly
                // protected a moment later instead of keeping a hole.
                world.refreshChunk(chunkX, chunkZ);
            }
        });
    }

    int recentToleratedFailures() {
        if (securityState.isTripped()) {
            return 0;
        }
        return securityState.recentFailureCount(System.currentTimeMillis(),
                settings.runtimeFailureToleranceWindowMillis());
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

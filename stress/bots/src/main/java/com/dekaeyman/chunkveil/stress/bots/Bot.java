package com.dekaeyman.chunkveil.stress.bots;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;

/** One fake player. All state changes happen on the bot's packet thread or the shared ticker. */
final class Bot {
    private final String name;
    private final char role;
    private final StressBots.Options options;
    private final StressBots.BotStats stats;
    private final Random random;
    private final Map<Long, int[]> surfaceHeights = new ConcurrentHashMap<>();
    private final int heightmapBits;

    private volatile ClientNetworkSession session;
    volatile boolean connected;
    volatile boolean spawned;
    private volatile boolean shuttingDown;
    private volatile boolean informationSent;
    private volatile boolean loadedSent;

    // Position and look, written by the packet thread (teleports) and the
    // ticker (movement); a teleport wins over an in-flight movement step.
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;
    private double heading;
    private long ticks;
    private volatile long lastPositionNanos;

    Bot(String name, char role, StressBots.Options options, StressBots.BotStats stats, Random random) {
        this.name = name;
        this.role = role;
        this.options = options;
        this.stats = stats;
        this.random = random;
        this.heading = random.nextDouble() * Math.PI * 2;
        this.heightmapBits = Integer.SIZE - Integer.numberOfLeadingZeros(options.worldHeight);
    }

    void connect() {
        informationSent = false;
        loadedSent = false;
        spawned = false;
        MinecraftProtocol protocol = new MinecraftProtocol(name);
        ClientNetworkSession created = ClientNetworkSessionFactory.factory()
                .setAddress(options.host, options.port)
                .setProtocol(protocol)
                .create();
        created.addListener(new Listener());
        session = created;
        created.connect(false);
    }

    void shutdown() {
        shuttingDown = true;
        ClientNetworkSession current = session;
        if (current != null && current.isConnected()) {
            current.disconnect("stress test finished");
        }
    }

    /** Called every 50 ms by the shared ticker. */
    void tick() {
        ClientNetworkSession current = session;
        if (!spawned || current == null || !current.isConnected()) {
            return;
        }
        ticks++;
        switch (role) {
            case 's' -> tickSurface(current);
            case 'c' -> tickCave(current);
            default -> tickIdle(current);
        }
    }

    private void tickSurface(Session current) {
        // Position goes out every second tick (the server allows at most one
        // position packet per server tick and kicks on a second one), so each
        // step covers two ticks; the head still turns every tick.
        if (ticks % 2 != 0) {
            float turnYaw = yaw;
            float turnPitch = (float) (15.0 + 25.0 * Math.sin(ticks / 40.0));
            rotate(current, turnYaw, turnPitch);
            return;
        }
        double step = options.speedBlocksPerSecond / 10.0;
        double distance = Math.hypot(x, z);
        if (distance > options.radius) {
            // Head back toward the middle of the pre-generated area.
            heading = Math.atan2(-z, -x) + (random.nextDouble() - 0.5) * 0.6;
        } else if (random.nextInt(200) == 0) {
            heading += (random.nextDouble() - 0.5) * Math.PI;
        }
        double nextX = x + Math.cos(heading) * step;
        double nextZ = z + Math.sin(heading) * step;
        double nextY = y;
        // Clearance over the 3x3 columns around the next position and two
        // blocks further ahead, so the player's box never touches terrain
        // (the server silently teleports a colliding player back).
        Integer clearance = clearanceY(nextX, nextZ);
        Integer ahead = clearanceY(nextX + Math.cos(heading) * 2, nextZ + Math.sin(heading) * 2);
        if (ahead != null && (clearance == null || ahead > clearance)) {
            clearance = ahead;
        }
        if (clearance != null) {
            double target = clearance + 0.3;
            if (target > y + 0.05) {
                // Rise straight up first (air above us cannot collide), move on next step.
                nextX = x;
                nextZ = z;
                nextY = Math.min(target, y + 4.0);
            } else {
                nextY = Math.max(target, y - 0.5);
            }
        }
        // Look where it goes (Minecraft yaw 0 faces +Z: yaw = atan2(-dx, dz)),
        // with a slow scan up and down.
        float nextYaw = (float) Math.toDegrees(Math.atan2(-Math.cos(heading), Math.sin(heading)));
        float nextPitch = (float) (15.0 + 25.0 * Math.sin(ticks / 40.0));
        move(current, nextX, nextY, nextZ, nextYaw, nextPitch);
    }

    private void tickCave(Session current) {
        float turnPerTick = (float) (options.turnDegreesPerSecond / 20.0);
        float nextYaw = wrapDegrees(yaw + turnPerTick);
        float nextPitch = (float) (10.0 + 35.0 * Math.sin(ticks / 30.0));
        rotate(current, nextYaw, nextPitch);
    }

    private void tickIdle(Session current) {
        // Like an AFK client: a position packet once a second.
        if (ticks % 20 == 0) {
            move(current, x, y, z, yaw, pitch);
        }
    }

    private void rotate(Session current, float nextYaw, float nextPitch) {
        yaw = nextYaw;
        pitch = nextPitch;
        current.send(new ServerboundMovePlayerRotPacket(false, false, nextYaw, nextPitch));
    }

    private void move(Session current, double nextX, double nextY, double nextZ, float nextYaw, float nextPitch) {
        long now = System.nanoTime();
        // Never two position packets within one server tick (kick), and none
        // right after accepting a teleport.
        if (now - lastPositionNanos < 90_000_000L) {
            rotate(current, nextYaw, nextPitch);
            return;
        }
        lastPositionNanos = now;
        x = nextX;
        y = nextY;
        z = nextZ;
        yaw = nextYaw;
        pitch = nextPitch;
        current.send(new ServerboundMovePlayerPosRotPacket(false, false, nextX, nextY, nextZ, nextYaw, nextPitch));
    }

    /** Highest surface (first free y) of the 3x3 columns around a position, or null if unknown. */
    private Integer clearanceY(double atX, double atZ) {
        int blockX = (int) Math.floor(atX);
        int blockZ = (int) Math.floor(atZ);
        Integer highest = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Integer surface = surfaceY(blockX + dx + 0.5, blockZ + dz + 0.5);
                if (surface == null) {
                    return null;
                }
                if (highest == null || surface > highest) {
                    highest = surface;
                }
            }
        }
        return highest;
    }

    private Integer surfaceY(double atX, double atZ) {
        int blockX = (int) Math.floor(atX);
        int blockZ = (int) Math.floor(atZ);
        int[] heights = surfaceHeights.get(chunkKey(blockX >> 4, blockZ >> 4));
        if (heights == null) {
            return null;
        }
        return heights[(blockX & 15) + (blockZ & 15) * 16];
    }

    private void storeHeightmap(ClientboundLevelChunkWithLightPacket packet) {
        long[] data = packet.getHeightMaps().get(HeightmapTypes.MOTION_BLOCKING);
        if (data == null || data.length == 0 || role != 's') {
            return;
        }
        int bits = heightmapBits;
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int[] heights = new int[256];
        for (int i = 0; i < 256; i++) {
            int longIndex = i / perLong;
            if (longIndex >= data.length) {
                return;
            }
            int shift = (i % perLong) * bits;
            heights[i] = options.minY + (int) ((data[longIndex] >>> shift) & mask);
        }
        surfaceHeights.put(chunkKey(packet.getX(), packet.getZ()), heights);
    }

    // 26.3 added the position to the teleport accept; 26.2 and older only send
    // the id. Resolved once, so one source builds against either MCPL version.
    private static final java.lang.reflect.Constructor<?> ACCEPT_WITH_POSITION;
    private static final java.lang.reflect.Constructor<?> ACCEPT_ID_ONLY;

    static {
        java.lang.reflect.Constructor<?> withPosition = null;
        java.lang.reflect.Constructor<?> idOnly = null;
        for (java.lang.reflect.Constructor<?> constructor : ServerboundAcceptTeleportationPacket.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 6 && types[0] == int.class) {
                withPosition = constructor;
            } else if (types.length == 1 && types[0] == int.class) {
                idOnly = constructor;
            }
        }
        ACCEPT_WITH_POSITION = withPosition;
        ACCEPT_ID_ONLY = idOnly;
    }

    private static Packet acceptTeleport(int id, double x, double y, double z, float yaw, float pitch) {
        try {
            if (ACCEPT_WITH_POSITION != null) {
                return (Packet) ACCEPT_WITH_POSITION.newInstance(id, x, y, z, yaw, pitch);
            }
            return (Packet) ACCEPT_ID_ONLY.newInstance(id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot build teleport accept packet", exception);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private final class Listener extends SessionAdapter {
        @Override
        public void packetReceived(Session session, Packet packet) {
            MinecraftProtocol protocol = session.getPacketProtocol();
            if (!informationSent && protocol.getInboundState() == ProtocolState.CONFIGURATION) {
                // Without this the server assumes view distance 2.
                informationSent = true;
                session.send(new ServerboundClientInformationPacket("en_us", options.viewDistance,
                        ChatVisibility.FULL, true, List.of(SkinPart.values()), HandPreference.RIGHT_HAND,
                        false, true, ParticleStatus.MINIMAL));
            }
            if (packet instanceof ClientboundPlayerPositionPacket teleport) {
                handleTeleport(session, teleport);
            } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                session.send(new ServerboundChunkBatchReceivedPacket(options.chunksPerTick));
            } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                stats.chunksReceived.incrementAndGet();
                storeHeightmap(chunk);
            } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                surfaceHeights.remove(chunkKey(forget.getX(), forget.getZ()));
            } else if (packet instanceof ClientboundSetHealthPacket health && health.getHealth() <= 0) {
                stats.deaths.incrementAndGet();
                session.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
            }
        }

        private void handleTeleport(Session session, ClientboundPlayerPositionPacket teleport) {
            List<PositionElement> relative = teleport.getRelatives();
            double newX = teleport.getPosition().getX() + (relative.contains(PositionElement.X) ? x : 0);
            double newY = teleport.getPosition().getY() + (relative.contains(PositionElement.Y) ? y : 0);
            double newZ = teleport.getPosition().getZ() + (relative.contains(PositionElement.Z) ? z : 0);
            float newYaw = teleport.getYRot() + (relative.contains(PositionElement.Y_ROT) ? yaw : 0);
            float newPitch = teleport.getXRot() + (relative.contains(PositionElement.X_ROT) ? pitch : 0);
            x = newX;
            y = newY;
            z = newZ;
            yaw = newYaw;
            pitch = newPitch;
            stats.teleports.incrementAndGet();
            // The accept carries the position; a separate position packet
            // right after it could be the second one in a server tick.
            lastPositionNanos = System.nanoTime();
            session.send(acceptTeleport(teleport.getId(), newX, newY, newZ, newYaw, newPitch));
            if (!loadedSent) {
                loadedSent = true;
                session.send(ServerboundPlayerLoadedPacket.INSTANCE);
            }
            connected = true;
            spawned = true;
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            boolean wasConnected = connected;
            connected = false;
            spawned = false;
            surfaceHeights.clear();
            if (shuttingDown) {
                return;
            }
            stats.disconnects.incrementAndGet();
            String reason = String.valueOf(event.getReason());
            if (reason.length() > 120) {
                reason = reason.substring(0, 120);
            }
            stats.disconnectReasons.merge(reason, 1, Integer::sum);
            if (options.reconnect) {
                long delay = wasConnected ? 5 : 10;
                CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(() -> {
                    if (!shuttingDown) {
                        stats.reconnects.incrementAndGet();
                        connect();
                    }
                });
            }
        }
    }
}

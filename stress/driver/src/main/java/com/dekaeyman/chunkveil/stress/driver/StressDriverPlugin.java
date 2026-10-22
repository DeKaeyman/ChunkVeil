package com.dekaeyman.chunkveil.stress.driver;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Server side of the ChunkVeil stress test. Test servers only: it teleports,
 * re-modes and breaks blocks around players whose name starts with the bot
 * prefix.
 *
 * <p>Bot roles come from the name: prefix + role letter + number, role
 * {@code s} surface, {@code c} cave, {@code i} idle (see the bot client).
 * Everything runs on the owning thread on Folia (player scheduler, region
 * scheduler) and on the main thread on Paper.
 */
public final class StressDriverPlugin extends JavaPlugin implements Listener {
    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private String prefix;
    private int radius;
    private int caveMinY;
    private int caveMaxY;
    private int caveMoveSeconds;
    private volatile int minePerSecond;
    private long seed;

    private final Map<UUID, Long> nextCaveMove = new ConcurrentHashMap<>();
    private final Map<Integer, RegionSample> regionSamples = new ConcurrentHashMap<>();
    private final AtomicLong blocksBroken = new AtomicLong();
    private final AtomicLong mobsSpawned = new AtomicLong();
    private final AtomicLong caveTeleports = new AtomicLong();
    private final AtomicLong caveSearchFailures = new AtomicLong();
    private final AtomicInteger pregenDone = new AtomicInteger();
    private final AtomicInteger pregenTotal = new AtomicInteger();
    private volatile boolean pregenRunning;
    private volatile long pregenStartedAt;
    private FoliaRegionProbe regionProbe;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        if (FOLIA) {
            regionProbe = FoliaRegionProbe.create(getLogger());
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isBot(player)) {
                adopt(player);
            }
        }
        getLogger().info("Stress driver ready (" + (FOLIA ? "Folia" : "Paper") + ", bot prefix '" + prefix + "').");
    }

    private void loadSettings() {
        reloadConfig();
        prefix = getConfig().getString("bot-prefix", "cvb");
        radius = getConfig().getInt("radius", 700);
        caveMinY = getConfig().getInt("cave-min-y", -56);
        caveMaxY = getConfig().getInt("cave-max-y", -8);
        caveMoveSeconds = getConfig().getInt("cave-move-seconds", 45);
        minePerSecond = getConfig().getInt("mine-blocks-per-second", 0);
        seed = getConfig().getLong("seed", 1L);
    }

    private boolean isBot(Player player) {
        return player.getName().startsWith(prefix) && player.getName().length() > prefix.length();
    }

    private char role(Player player) {
        return player.getName().charAt(prefix.length());
    }

    // ------------------------------------------------------------------
    // Bot lifecycle
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isBot(player)) {
            event.joinMessage(null);
            adopt(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isBot(event.getPlayer())) {
            event.quitMessage(null);
            nextCaveMove.remove(event.getPlayer().getUniqueId());
        }
    }

    /** Runs on the player's thread: creative flight, then a start position and the per-bot loop. */
    private void adopt(Player player) {
        player.getScheduler().run(this, task -> {
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setInvulnerable(true);
            if (role(player) == 'c') {
                moveToCave(player);
            } else {
                moveToSurface(player);
            }
        }, null);
        player.getScheduler().runAtFixedRate(this, task -> botTick(player), null, 20L, 20L);
    }

    /** Once a second per bot, on the bot's own thread. */
    private void botTick(Player player) {
        if (FOLIA && regionProbe != null) {
            RegionSample sample = regionProbe.sampleCurrentRegion();
            if (sample != null) {
                regionSamples.put(sample.regionId(), sample);
            }
        }
        if (role(player) != 'c') {
            return;
        }
        long now = System.currentTimeMillis();
        Long due = nextCaveMove.get(player.getUniqueId());
        if (due != null && now >= due) {
            moveToCave(player);
        }
        int mine = minePerSecond;
        if (mine > 0) {
            Location at = player.getLocation();
            Bukkit.getRegionScheduler().execute(this, at, () -> mineAround(at, mine));
        }
    }

    private void moveToSurface(Player player) {
        Random random = new Random(seed ^ player.getName().hashCode() ^ System.nanoTime());
        World world = Bukkit.getWorlds().get(0);
        int x = random.nextInt(radius * 2) - radius;
        int z = random.nextInt(radius * 2) - radius;
        world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk ->
                Bukkit.getRegionScheduler().execute(this, world, x >> 4, z >> 4, () -> {
                    int y = world.getHighestBlockYAt(x, z) + 2;
                    player.teleportAsync(new Location(world, x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 10f));
                }));
    }

    /** Teleports a cave bot into underground air somewhere in the test area. */
    private void moveToCave(Player player) {
        nextCaveMove.put(player.getUniqueId(), System.currentTimeMillis() + caveMoveSeconds * 1000L);
        Random random = new Random(seed ^ player.getName().hashCode() ^ System.nanoTime());
        World world = Bukkit.getWorlds().get(0);
        tryCave(player, world, random, 6);
    }

    private void tryCave(Player player, World world, Random random, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            caveSearchFailures.incrementAndGet();
            return;
        }
        int chunkX = (random.nextInt(radius * 2) - radius) >> 4;
        int chunkZ = (random.nextInt(radius * 2) - radius) >> 4;
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk ->
                Bukkit.getRegionScheduler().execute(this, world, chunkX, chunkZ, () -> {
                    Location spot = findCaveAir(world, chunkX, chunkZ, random);
                    if (spot == null) {
                        tryCave(player, world, random, attemptsLeft - 1);
                        return;
                    }
                    player.teleportAsync(spot);
                    caveTeleports.incrementAndGet();
                }));
    }

    /** Two blocks of air with ground below, between cave-min-y and cave-max-y. Runs on the chunk's region. */
    private Location findCaveAir(World world, int chunkX, int chunkZ, Random random) {
        for (int column = 0; column < 24; column++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            for (int y = caveMaxY; y > caveMinY; y--) {
                Block feet = world.getBlockAt(x, y, z);
                if (feet.getType().isAir()
                        && world.getBlockAt(x, y + 1, z).getType().isAir()
                        && world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    return new Location(world, x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
                }
            }
        }
        return null;
    }

    /** Breaks a few solid blocks near a cave bot (block change packets). Runs on that location's region. */
    private void mineAround(Location center, int count) {
        Random random = new Random();
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            int x = center.getBlockX() + random.nextInt(9) - 4;
            int y = center.getBlockY() + random.nextInt(5) - 2;
            int z = center.getBlockZ() + random.nextInt(9) - 4;
            if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type.isSolid() && type != Material.BEDROCK) {
                block.setType(Material.AIR, false);
                blocksBroken.incrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats" -> sender.sendMessage(statsJson());
            case "pregen" -> pregen(sender, args);
            case "spread" -> {
                int moved = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isBot(player)) {
                        player.getScheduler().run(this, t -> {
                            if (role(player) == 'c') {
                                moveToCave(player);
                            } else {
                                moveToSurface(player);
                            }
                        }, null);
                        moved++;
                    }
                }
                sender.sendMessage("stress spread bots=" + moved);
            }
            case "mine" -> {
                minePerSecond = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                sender.sendMessage("stress mine blocksPerSecondPerCaveBot=" + minePerSecond);
            }
            case "mobs" -> spawnMobs(sender, args.length > 1 ? Integer.parseInt(args[1]) : 5);
            case "reload" -> {
                loadSettings();
                sender.sendMessage("stress config reloaded");
            }
            default -> sender.sendMessage("Usage: /stress <stats|pregen <radius>|pregen status|spread|mine <n>|mobs <n>|reload>");
        }
        return true;
    }

    private void spawnMobs(CommandSender sender, int perBot) {
        EntityType[] types = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER};
        int bots = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isBot(player) || role(player) != 'c') {
                continue;
            }
            bots++;
            player.getScheduler().run(this, t -> {
                Location at = player.getLocation();
                Bukkit.getRegionScheduler().execute(this, at, () -> {
                    Random random = new Random();
                    for (int i = 0; i < perBot; i++) {
                        Location spot = at.clone().add(random.nextInt(11) - 5, 0, random.nextInt(11) - 5);
                        if (!Bukkit.isOwnedByCurrentRegion(spot)) {
                            continue;
                        }
                        if (spot.getBlock().getType().isAir()) {
                            at.getWorld().spawnEntity(spot, types[random.nextInt(types.length)]).setPersistent(true);
                            mobsSpawned.incrementAndGet();
                        }
                    }
                });
            }, null);
        }
        sender.sendMessage("stress mobs perCaveBot=" + perBot + " caveBots=" + bots);
    }

    private void pregen(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(String.format(Locale.ROOT, "stress pregen running=%s done=%d total=%d seconds=%d",
                    pregenRunning, pregenDone.get(), pregenTotal.get(),
                    pregenStartedAt == 0 ? 0 : (System.currentTimeMillis() - pregenStartedAt) / 1000));
            return;
        }
        if (pregenRunning) {
            sender.sendMessage("stress pregen already running");
            return;
        }
        int blocks = args.length > 1 ? Integer.parseInt(args[1]) : radius + 200;
        World world = Bukkit.getWorlds().get(0);
        int chunkRadius = (blocks >> 4) + 1;
        List<int[]> chunks = new ArrayList<>();
        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                chunks.add(new int[]{x, z});
            }
        }
        pregenRunning = true;
        pregenStartedAt = System.currentTimeMillis();
        pregenDone.set(0);
        pregenTotal.set(chunks.size());
        sender.sendMessage("stress pregen started chunks=" + chunks.size() + " radiusBlocks=" + blocks);
        Thread worker = new Thread(() -> {
            Semaphore inFlight = new Semaphore(64);
            try {
                for (int[] c : chunks) {
                    inFlight.acquire();
                    world.getChunkAtAsync(c[0], c[1], true).whenComplete((chunk, error) -> {
                        pregenDone.incrementAndGet();
                        inFlight.release();
                    });
                }
                inFlight.acquire(64);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pregenRunning = false;
                getLogger().info("Pre-generation finished: " + pregenDone.get() + " chunks in "
                        + (System.currentTimeMillis() - pregenStartedAt) / 1000 + " s");
            }
        }, "stress-pregen");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    /** One JSON line with server load; rates are computed by the caller from two polls. */
    private String statsJson() {
        int players = 0;
        int bots = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            players++;
            if (isBot(player)) {
                bots++;
            }
        }
        StringBuilder json = new StringBuilder("{");
        field(json, "platform", FOLIA ? "folia" : "paper");
        field(json, "timeMillis", System.currentTimeMillis());
        field(json, "players", players);
        field(json, "bots", bots);
        if (FOLIA) {
            long now = System.currentTimeMillis();
            regionSamples.values().removeIf(sample -> now - sample.sampledAtMillis() > 5000);
            double worstMspt = 0;
            double sumMspt = 0;
            double minTps = 20;
            double sumTps = 0;
            double sumUtil = 0;
            int regions = 0;
            for (RegionSample sample : regionSamples.values()) {
                regions++;
                worstMspt = Math.max(worstMspt, sample.mspt());
                sumMspt += sample.mspt();
                minTps = Math.min(minTps, sample.tps());
                sumTps += sample.tps();
                sumUtil += sample.utilisation();
            }
            field(json, "regions", regions);
            field(json, "tps", regions == 0 ? 20.0 : sumTps / regions);
            field(json, "minRegionTps", regions == 0 ? 20.0 : minTps);
            field(json, "mspt", regions == 0 ? 0.0 : sumMspt / regions);
            field(json, "worstRegionMspt", worstMspt);
            field(json, "regionUtilisationSum", sumUtil);
        } else {
            double[] tps = Bukkit.getTPS();
            long[] tickTimes = Bukkit.getTickTimes();
            long max = 0;
            long[] sorted = tickTimes.clone();
            java.util.Arrays.sort(sorted);
            for (long t : tickTimes) {
                max = Math.max(max, t);
            }
            field(json, "tps", tps.length == 0 ? 20.0 : tps[0]);
            field(json, "mspt", Bukkit.getAverageTickTime());
            field(json, "msptP95", sorted.length == 0 ? 0.0 : sorted[Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1)] / 1e6);
            field(json, "msptMax", max / 1e6);
        }
        int entities = 0;
        int chunks = 0;
        if (!FOLIA) {
            for (World world : Bukkit.getWorlds()) {
                entities += world.getEntityCount();
                chunks += world.getChunkCount();
            }
        }
        field(json, "entities", FOLIA ? -1 : entities);
        field(json, "loadedChunks", FOLIA ? -1 : chunks);
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        field(json, "heapUsedMb", memory.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        long gcCount = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        field(json, "gcCount", gcCount);
        field(json, "gcMillis", gcMillis);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            field(json, "processCpuNanos", os.getProcessCpuTime());
            field(json, "processCpuLoad", os.getProcessCpuLoad());
        }
        field(json, "threads", ManagementFactory.getThreadMXBean().getThreadCount());
        field(json, "blocksBroken", blocksBroken.get());
        field(json, "mobsSpawned", mobsSpawned.get());
        field(json, "caveTeleports", caveTeleports.get());
        field(json, "caveSearchFailures", caveSearchFailures.get());
        json.setLength(json.length() - 1);
        return json.append('}').toString();
    }

    private static void field(StringBuilder json, String key, Object value) {
        json.append('"').append(key).append("\":");
        if (value instanceof String text) {
            json.append('"').append(text).append('"');
        } else if (value instanceof Double number) {
            json.append(Double.isFinite(number) ? String.format(Locale.ROOT, "%.4f", number) : "null");
        } else {
            json.append(value);
        }
        json.append(',');
    }

    record RegionSample(int regionId, double tps, double mspt, double utilisation, long sampledAtMillis) {
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}

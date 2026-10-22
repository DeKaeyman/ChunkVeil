package com.dekaeyman.chunkveil.stress.bots;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless fake players for ChunkVeil stress tests.
 *
 * <p>Each bot is a real network client (MCProtocolLib), so every chunk,
 * block and entity packet the server sends it goes through ProtocolLib and
 * ChunkVeil exactly like for a real player. Bots answer keep-alives,
 * teleports and chunk batches (so the server keeps streaming chunks at full
 * speed) and move according to their role, encoded in the name so the
 * server-side stress driver plugin can tell them apart:
 * <ul>
 *   <li>{@code s} surface: flies over the terrain (using the chunk
 *       heightmaps) in long straight lines with turns, loading new chunks,
 *       looking around while it goes.</li>
 *   <li>{@code c} cave: stays where the driver teleports it (underground
 *       air) and keeps turning its head, which drives ChunkVeil's reveal
 *       scans.</li>
 *   <li>{@code i} idle: stands still, like an AFK player.</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar chunkveil-stress-bots.jar --count 100 --port 25565 ...}
 * (see {@link Options#usage()}).
 */
public final class StressBots {
    private StressBots() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options == null) {
            System.out.println(Options.usage());
            return;
        }
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");

        BotStats stats = new BotStats();
        List<Bot> bots = new ArrayList<>();
        Random random = new Random(options.seed);
        List<Character> roles = options.roleSequence(random);
        for (int i = 0; i < options.count; i++) {
            char role = roles.get(i);
            String name = String.format(Locale.ROOT, "%s%c%04d", options.prefix, role, i + 1);
            bots.add(new Bot(name, role, options, stats, new Random(options.seed * 31L + i)));
        }

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bot-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(() -> {
            for (Bot bot : bots) {
                try {
                    bot.tick();
                } catch (Throwable throwable) {
                    stats.tickErrors.incrementAndGet();
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        ticker.scheduleAtFixedRate(() -> stats.report(bots, options.statusFile), 5, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Bot bot : bots) {
                bot.shutdown();
            }
            stats.report(bots, options.statusFile);
        }));

        System.out.printf(Locale.ROOT, "Connecting %d bots to %s:%d (%s), one every %d ms%n",
                options.count, options.host, options.port, options.mixDescription(), options.joinIntervalMillis);
        for (Bot bot : bots) {
            bot.connect();
            Thread.sleep(options.joinIntervalMillis);
        }
        System.out.println("All bots started.");

        if (options.durationSeconds > 0) {
            Thread.sleep(options.durationSeconds * 1000L);
            System.exit(0);
        } else {
            Thread.currentThread().join();
        }
    }

    /** Shared counters, printed every 5 s and optionally written as JSON. */
    static final class BotStats {
        final AtomicLong chunksReceived = new AtomicLong();
        final AtomicLong teleports = new AtomicLong();
        final AtomicLong disconnects = new AtomicLong();
        final AtomicLong reconnects = new AtomicLong();
        final AtomicLong deaths = new AtomicLong();
        final AtomicLong tickErrors = new AtomicLong();
        final Map<String, Integer> disconnectReasons = new ConcurrentHashMap<>();

        void report(List<Bot> bots, Path statusFile) {
            int connected = 0;
            int spawned = 0;
            for (Bot bot : bots) {
                if (bot.connected) {
                    connected++;
                }
                if (bot.spawned) {
                    spawned++;
                }
            }
            String line = String.format(Locale.ROOT,
                    "bots total=%d connected=%d spawned=%d chunks=%d teleports=%d disconnects=%d reconnects=%d deaths=%d",
                    bots.size(), connected, spawned, chunksReceived.get(), teleports.get(), disconnects.get(),
                    reconnects.get(), deaths.get());
            System.out.println(line);
            if (statusFile != null) {
                StringBuilder json = new StringBuilder();
                json.append("{\"timeMillis\":").append(System.currentTimeMillis())
                        .append(",\"total\":").append(bots.size())
                        .append(",\"connected\":").append(connected)
                        .append(",\"spawned\":").append(spawned)
                        .append(",\"chunks\":").append(chunksReceived.get())
                        .append(",\"teleports\":").append(teleports.get())
                        .append(",\"disconnects\":").append(disconnects.get())
                        .append(",\"reconnects\":").append(reconnects.get())
                        .append(",\"deaths\":").append(deaths.get())
                        .append(",\"tickErrors\":").append(tickErrors.get())
                        .append(",\"disconnectReasons\":{");
                boolean first = true;
                for (Map.Entry<String, Integer> entry : disconnectReasons.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
                }
                json.append("}}\n");
                try {
                    Files.writeString(statusFile, json.toString(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Status is best effort.
                }
            }
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
        }
    }

    /** Command line options. */
    static final class Options {
        String host = "127.0.0.1";
        int port = 25565;
        int count = 20;
        long joinIntervalMillis = 250;
        String prefix = "cvb";
        int surfaceShare = 50;
        int caveShare = 40;
        int idleShare = 10;
        int viewDistance = 10;
        int minY = -64;
        int worldHeight = 384;
        double speedBlocksPerSecond = 5.6;
        double radius = 700;
        double turnDegreesPerSecond = 45;
        float chunksPerTick = 20f;
        long seed = 1;
        int durationSeconds = 0;
        boolean reconnect = true;
        Path statusFile;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (key.equals("--help") || key.equals("-h")) {
                    return null;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + key);
                }
                String value = args[++i];
                switch (key) {
                    case "--host" -> options.host = value;
                    case "--port" -> options.port = Integer.parseInt(value);
                    case "--count" -> options.count = Integer.parseInt(value);
                    case "--join-interval-ms" -> options.joinIntervalMillis = Long.parseLong(value);
                    case "--prefix" -> options.prefix = value;
                    case "--mix" -> options.parseMix(value);
                    case "--view-distance" -> options.viewDistance = Integer.parseInt(value);
                    case "--min-y" -> options.minY = Integer.parseInt(value);
                    case "--world-height" -> options.worldHeight = Integer.parseInt(value);
                    case "--speed" -> options.speedBlocksPerSecond = Double.parseDouble(value);
                    case "--radius" -> options.radius = Double.parseDouble(value);
                    case "--turn" -> options.turnDegreesPerSecond = Double.parseDouble(value);
                    case "--chunks-per-tick" -> options.chunksPerTick = Float.parseFloat(value);
                    case "--seed" -> options.seed = Long.parseLong(value);
                    case "--duration-seconds" -> options.durationSeconds = Integer.parseInt(value);
                    case "--reconnect" -> options.reconnect = Boolean.parseBoolean(value);
                    case "--status-file" -> options.statusFile = Path.of(value);
                    default -> throw new IllegalArgumentException("Unknown option " + key + "\n" + usage());
                }
            }
            if (options.prefix.length() + 5 > 16) {
                throw new IllegalArgumentException("Prefix too long: names are prefix + role + 4 digits, max 16 chars");
            }
            return options;
        }

        void parseMix(String value) {
            surfaceShare = 0;
            caveShare = 0;
            idleShare = 0;
            for (String part : value.split(",")) {
                String[] kv = part.split("=");
                int share = Integer.parseInt(kv[1].trim());
                switch (kv[0].trim()) {
                    case "surface" -> surfaceShare = share;
                    case "cave" -> caveShare = share;
                    case "idle" -> idleShare = share;
                    default -> throw new IllegalArgumentException("Unknown role in --mix: " + kv[0]);
                }
            }
        }

        /** Roles spread evenly over the join order, so every ramp stage has the same mix. */
        List<Character> roleSequence(Random random) {
            int total = Math.max(1, surfaceShare + caveShare + idleShare);
            List<Character> roles = new ArrayList<>(count);
            double surface = 0;
            double cave = 0;
            double idle = 0;
            for (int i = 0; i < count; i++) {
                surface += surfaceShare / (double) total;
                cave += caveShare / (double) total;
                idle += idleShare / (double) total;
                if (surface >= cave && surface >= idle) {
                    roles.add('s');
                    surface -= 1;
                } else if (cave >= idle) {
                    roles.add('c');
                    cave -= 1;
                } else {
                    roles.add('i');
                    idle -= 1;
                }
            }
            return roles;
        }

        String mixDescription() {
            return "surface=" + surfaceShare + ",cave=" + caveShare + ",idle=" + idleShare;
        }

        static String usage() {
            Map<String, String> lines = new LinkedHashMap<>();
            lines.put("--host <host>", "server host (127.0.0.1)");
            lines.put("--port <port>", "server port (25565)");
            lines.put("--count <n>", "number of bots (20)");
            lines.put("--join-interval-ms <ms>", "delay between joins (250)");
            lines.put("--prefix <text>", "name prefix, names are prefix+role+0001 (cvb)");
            lines.put("--mix surface=50,cave=40,idle=10", "share of each role");
            lines.put("--view-distance <n>", "client view distance sent to the server (10)");
            lines.put("--min-y <y> / --world-height <h>", "world bounds for heightmaps (-64 / 384)");
            lines.put("--speed <b/s>", "surface bot speed (5.6, sprinting)");
            lines.put("--radius <blocks>", "surface bots turn back beyond this distance from 0,0 (700)");
            lines.put("--turn <deg/s>", "head turn speed of cave bots (45)");
            lines.put("--chunks-per-tick <n>", "chunk batch rate requested from the server (20)");
            lines.put("--seed <n>", "random seed (1)");
            lines.put("--duration-seconds <s>", "exit after this long, 0 = run until stopped (0)");
            lines.put("--reconnect <true|false>", "reconnect after a disconnect (true)");
            lines.put("--status-file <path>", "write a JSON status line every 5 s");
            StringBuilder out = new StringBuilder("ChunkVeil stress bots\n");
            lines.forEach((k, v) -> out.append(String.format(Locale.ROOT, "  %-38s %s%n", k, v)));
            return out.toString();
        }
    }
}

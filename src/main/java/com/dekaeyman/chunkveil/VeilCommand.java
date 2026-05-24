package com.dekaeyman.chunkveil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class VeilCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("status", "compat", "inspect", "report", "predict", "reload", "refresh", "disable", "enable", "debug", "version");
    private static final DateTimeFormatter REPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long REQUIRED_REVEAL_PREDICT_SAMPLES = 30L;
    private static final long REQUIRED_CHUNK_MASK_PREDICT_SAMPLES = 5L;
    private static final long REQUIRED_ENTITY_PREDICT_SAMPLES = 10L;
    private static final long REQUIRED_QUEUE_PREDICT_SAMPLES = 100L;

    private final ChunkVeilPlugin plugin;

    VeilCommand(ChunkVeilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "status" -> sendStatus(sender);
            case "compat" -> sendCompat(sender);
            case "inspect" -> inspect(sender, args);
            case "report" -> report(sender);
            case "predict" -> predict(sender, args);
            case "reload" -> reload(sender);
            case "refresh" -> refresh(sender);
            case "disable", "off" -> disable(sender);
            case "enable", "on" -> enable(sender);
            case "debug" -> debug(sender, args);
            case "version" -> version(sender);
            default -> {
                sender.sendMessage(lang().message("commands.help.unknown-command"));
                sendHelp(sender, label);
            }
        }
        return true;
    }

    private void version(CommandSender sender) {
        if (!canUse(sender, "chunkveil.version")) {
            deny(sender);
            return;
        }
        sender.sendMessage(lang().message("commands.version", Map.of(
                "version", plugin.getDescription().getVersion()
        )));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(prefix) && canUse(sender, permissionFor(subcommand))) {
                    matches.add(subcommand);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug") && canUse(sender, "chunkveil.debug")) {
            return List.of("on", "off");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect") && canUse(sender, "chunkveil.inspect")) {
            List<String> matches = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    matches.add(player.getName());
                }
            }
            return matches;
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender, String label) {
        boolean sentAny = false;
        for (String subcommand : SUBCOMMANDS) {
            if (!canUse(sender, permissionFor(subcommand))) {
                continue;
            }
            sender.sendMessage(lang().message("commands.help." + subcommand, Map.of("label", label)));
            sentAny = true;
        }
        if (!sentAny) {
            sender.sendMessage(lang().message("commands.help.no-commands"));
        }
    }

    private void sendStatus(CommandSender sender) {
        if (!canUse(sender, "chunkveil.status")) {
            deny(sender);
            return;
        }

        VeilSettings settings = plugin.settings();
        VeilMetrics metrics = plugin.metrics();
        VeilEngine veilEngine = plugin.veilEngine();
        int trackedPlayers = veilEngine == null ? 0 : veilEngine.trackedPlayerCount();
        int queuedChunks = veilEngine == null ? 0 : veilEngine.queuedChunkCount();

        sender.sendMessage(lang().message("commands.status.title"));
        sender.sendMessage(lang().message("commands.status.heading"));
        sender.sendMessage(lang().message("commands.status.section-runtime"));
        sender.sendMessage(lang().message("commands.status.runtime", Map.of(
                "badge", badge(plugin.veilRuntimeEnabled()),
                "state", state(plugin.veilRuntimeEnabled())
        )));
        if (!plugin.veilRuntimeEnabled()) {
            sender.sendMessage(lang().message("commands.status.disabled-reason", Map.of(
                    "badge", failBadge(),
                    "reason", plugin.runtimeDisabledReason()
            )));
        }
        sender.sendMessage(lang().message("commands.status.debug", Map.of(
                "badge", infoBadge(),
                "state", state(plugin.debugEnabled())
        )));

        sender.sendMessage(lang().message("commands.status.section-compat"));
        sender.sendMessage(lang().message("commands.status.listener", Map.of(
                "badge", badge(plugin.protocolListenerActive()),
                "state", state(plugin.protocolListenerActive())
        )));
        sender.sendMessage(lang().message("commands.status.rewrite", Map.of(
                "badge", badge(plugin.packetRewriteActive()),
                "state", state(plugin.packetRewriteActive())
        )));

        sender.sendMessage(lang().message("commands.status.section-worlds"));
        sender.sendMessage(lang().message("commands.status.worlds-enabled", Map.of(
                "badge", settings.enabledWorlds().isEmpty() ? warnBadge() : okBadge(),
                "worlds", worlds(settings.enabledWorlds())
        )));
        if (!settings.worlds().isEmpty()) {
            sender.sendMessage(lang().message("commands.status.world-overrides", Map.of(
                    "badge", infoBadge(),
                    "worlds", worlds(settings.worlds().keySet())
            )));
        }

        sender.sendMessage(lang().message("commands.status.section-players"));
        sender.sendMessage(lang().message("commands.status.tracked", Map.of(
                "badge", infoBadge(),
                "players", trackedPlayers
        )));
        sender.sendMessage(lang().message("commands.status.queued", Map.of(
                "badge", queuedChunks > 0 ? warnBadge() : okBadge(),
                "chunks", queuedChunks
        )));

        sender.sendMessage(lang().message("commands.status.section-protection"));
        sender.sendMessage(lang().message("commands.status.chunk-packets-total", Map.of(
                "badge", infoBadge(),
                "packets", metrics.chunkPackets()
        )));
        sender.sendMessage(lang().message("commands.status.chunk-packets-hidden", Map.of(
                "badge", infoBadge(),
                "hidden", metrics.hiddenChunkPackets()
        )));
        sender.sendMessage(lang().message("commands.status.chunk-packets-rewritten", Map.of(
                "badge", infoBadge(),
                "rewritten", metrics.rewrittenChunkPackets()
        )));
        sender.sendMessage(lang().message("commands.status.chunk-packets-unrewritten", Map.of(
                "badge", metrics.unrewrittenHiddenChunkPackets() > 0 ? warnBadge() : okBadge(),
                "unrewritten", metrics.unrewrittenHiddenChunkPackets()
        )));

        sender.sendMessage(lang().message("commands.status.section-updates"));
        sender.sendMessage(lang().message("commands.status.updates-block", Map.of(
                "badge", infoBadge(),
                "block", metrics.blockChangesRewritten()
        )));
        sender.sendMessage(lang().message("commands.status.updates-multi", Map.of(
                "badge", infoBadge(),
                "multi", metrics.multiBlockChangesRewritten()
        )));
        sender.sendMessage(lang().message("commands.status.updates-block-entities", Map.of(
                "badge", infoBadge(),
                "block_entities", metrics.blockEntityUpdatesCancelled()
        )));
        sender.sendMessage(lang().message("commands.status.entities", Map.of(
                "badge", infoBadge(),
                "spawns", metrics.entitySpawnsCancelled(),
                "packets", metrics.entityPacketsCancelled()
        )));
        sender.sendMessage(lang().message("commands.status.secondary-packets", Map.of(
                "badge", infoBadge(),
                "explosions", metrics.explosionPacketsCancelled(),
                "events", metrics.worldEventPacketsCancelled(),
                "cracks", metrics.blockBreakAnimationPacketsCancelled(),
                "sounds", metrics.soundPacketsCancelled()
        )));

        sender.sendMessage(lang().message("commands.status.section-timings"));
        sendTiming(sender, "Reveal scan", metrics.revealScanAverageMillis(), metrics.revealScanMaxMillis(), metrics.revealScanSamples());
        sendTiming(sender, "Packet chunk rewrite", metrics.packetChunkRewriteAverageMillis(), metrics.packetChunkRewriteMaxMillis(), metrics.packetChunkRewriteSamples());
        sendTiming(sender, "Chunk update masking", metrics.chunkUpdateMaskAverageMillis(), metrics.chunkUpdateMaskMaxMillis(), metrics.chunkUpdateMaskSamples());
        sendTiming(sender, "Entity scan", metrics.entityScanAverageMillis(), metrics.entityScanMaxMillis(), metrics.entityScanSamples());
        sendTiming(sender, "Queue processing", metrics.queueProcessingAverageMillis(), metrics.queueProcessingMaxMillis(), metrics.queueProcessingSamples());
    }

    private void sendTiming(CommandSender sender, String label, double averageMillis, double maxMillis, long samples) {
        sender.sendMessage(lang().message("commands.status.timing", Map.of(
                "badge", samples == 0L ? warnBadge() : infoBadge(),
                "metric", label,
                "avg", decimal(averageMillis),
                "max", decimal(maxMillis),
                "samples", samples
        )));
    }

    private void sendCompat(CommandSender sender) {
        if (!canUse(sender, "chunkveil.compat")) {
            deny(sender);
            return;
        }

        VeilSettings settings = plugin.settings();
        Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        List<String> warnings = compatibilityWarnings(protocolLib, settings);

        sender.sendMessage(lang().message("commands.compat.title"));
        sender.sendMessage(lang().message("commands.compat.heading"));

        sender.sendMessage(lang().message("commands.compat.section-server"));
        sender.sendMessage(lang().message("commands.compat.minecraft", Map.of(
                "badge", infoBadge(),
                "version", Bukkit.getMinecraftVersion()
        )));
        sender.sendMessage(lang().message("commands.compat.server", Map.of(
                "badge", infoBadge(),
                "version", Bukkit.getVersion()
        )));
        sender.sendMessage(lang().message("commands.compat.bukkit", Map.of(
                "badge", infoBadge(),
                "version", Bukkit.getBukkitVersion()
        )));
        sender.sendMessage(lang().message("commands.compat.java", Map.of(
                "badge", infoBadge(),
                "version", Runtime.version()
        )));

        sender.sendMessage(lang().message("commands.compat.section-protocollib"));
        sender.sendMessage(lang().message("commands.compat.protocollib", Map.of(
                "badge", protocolLib != null && protocolLib.isEnabled() ? okBadge() : failBadge(),
                "version", protocolLibVersion(protocolLib)
        )));
        sender.sendMessage(lang().message("commands.compat.listener", Map.of(
                "badge", badge(plugin.protocolListenerActive()),
                "state", state(plugin.protocolListenerActive())
        )));
        sender.sendMessage(lang().message("commands.compat.rewrite", Map.of(
                "badge", badge(plugin.packetRewriteActive()),
                "state", state(plugin.packetRewriteActive())
        )));

        sender.sendMessage(lang().message("commands.compat.section-runtime"));
        sender.sendMessage(lang().message("commands.compat.runtime", Map.of(
                "badge", badge(plugin.veilRuntimeEnabled()),
                "state", state(plugin.veilRuntimeEnabled())
        )));
        if (!plugin.veilRuntimeEnabled()) {
            sender.sendMessage(lang().message("commands.compat.last-failure", Map.of(
                    "badge", failBadge(),
                    "reason", plugin.runtimeDisabledReason()
            )));
        }
        sender.sendMessage(lang().message("commands.compat.worlds", Map.of(
                "badge", settings.enabledWorlds().isEmpty() ? warnBadge() : okBadge(),
                "worlds", worlds(settings.enabledWorlds())
        )));

        sender.sendMessage(lang().message("commands.compat.section-warnings"));
        if (warnings.isEmpty()) {
            sender.sendMessage(lang().message("commands.compat.no-warnings", Map.of("badge", okBadge())));
        } else {
            for (String warning : warnings) {
                sender.sendMessage(lang().message("commands.compat.warning", Map.of(
                        "badge", warnBadge(),
                        "warning", warning
                )));
            }
        }
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!canUse(sender, "chunkveil.inspect")) {
            deny(sender);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(lang().message("commands.inspect.usage"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(lang().message("commands.inspect.not-found", Map.of("player", args[1])));
            return;
        }

        sender.sendMessage(lang().message("commands.inspect.title"));
        sender.sendMessage(lang().message("commands.inspect.heading"));
        if (!plugin.veilRuntimeEnabled() || plugin.veilEngine() == null) {
            sender.sendMessage(lang().message("commands.inspect.disabled", Map.of("badge", warnBadge())));
            sender.sendMessage(lang().message("commands.inspect.player", Map.of(
                    "badge", infoBadge(),
                    "player", target.getName()
            )));
            sender.sendMessage(lang().message("commands.inspect.world", Map.of(
                    "badge", infoBadge(),
                    "world", target.getWorld().getName()
            )));
            sender.sendMessage(lang().message("commands.inspect.bypass", Map.of(
                    "badge", badge(!target.hasPermission("chunkveil.bypass")),
                    "state", state(target.hasPermission("chunkveil.bypass"))
            )));
            return;
        }

        VeilEngine.PlayerInspection inspection = plugin.veilEngine().inspectPlayer(target);
        sender.sendMessage(lang().message("commands.inspect.player", Map.of(
                "badge", infoBadge(),
                "player", inspection.playerName()
        )));
        sender.sendMessage(lang().message("commands.inspect.world", Map.of(
                "badge", infoBadge(),
                "world", inspection.worldName()
        )));
        sender.sendMessage(lang().message("commands.inspect.bypass", Map.of(
                "badge", inspection.bypassed() ? warnBadge() : okBadge(),
                "state", state(inspection.bypassed())
        )));
        sender.sendMessage(lang().message("commands.inspect.client-view-distance", Map.of(
                "badge", infoBadge(),
                "distance", inspection.clientViewDistance()
        )));
        sender.sendMessage(lang().message("commands.inspect.effective-scan-radius", Map.of(
                "badge", infoBadge(),
                "radius", inspection.effectiveScanRadius()
        )));
        sender.sendMessage(lang().message("commands.inspect.visible-chunks", Map.of(
                "badge", infoBadge(),
                "chunks", inspection.visibleChunkCount()
        )));
        sender.sendMessage(lang().message("commands.inspect.queued-chunks", Map.of(
                "badge", inspection.queuedChunkCount() > 0 ? warnBadge() : okBadge(),
                "chunks", inspection.queuedChunkCount()
        )));
        sender.sendMessage(lang().message("commands.inspect.hidden-entities", Map.of(
                "badge", infoBadge(),
                "entities", inspection.hiddenEntityCount()
        )));
        sender.sendMessage(lang().message("commands.inspect.last-scan", Map.of(
                "badge", inspection.lastViewRevealAgeMillis() < 0L ? warnBadge() : infoBadge(),
                "age", age(inspection.lastViewRevealAgeMillis())
        )));
        sender.sendMessage(lang().message("commands.inspect.current-chunk", Map.of(
                "badge", "hidden".equals(inspection.currentChunkState()) ? warnBadge() : okBadge(),
                "state", inspection.currentChunkState()
        )));
    }

    private void report(CommandSender sender) {
        if (!canUse(sender, "chunkveil.report")) {
            deny(sender);
            return;
        }

        try {
            Path reportFile = writeReport();
            sender.sendMessage(lang().message("commands.report.created", Map.of(
                    "file", reportFile.toString()
            )));
        } catch (IOException exception) {
            sender.sendMessage(lang().message("commands.report.failed", Map.of(
                    "reason", exception.getMessage()
            )));
        }
    }

    private void predict(CommandSender sender, String[] args) {
        if (!canUse(sender, "chunkveil.predict")) {
            deny(sender);
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(lang().message("commands.predict.usage"));
            return;
        }

        Integer players = positiveInteger(args[1]);
        Double ramGb = positiveDouble(args[2]);
        CpuTier cpuTier = cpuTier(args[3]);
        Integer viewDistance = args.length >= 5 ? positiveInteger(args[4]) : plugin.getServer().getViewDistance();
        if (players == null || ramGb == null || cpuTier == null || viewDistance == null) {
            sender.sendMessage(lang().message("commands.predict.usage"));
            return;
        }

        VeilMetrics metrics = plugin.metrics();
        if (!hasEnoughPredictionSamples(metrics)) {
            sender.sendMessage(lang().message("commands.predict.title"));
            sender.sendMessage(lang().message("commands.predict.heading"));
            sender.sendMessage(lang().message("commands.predict.not-ready", Map.of("badge", failBadge())));
            sender.sendMessage(lang().message("commands.predict.samples", Map.of(
                    "badge", infoBadge(),
                    "reveal", metrics.revealScanSamples(),
                    "required_reveal", REQUIRED_REVEAL_PREDICT_SAMPLES,
                    "chunk_mask", metrics.chunkUpdateMaskSamples(),
                    "required_chunk_mask", REQUIRED_CHUNK_MASK_PREDICT_SAMPLES,
                    "entity", metrics.entityScanSamples(),
                    "required_entity", REQUIRED_ENTITY_PREDICT_SAMPLES,
                    "queue", metrics.queueProcessingSamples(),
                    "required_queue", REQUIRED_QUEUE_PREDICT_SAMPLES
            )));
            return;
        }

        Prediction prediction = predictPerformance(players, ramGb, cpuTier, viewDistance);
        sender.sendMessage(lang().message("commands.predict.title"));
        sender.sendMessage(lang().message("commands.predict.heading"));
        sender.sendMessage(lang().message("commands.predict.input", Map.of(
                "badge", infoBadge(),
                "players", players,
                "ram", decimal(ramGb),
                "cpu_tier", cpuTier.label(),
                "view_distance", viewDistance
        )));
        sender.sendMessage(lang().message("commands.predict.estimate", Map.of(
                "badge", prediction.loadPercent() >= 70.0D ? warnBadge() : okBadge(),
                "load", decimal(prediction.loadPercent())
        )));
        sender.sendMessage(lang().message("commands.predict.tick-cost", Map.of(
                "badge", prediction.tickMillis() >= 10.0D ? warnBadge() : okBadge(),
                "tick", decimal(prediction.tickMillis())
        )));
        sender.sendMessage(lang().message("commands.predict.memory", Map.of(
                "badge", prediction.memoryWarning() ? warnBadge() : okBadge(),
                "memory", prediction.memorySummary()
        )));
        sender.sendMessage(lang().message("commands.predict.confidence", Map.of(
                "badge", okBadge(),
                "confidence", prediction.confidence()
        )));
        sender.sendMessage(lang().message("commands.predict.recommendation", Map.of(
                "badge", prediction.recommendationBadge(),
                "recommendation", prediction.recommendation()
        )));
    }

    private Prediction predictPerformance(int players, double ramGb, CpuTier cpuTier, int viewDistance) {
        VeilSettings settings = plugin.settings();
        VeilMetrics metrics = plugin.metrics();
        double revealMillis = metrics.revealScanAverageMillis();
        double entityMillis = metrics.entityScanAverageMillis();
        double queueMillis = metrics.queueProcessingAverageMillis();
        double maskMillis = metrics.chunkUpdateMaskAverageMillis();

        double revealActivityFactor = 0.18D;
        double entityActivityFactor = 0.25D;
        double chunkMaskBurstsPerPlayerPerSecond = 0.12D;
        double revealScansPerSecond = players * (1000.0D / settings.viewRevealRefreshMillis()) * revealActivityFactor;
        double entityScansPerSecond = players * (1000.0D / settings.entityScanIntervalMillis()) * entityActivityFactor;
        double estimatedMillisPerSecond = revealScansPerSecond * revealMillis
                + entityScansPerSecond * entityMillis
                + 20.0D * queueMillis
                + players * chunkMaskBurstsPerPlayerPerSecond * maskMillis;
        double tickMillis = estimatedMillisPerSecond / 20.0D * cpuTier.mainThreadMultiplier();
        double loadPercent = tickMillis / 50.0D * 100.0D;

        double estimatedPluginRamGb = 0.08D + players * 0.006D + viewDistance * 0.015D;
        boolean memoryWarning = ramGb < 2.0D || estimatedPluginRamGb > ramGb * 0.15D;
        String memorySummary = decimal(estimatedPluginRamGb) + "GB estimated ChunkVeil overhead on " + decimal(ramGb) + "GB server RAM";
        String confidence = "medium/high, based only on live timing samples from this server session";

        String recommendation;
        String recommendationBadge;
        if (loadPercent >= 85.0D || tickMillis >= 15.0D || memoryWarning) {
            recommendation = "High risk. Lower ray counts, increase refresh intervals, reduce view distance, or use stronger single-core CPU/RAM.";
            recommendationBadge = failBadge();
        } else if (loadPercent >= 55.0D || tickMillis >= 8.0D) {
            recommendation = "Moderate risk. Watch /chunkveil status timings during peak player count. Extra CPU threads do not scale the main tick linearly.";
            recommendationBadge = warnBadge();
        } else {
            recommendation = "Looks reasonable for this configuration. Re-check after collecting live timings with real players.";
            recommendationBadge = okBadge();
        }

        return new Prediction(loadPercent, tickMillis, memoryWarning, memorySummary, confidence, recommendation, recommendationBadge);
    }

    private boolean hasEnoughPredictionSamples(VeilMetrics metrics) {
        return metrics.revealScanSamples() >= REQUIRED_REVEAL_PREDICT_SAMPLES
                && metrics.chunkUpdateMaskSamples() >= REQUIRED_CHUNK_MASK_PREDICT_SAMPLES
                && metrics.entityScanSamples() >= REQUIRED_ENTITY_PREDICT_SAMPLES
                && metrics.queueProcessingSamples() >= REQUIRED_QUEUE_PREDICT_SAMPLES;
    }

    private Integer positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Double positiveDouble(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 0.0D ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private CpuTier cpuTier(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "low" -> new CpuTier("low", 1.35D);
            case "mid", "medium" -> new CpuTier("mid", 1.0D);
            case "high" -> new CpuTier("high", 0.78D);
            case "top", "veryhigh", "very-high" -> new CpuTier("top", 0.62D);
            default -> null;
        };
    }

    private record CpuTier(String label, double mainThreadMultiplier) {
    }

    private record Prediction(
            double loadPercent,
            double tickMillis,
            boolean memoryWarning,
            String memorySummary,
            String confidence,
            String recommendation,
            String recommendationBadge
    ) {
    }

    private List<String> compatibilityWarnings(Plugin protocolLib, VeilSettings settings) {
        List<String> warnings = new ArrayList<>();
        if (protocolLib == null || !protocolLib.isEnabled()) {
            warnings.add("ProtocolLib is not enabled. ChunkVeil cannot protect chunks without it.");
        }
        if (!plugin.veilRuntimeEnabled()) {
            warnings.add("Runtime protection is disabled: " + plugin.runtimeDisabledReason());
        }
        if (!plugin.protocolListenerActive()) {
            warnings.add("ProtocolLib listener is not active.");
        }
        if (!plugin.packetRewriteActive()) {
            warnings.add("Raw chunk packet rewrite is not active. Install a ProtocolLib build for this exact server version.");
        }
        if (Bukkit.getMinecraftVersion().startsWith("26.")) {
            warnings.add("Minecraft 26.x is not currently supported by the raw chunk rewrite path.");
        }
        warnings.addAll(settings.validationWarnings());
        return warnings;
    }

    private String protocolLibVersion(Plugin protocolLib) {
        if (protocolLib == null) {
            return "not installed";
        }
        return protocolLib.getDescription().getVersion() + (protocolLib.isEnabled() ? "" : " (disabled)");
    }

    private Path writeReport() throws IOException {
        Path reportsDirectory = plugin.getDataFolder().toPath().resolve("reports");
        Files.createDirectories(reportsDirectory);

        String timestamp = LocalDateTime.now().format(REPORT_TIME_FORMAT);
        Path reportFile = reportsDirectory.resolve("chunkveil-report-" + timestamp + ".txt");
        Files.writeString(reportFile, reportContent(), StandardCharsets.UTF_8);
        return reportFile;
    }

    private String reportContent() {
        StringBuilder report = new StringBuilder();
        VeilSettings settings = plugin.settings();
        VeilMetrics metrics = plugin.metrics();
        Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");

        appendSection(report, "ChunkVeil Diagnostic Report");
        appendLine(report, "Generated", LocalDateTime.now().toString());
        appendLine(report, "Plugin version", plugin.getDescription().getVersion());
        appendLine(report, "Runtime enabled", plugin.veilRuntimeEnabled());
        appendLine(report, "Runtime disabled reason", plugin.veilRuntimeEnabled() ? "none" : plugin.runtimeDisabledReason());
        appendLine(report, "Debug enabled", plugin.debugEnabled());

        appendSection(report, "Server");
        appendLine(report, "Minecraft", Bukkit.getMinecraftVersion());
        appendLine(report, "Server", Bukkit.getVersion());
        appendLine(report, "Bukkit/Paper API", Bukkit.getBukkitVersion());
        appendLine(report, "Java", Runtime.version());

        appendSection(report, "ProtocolLib");
        appendLine(report, "ProtocolLib", protocolLibVersion(protocolLib));
        appendLine(report, "ProtocolLib listener active", plugin.protocolListenerActive());
        appendLine(report, "Raw chunk packet rewrite active", plugin.packetRewriteActive());

        appendSection(report, "Worlds");
        appendLine(report, "Enabled worlds", worlds(settings.enabledWorlds()));
        appendLine(report, "Configured worlds", worlds(settings.worlds().keySet()));
        for (Map.Entry<String, VeilWorldSettings> entry : settings.worlds().entrySet()) {
            VeilWorldSettings worldSettings = entry.getValue();
            report.append("- ").append(entry.getKey()).append(": ")
                    .append("enabled=").append(worldSettings.enabled())
                    .append(", min-y=").append(worldSettings.minY())
                    .append(", hide-below-y=").append(worldSettings.hideBelowY())
                    .append(", fake-block=").append(worldSettings.defaultFakeBlock())
                    .append(", hide-air=").append(worldSettings.hideAir())
                    .append(", hide-entities=").append(worldSettings.hideEntities())
                    .append(", hide-players=").append(worldSettings.hidePlayers())
                    .append(System.lineSeparator());
        }

        appendSection(report, "Reveal Config");
        appendLine(report, "front horizontal rays", settings.viewRevealFrontHorizontalRays());
        appendLine(report, "side horizontal rays", settings.viewRevealSideHorizontalRays());
        appendLine(report, "back horizontal rays", settings.viewRevealBackHorizontalRays());
        appendLine(report, "vertical rays", settings.viewRevealVerticalRays());
        appendLine(report, "occlusion grace blocks", settings.viewRevealOcclusionGraceBlocks());
        appendLine(report, "refresh millis", settings.viewRevealRefreshMillis());
        appendLine(report, "force refresh millis", settings.viewRevealForceRefreshMillis());
        appendLine(report, "move threshold blocks", settings.viewRevealMoveThresholdBlocks());
        appendLine(report, "yaw threshold degrees", settings.viewRevealYawThresholdDegrees());
        appendLine(report, "pitch threshold degrees", settings.viewRevealPitchThresholdDegrees());
        appendLine(report, "priority chunk updates per player per tick", settings.maxPriorityChunkUpdatesPerPlayerPerTick());
        appendLine(report, "regular chunk updates per player per tick", settings.maxRegularChunkUpdatesPerPlayerPerTick());
        appendLine(report, "entity scan interval millis", settings.entityScanIntervalMillis());
        appendLine(report, "entity scan max entities per player", settings.entityScanMaxEntitiesPerPlayer());
        appendLine(report, "view reveal yaw cache bucket degrees", settings.viewRevealYawCacheBucketDegrees());
        appendLine(report, "adaptive scan quality enabled", settings.adaptiveScanQualityEnabled());
        appendLine(report, "adaptive reduce rays below TPS", settings.adaptiveScanReduceBelowTps());
        appendLine(report, "adaptive minimum front rays", settings.adaptiveScanMinimumFrontHorizontalRays());
        appendLine(report, "adaptive minimum side rays", settings.adaptiveScanMinimumSideHorizontalRays());
        appendLine(report, "adaptive minimum back rays", settings.adaptiveScanMinimumBackHorizontalRays());
        appendLine(report, "adaptive minimum vertical rays", settings.adaptiveScanMinimumVerticalRays());

        appendSection(report, "Packet Protection Config");
        appendLine(report, "cancel explosions", settings.cancelExplosionsInHiddenZones());
        appendLine(report, "cancel world events", settings.cancelWorldEventsInHiddenZones());
        appendLine(report, "cancel block break animations", settings.cancelBlockCrackInHiddenZones());
        appendLine(report, "cancel positional sounds", settings.cancelPositionalSoundsInHiddenZones());

        appendSection(report, "Validation Warnings");
        if (settings.validationWarnings().isEmpty()) {
            report.append("- none").append(System.lineSeparator());
        } else {
            for (String warning : settings.validationWarnings()) {
                report.append("- ").append(warning).append(System.lineSeparator());
            }
        }

        appendSection(report, "Metrics Snapshot");
        appendLine(report, "chunk packets", metrics.chunkPackets());
        appendLine(report, "hidden chunk packets", metrics.hiddenChunkPackets());
        appendLine(report, "rewritten chunk packets", metrics.rewrittenChunkPackets());
        appendLine(report, "unrewritten hidden chunk packets", metrics.unrewrittenHiddenChunkPackets());
        appendLine(report, "chunk update packets sent", metrics.chunkUpdatePacketsSent());
        appendLine(report, "block changes rewritten", metrics.blockChangesRewritten());
        appendLine(report, "multi-block changes rewritten", metrics.multiBlockChangesRewritten());
        appendLine(report, "block entity updates cancelled", metrics.blockEntityUpdatesCancelled());
        appendLine(report, "entity spawns cancelled", metrics.entitySpawnsCancelled());
        appendLine(report, "entity packets cancelled", metrics.entityPacketsCancelled());
        appendLine(report, "explosion packets cancelled", metrics.explosionPacketsCancelled());
        appendLine(report, "world event packets cancelled", metrics.worldEventPacketsCancelled());
        appendLine(report, "block break animation packets cancelled", metrics.blockBreakAnimationPacketsCancelled());
        appendLine(report, "sound packets cancelled", metrics.soundPacketsCancelled());
        appendLine(report, "reveal scan avg ms", decimal(metrics.revealScanAverageMillis()));
        appendLine(report, "reveal scan max ms", decimal(metrics.revealScanMaxMillis()));
        appendLine(report, "reveal scan samples", metrics.revealScanSamples());
        appendLine(report, "packet chunk rewrite avg ms", decimal(metrics.packetChunkRewriteAverageMillis()));
        appendLine(report, "packet chunk rewrite max ms", decimal(metrics.packetChunkRewriteMaxMillis()));
        appendLine(report, "packet chunk rewrite samples", metrics.packetChunkRewriteSamples());
        appendLine(report, "chunk update mask avg ms", decimal(metrics.chunkUpdateMaskAverageMillis()));
        appendLine(report, "chunk update mask max ms", decimal(metrics.chunkUpdateMaskMaxMillis()));
        appendLine(report, "chunk update mask samples", metrics.chunkUpdateMaskSamples());
        appendLine(report, "entity scan avg ms", decimal(metrics.entityScanAverageMillis()));
        appendLine(report, "entity scan max ms", decimal(metrics.entityScanMaxMillis()));
        appendLine(report, "entity scan samples", metrics.entityScanSamples());
        appendLine(report, "queue processing avg ms", decimal(metrics.queueProcessingAverageMillis()));
        appendLine(report, "queue processing max ms", decimal(metrics.queueProcessingMaxMillis()));
        appendLine(report, "queue processing samples", metrics.queueProcessingSamples());

        appendSection(report, "Tracked Runtime State");
        VeilEngine veilEngine = plugin.veilEngine();
        appendLine(report, "tracked players", veilEngine == null ? 0 : veilEngine.trackedPlayerCount());
        appendLine(report, "queued chunks", veilEngine == null ? 0 : veilEngine.queuedChunkCount());
        for (Player player : Bukkit.getOnlinePlayers()) {
            report.append("- player=").append(player.getName())
                    .append(", world=").append(player.getWorld().getName())
                    .append(", bypass=").append(player.hasPermission("chunkveil.bypass"));
            if (veilEngine != null) {
                VeilEngine.PlayerInspection inspection = veilEngine.inspectPlayer(player);
                report.append(", client-view-distance=").append(inspection.clientViewDistance())
                        .append(", effective-scan-radius=").append(inspection.effectiveScanRadius())
                        .append(", visible-chunks=").append(inspection.visibleChunkCount())
                        .append(", queued-chunks=").append(inspection.queuedChunkCount())
                        .append(", hidden-entities=").append(inspection.hiddenEntityCount())
                        .append(", last-scan-age=").append(age(inspection.lastViewRevealAgeMillis()))
                        .append(", current-chunk=").append(inspection.currentChunkState());
            }
            report.append(System.lineSeparator());
        }

        return report.toString();
    }

    private void appendSection(StringBuilder report, String title) {
        report.append(System.lineSeparator()).append("== ").append(title).append(" ==").append(System.lineSeparator());
    }

    private void appendLine(StringBuilder report, String key, Object value) {
        report.append(key).append(": ").append(value).append(System.lineSeparator());
    }

    private String age(long millis) {
        if (millis < 0L) {
            return "never";
        }
        if (millis < 1000L) {
            return millis + "ms ago";
        }
        long seconds = millis / 1000L;
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m " + (seconds % 60L) + "s ago";
        }
        long hours = minutes / 60L;
        return hours + "h " + (minutes % 60L) + "m ago";
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String worlds(Iterable<String> worlds) {
        List<String> values = new ArrayList<>();
        for (String world : worlds) {
            values.add(world);
        }
        if (values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values);
    }

    private String badge(boolean ok) {
        return ok ? okBadge() : failBadge();
    }

    private String okBadge() {
        return lang().raw("badges.ok");
    }

    private String warnBadge() {
        return lang().raw("badges.warn");
    }

    private String failBadge() {
        return lang().raw("badges.fail");
    }

    private String infoBadge() {
        return lang().raw("badges.info");
    }

    private void reload(CommandSender sender) {
        if (!canUse(sender, "chunkveil.reload")) {
            deny(sender);
            return;
        }

        plugin.reloadVeil();
        sender.sendMessage(lang().message("commands.reload"));
    }

    private void refresh(CommandSender sender) {
        if (!canUse(sender, "chunkveil.refresh")) {
            deny(sender);
            return;
        }
        if (!plugin.veilRuntimeEnabled() || plugin.veilEngine() == null) {
            sender.sendMessage(lang().message("commands.refresh-disabled"));
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.veilEngine().rescanPlayer(player);
        }
        sender.sendMessage(lang().message("commands.refresh", Map.of("players", Bukkit.getOnlinePlayers().size())));
    }

    private void disable(CommandSender sender) {
        if (!canUse(sender, "chunkveil.toggle")) {
            deny(sender);
            return;
        }
        if (!plugin.veilRuntimeEnabled()) {
            sender.sendMessage(lang().message("commands.disable.already"));
            return;
        }

        VeilRestoreResult restoreResult = plugin.disableVeilRuntime();
        sender.sendMessage(lang().message("commands.disable.done", Map.of(
                "players", restoreResult.players(),
                "chunks", restoreResult.chunks()
        )));
    }

    private void enable(CommandSender sender) {
        if (!canUse(sender, "chunkveil.toggle")) {
            deny(sender);
            return;
        }
        if (plugin.veilRuntimeEnabled()) {
            sender.sendMessage(lang().message("commands.enable.already"));
            return;
        }

        plugin.enableVeilRuntime();
        if (plugin.veilRuntimeEnabled()) {
            sender.sendMessage(lang().message("commands.enable.done"));
        } else {
            sender.sendMessage(lang().message("commands.enable.failed", Map.of(
                    "reason", plugin.runtimeDisabledReason()
            )));
        }
    }

    private void debug(CommandSender sender, String[] args) {
        if (!canUse(sender, "chunkveil.debug")) {
            deny(sender);
            return;
        }
        if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage(lang().message("commands.debug.usage"));
            return;
        }

        boolean enabled = args[1].equalsIgnoreCase("on");
        plugin.setDebugEnabled(enabled);
        sender.sendMessage(lang().message("commands.debug.changed", Map.of("state", state(enabled))));
    }

    private boolean canUse(CommandSender sender, String permission) {
        return !(sender instanceof Player) || sender.hasPermission(permission) || sender.hasPermission("chunkveil.admin");
    }

    private String permissionFor(String subcommand) {
        return switch (subcommand) {
            case "reload" -> "chunkveil.reload";
            case "status" -> "chunkveil.status";
            case "compat" -> "chunkveil.compat";
            case "inspect" -> "chunkveil.inspect";
            case "report" -> "chunkveil.report";
            case "predict" -> "chunkveil.predict";
            case "debug" -> "chunkveil.debug";
            case "version" -> "chunkveil.version";
            case "refresh" -> "chunkveil.refresh";
            case "disable", "enable" -> "chunkveil.toggle";
            default -> "chunkveil.admin";
        };
    }

    private String state(boolean enabled) {
        return lang().state(enabled);
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(lang().message("commands.no-permission"));
    }

    private VeilLang lang() {
        return plugin.lang();
    }
}

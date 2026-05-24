package com.dekaeyman.chunkveil;

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
    private static final List<String> SUBCOMMANDS = List.of("status", "compat", "reload", "refresh", "disable", "enable", "debug", "version");

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
            case "reload" -> reload(sender);
            case "refresh" -> refresh(sender);
            case "disable", "off" -> disable(sender);
            case "enable", "on" -> enable(sender);
            case "debug" -> debug(sender, args);
            case "version" -> sender.sendMessage(lang().message("commands.version", Map.of(
                    "version", plugin.getDescription().getVersion()
            )));
            default -> {
                sender.sendMessage(lang().message("commands.help.unknown-command"));
                sendHelp(sender, label);
            }
        }
        return true;
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
        if (settings.enabledWorlds().isEmpty()) {
            warnings.add("No worlds are enabled in config.yml.");
        }
        return warnings;
    }

    private String protocolLibVersion(Plugin protocolLib) {
        if (protocolLib == null) {
            return "not installed";
        }
        return protocolLib.getDescription().getVersion() + (protocolLib.isEnabled() ? "" : " (disabled)");
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
            case "debug" -> "chunkveil.debug";
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

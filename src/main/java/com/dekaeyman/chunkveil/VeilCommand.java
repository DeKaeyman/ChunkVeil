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

final class VeilCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "refresh", "disable", "enable", "debug", "version");

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
        sender.sendMessage(lang().message("commands.status.title"));
        sender.sendMessage(lang().message("commands.status.runtime", Map.of("state", state(plugin.veilRuntimeEnabled()))));
        sender.sendMessage(lang().message("commands.status.worlds", Map.of("worlds", settings.enabledWorlds())));
        if (!settings.worlds().isEmpty()) {
            sender.sendMessage(lang().message("commands.status.world-overrides", Map.of("worlds", settings.worlds().keySet())));
        }
        sender.sendMessage(lang().message("commands.status.debug", Map.of("state", state(plugin.debugEnabled()))));
        VeilEngine veilEngine = plugin.veilEngine();
        int trackedPlayers = veilEngine == null ? 0 : veilEngine.trackedPlayerCount();
        int queuedChunks = veilEngine == null ? 0 : veilEngine.queuedChunkCount();
        sender.sendMessage(lang().message("commands.status.tracked", Map.of(
                "players", trackedPlayers,
                "chunks", queuedChunks
        )));
        sender.sendMessage(lang().message("commands.status.protocol", Map.of(
                "listener", state(plugin.protocolListenerActive()),
                "rewrite", state(plugin.packetRewriteActive())
        )));
        sender.sendMessage(lang().message("commands.status.chunk-packets", Map.of(
                "packets", metrics.chunkPackets(),
                "hidden", metrics.hiddenChunkPackets(),
                "rewritten", metrics.rewrittenChunkPackets(),
                "unrewritten", metrics.unrewrittenHiddenChunkPackets()
        )));
        sender.sendMessage(lang().message("commands.status.updates", Map.of(
                "block", metrics.blockChangesRewritten(),
                "multi", metrics.multiBlockChangesRewritten(),
                "block_entities", metrics.blockEntityUpdatesCancelled()
        )));
        sender.sendMessage(lang().message("commands.status.entities", Map.of(
                "spawns", metrics.entitySpawnsCancelled(),
                "packets", metrics.entityPacketsCancelled()
        )));
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
        sender.sendMessage(lang().message("commands.enable.done"));
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

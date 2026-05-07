package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

final class VeilCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "refresh", "debug", "version");

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
            case "debug" -> debug(sender, args);
            case "version" -> sender.sendMessage(prefix() + "Version " + plugin.getDescription().getVersion());
            default -> sendHelp(sender, label);
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
        sender.sendMessage(prefix() + "/" + label + " status");
        sender.sendMessage(prefix() + "/" + label + " reload");
        sender.sendMessage(prefix() + "/" + label + " refresh");
        sender.sendMessage(prefix() + "/" + label + " debug <on|off>");
        sender.sendMessage(prefix() + "/" + label + " version");
    }

    private void sendStatus(CommandSender sender) {
        if (!canUse(sender, "chunkveil.status")) {
            deny(sender);
            return;
        }

        VeilSettings settings = plugin.settings();
        VeilMetrics metrics = plugin.metrics();
        sender.sendMessage(prefix() + "Status");
        sender.sendMessage(ChatColor.GRAY + "Worlds: " + ChatColor.WHITE + settings.enabledWorlds());
        if (!settings.worlds().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "World overrides: " + ChatColor.WHITE + settings.worlds().keySet());
        }
        sender.sendMessage(ChatColor.GRAY + "Debug: " + enabled(plugin.debugEnabled()));
        sender.sendMessage(ChatColor.GRAY + "Tracked players: " + ChatColor.WHITE + plugin.veilEngine().trackedPlayerCount() + ChatColor.GRAY + ", queued chunks: " + ChatColor.WHITE + plugin.veilEngine().queuedChunkCount());
        sender.sendMessage(ChatColor.GRAY + "ProtocolLib listener: " + enabled(plugin.protocolListenerActive()) + ChatColor.GRAY + ", packet rewrite: " + enabled(plugin.packetRewriteActive()));
        sender.sendMessage(ChatColor.GRAY + "Chunk packets: " + ChatColor.WHITE + metrics.chunkPackets() + ChatColor.GRAY + ", hidden: " + ChatColor.WHITE + metrics.hiddenChunkPackets() + ChatColor.GRAY + ", rewritten: " + ChatColor.WHITE + metrics.rewrittenChunkPackets() + ChatColor.GRAY + ", unrewritten hidden: " + ChatColor.WHITE + metrics.unrewrittenHiddenChunkPackets());
        sender.sendMessage(ChatColor.GRAY + "Updates: block=" + ChatColor.WHITE + metrics.blockChangesRewritten() + ChatColor.GRAY + ", multi=" + ChatColor.WHITE + metrics.multiBlockChangesRewritten() + ChatColor.GRAY + ", block entities=" + ChatColor.WHITE + metrics.blockEntityUpdatesCancelled());
        sender.sendMessage(ChatColor.GRAY + "Entities: spawns=" + ChatColor.WHITE + metrics.entitySpawnsCancelled() + ChatColor.GRAY + ", packets=" + ChatColor.WHITE + metrics.entityPacketsCancelled());
    }

    private void reload(CommandSender sender) {
        if (!canUse(sender, "chunkveil.reload")) {
            deny(sender);
            return;
        }

        plugin.reloadVeil();
        sender.sendMessage(prefix() + "Reloaded config and refreshed online players.");
    }

    private void refresh(CommandSender sender) {
        if (!canUse(sender, "chunkveil.refresh")) {
            deny(sender);
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.veilEngine().rescanPlayer(player);
        }
        sender.sendMessage(prefix() + "Refreshed " + Bukkit.getOnlinePlayers().size() + " online players.");
    }

    private void debug(CommandSender sender, String[] args) {
        if (!canUse(sender, "chunkveil.debug")) {
            deny(sender);
            return;
        }
        if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            sender.sendMessage(prefix() + "Usage: /chunkveil debug <on|off>");
            return;
        }

        boolean enabled = args[1].equalsIgnoreCase("on");
        plugin.setDebugEnabled(enabled);
        sender.sendMessage(prefix() + "Debug " + (enabled ? "enabled" : "disabled") + ".");
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
            default -> "chunkveil.admin";
        };
    }

    private String enabled(boolean enabled) {
        return enabled ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled";
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(prefix() + ChatColor.RED + "You do not have permission.");
    }

    private String prefix() {
        return ChatColor.DARK_AQUA + "[ChunkVeil] " + ChatColor.GRAY;
    }
}

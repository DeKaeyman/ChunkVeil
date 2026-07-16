package com.dekaeyman.chunkveil;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends the update notice shortly after an admin joins. Registered separately
 * from {@link VeilListener} so toggling the veil runtime does not remove it.
 */
final class UpdateNotifyListener implements Listener {
    private static final long NOTIFY_DELAY_TICKS = 60L;

    private final ChunkVeilPlugin plugin;

    UpdateNotifyListener(ChunkVeilPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            UpdateChecker updateChecker = plugin.updateChecker();
            if (player != null && updateChecker != null) {
                updateChecker.notifyPlayer(player);
            }
        }, NOTIFY_DELAY_TICKS);
    }
}

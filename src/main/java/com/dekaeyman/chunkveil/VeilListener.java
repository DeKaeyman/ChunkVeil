package com.dekaeyman.chunkveil;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

final class VeilListener implements Listener {
    private final VeilEngine veilEngine;

    VeilListener(VeilEngine veilEngine) {
        this.veilEngine = veilEngine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPlayerJoin(PlayerJoinEvent event) {
        veilEngine.resetPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Moves within a chunk need nothing here: the per-player worker runs
        // the (rate-limited) view scan every tick, which also covers vehicle
        // movement that fires no move event. Crossing into a new chunk still
        // refreshes immediately.
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4
                && from.getBlockZ() >> 4 == to.getBlockZ() >> 4
                && from.getWorld().equals(to.getWorld())) {
            return;
        }

        veilEngine.refreshPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Cross-world teleports are reset by PlayerChangedWorldEvent. This
        // event fires before the player moves, so scanning now would scan the
        // departure point: rescan once the teleport has happened.
        if (event.getFrom().getWorld().equals(to.getWorld())) {
            veilEngine.rescanPlayerAfterTeleport(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        veilEngine.resetPlayer(event.getPlayer());
    }

    @EventHandler
    void onPlayerQuit(PlayerQuitEvent event) {
        veilEngine.removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlayerClientOptionsChange(PlayerClientOptionsChangeEvent event) {
        veilEngine.refreshPlayer(event.getPlayer());
    }

    @EventHandler
    void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        veilEngine.forgetChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }
}

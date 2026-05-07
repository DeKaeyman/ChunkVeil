package com.dekaeyman.chunkveil;

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
        if (event.getFrom().getWorld().equals(to.getWorld())) {
            veilEngine.rescanPlayer(event.getPlayer());
        } else {
            veilEngine.resetPlayer(event.getPlayer());
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

    @EventHandler
    void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        veilEngine.forgetChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }
}

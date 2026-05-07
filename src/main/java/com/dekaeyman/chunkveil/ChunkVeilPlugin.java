package com.dekaeyman.chunkveil;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ChunkVeilPlugin extends JavaPlugin {
    private VeilEngine veilEngine;
    private ProtocolChunkListener protocolChunkListener;
    private VeilSettings settings;
    private VeilLang lang;
    private VeilMetrics metrics;
    private boolean debugEnabled;
    private boolean veilRuntimeEnabled;
    private BukkitTask debugTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.lang = VeilLang.load(this);
        this.metrics = new VeilMetrics();

        startVeil();
        registerCommand();
    }

    @Override
    public void onDisable() {
        stopVeil();
    }

    void reloadVeil() {
        boolean restoreDebug = debugEnabled;
        stopVeil();
        reloadConfig();
        this.lang = VeilLang.load(this);
        startVeil();
        if (restoreDebug) {
            setDebugEnabled(true);
        }
    }

    VeilRestoreResult disableVeilRuntime() {
        if (!veilRuntimeEnabled) {
            return new VeilRestoreResult(0, 0);
        }

        setDebugEnabled(false);
        if (protocolChunkListener != null) {
            protocolChunkListener.stop();
            protocolChunkListener = null;
        }

        VeilRestoreResult restoreResult = new VeilRestoreResult(0, 0);
        if (veilEngine != null) {
            restoreResult = veilEngine.restoreAllPlayersToRealChunks();
            veilEngine = null;
        }

        HandlerList.unregisterAll(this);
        veilRuntimeEnabled = false;
        getLogger().warning("ChunkVeil runtime disabled. Restored "
                + restoreResult.players() + " players and refreshed "
                + restoreResult.chunks() + " chunks.");
        return restoreResult;
    }

    void enableVeilRuntime() {
        if (veilRuntimeEnabled) {
            return;
        }
        startVeil();
    }

    VeilEngine veilEngine() {
        return veilEngine;
    }

    VeilSettings settings() {
        return settings;
    }

    VeilLang lang() {
        return lang;
    }

    VeilMetrics metrics() {
        return metrics;
    }

    boolean debugEnabled() {
        return debugEnabled;
    }

    void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
        if (debugEnabled) {
            debugTask = getServer().getScheduler().runTaskTimer(this, this::logDebugSummary, 20L * 30L, 20L * 30L);
        }
    }

    boolean protocolListenerActive() {
        return protocolChunkListener != null;
    }

    boolean packetRewriteActive() {
        return protocolChunkListener != null && protocolChunkListener.packetRewriteActive();
    }

    boolean veilRuntimeEnabled() {
        return veilRuntimeEnabled;
    }

    private void startVeil() {
        if (veilRuntimeEnabled) {
            return;
        }
        this.settings = VeilSettings.load(this);
        this.veilEngine = new VeilEngine(this, settings, metrics);
        this.veilEngine.start();
        this.protocolChunkListener = ProtocolChunkListener.start(this, veilEngine, settings, metrics);

        getServer().getPluginManager().registerEvents(new VeilListener(veilEngine), this);
        veilRuntimeEnabled = true;
        getLogger().info("ChunkVeil enabled for worlds " + settings.enabledWorlds());
    }

    private void stopVeil() {
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
        debugEnabled = false;
        HandlerList.unregisterAll(this);
        if (protocolChunkListener != null) {
            protocolChunkListener.stop();
            protocolChunkListener = null;
        }
        if (veilEngine != null) {
            veilEngine.stop();
            veilEngine = null;
        }
        veilRuntimeEnabled = false;
    }

    private void registerCommand() {
        PluginCommand command = getCommand("chunkveil");
        if (command == null) {
            getLogger().warning("Command chunkveil is missing from plugin.yml.");
            return;
        }

        VeilCommand veilCommand = new VeilCommand(this);
        command.setExecutor(veilCommand);
        command.setTabCompleter(veilCommand);
    }

    private void logDebugSummary() {
        if (!debugEnabled || metrics == null || veilEngine == null) {
            return;
        }

        getLogger().info("debug packets=" + metrics.chunkPackets()
                + " hidden=" + metrics.hiddenChunkPackets()
                + " rewritten=" + metrics.rewrittenChunkPackets()
                + " unrewrittenHidden=" + metrics.unrewrittenHiddenChunkPackets()
                + " queued=" + veilEngine.queuedChunkCount()
                + " trackedPlayers=" + veilEngine.trackedPlayerCount()
                + " entitySpawnsCancelled=" + metrics.entitySpawnsCancelled()
                + " entityPacketsCancelled=" + metrics.entityPacketsCancelled());
    }
}

package com.dekaeyman.chunkveil;

import com.dekaeyman.chunkveil.api.VeilProtectionStatusEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ChunkVeilPlugin extends JavaPlugin {
    private VeilEngine veilEngine;
    private ProtocolChunkListener protocolChunkListener;
    private VeilListener veilListener;
    private VeilSettings settings;
    private VeilLang lang;
    private VeilMetrics metrics;
    private UpdateChecker updateChecker;
    private VeilTelemetry telemetry;
    private boolean debugEnabled;
    private boolean veilRuntimeEnabled;
    private String runtimeDisabledReason;
    private BukkitTask debugTask;
    private BukkitTask inactiveReminderTask;
    private String lastCriticalFailureReason;
    private long lastCriticalFailureAtMillis;

    private static final long INACTIVE_REMINDER_TICKS = 20L * 60L * 30L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.lang = VeilLang.load(this);
        this.metrics = new VeilMetrics();

        registerCommand();
        startVeil();

        // The update checker and telemetry live outside the veil runtime so a
        // fail-closed shutdown or /chunkveil disable never affects them.
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this), this);
        this.updateChecker = UpdateChecker.start(this);
        this.telemetry = VeilTelemetry.start(this);

        // Rate-limited reminder so an inactive protection runtime cannot go
        // unnoticed in the console while the plugin itself stays loaded.
        this.inactiveReminderTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!veilRuntimeEnabled) {
                getLogger().warning("ChunkVeil protection is still INACTIVE (" + runtimeDisabledReason()
                        + ") Underground data is not being hidden. Run /chunkveil compat for diagnostics.");
            }
        }, INACTIVE_REMINDER_TICKS, INACTIVE_REMINDER_TICKS);
    }

    @Override
    public void onDisable() {
        if (inactiveReminderTask != null) {
            inactiveReminderTask.cancel();
            inactiveReminderTask = null;
        }
        stopUpdateChecker();
        stopTelemetry();
        stopVeil();
    }

    void reloadVeil() {
        boolean restoreDebug = debugEnabled;
        if (veilEngine != null) {
            veilEngine.restoreAllPlayersToRealChunks();
        }
        stopUpdateChecker();
        stopTelemetry();
        stopVeil();
        reloadConfig();
        this.lang = VeilLang.load(this);
        this.metrics = new VeilMetrics();
        startVeil();
        this.updateChecker = UpdateChecker.start(this);
        this.telemetry = VeilTelemetry.start(this);
        if (restoreDebug && veilRuntimeEnabled) {
            setDebugEnabled(true);
        }
    }

    VeilRestoreResult disableVeilRuntime() {
        return disableVeilRuntime("Disabled by admin command.", VeilProtectionStatusEvent.Cause.DISABLED_BY_ADMIN);
    }

    private VeilRestoreResult disableVeilRuntime(String reason, VeilProtectionStatusEvent.Cause cause) {
        runtimeDisabledReason = reason;
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

        unregisterVeilListener();
        veilRuntimeEnabled = false;
        getLogger().warning("ChunkVeil runtime disabled: " + reason + " Restored "
                + restoreResult.players() + " players and refreshed "
                + restoreResult.chunks() + " chunks.");
        fireProtectionStatusEvent(false, cause, reason);
        return restoreResult;
    }

    void failClosed(String reason) {
        lastCriticalFailureReason = reason;
        lastCriticalFailureAtMillis = System.currentTimeMillis();
        logProtectionInactiveBanner("ChunkVeil failed closed: " + reason);
        disableVeilRuntime(reason, VeilProtectionStatusEvent.Cause.FAILED_CLOSED);
    }

    private void logProtectionInactiveBanner(String headline) {
        getLogger().severe("============================================================");
        getLogger().severe(" __     _______ ___ _        ___  _____ _____ ");
        getLogger().severe(" \\ \\   / / ____|_ _| |      / _ \\|  ___|  ___|");
        getLogger().severe("  \\ \\ / /|  _|  | || |     | | | | |_  | |_   ");
        getLogger().severe("   \\ V / | |___ | || |___  | |_| |  _| |  _|  ");
        getLogger().severe("    \\_/  |_____|___|_____|  \\___/|_| |_|      ");
        getLogger().severe("");
        getLogger().severe(headline);
        getLogger().severe("Runtime protection is DISABLED. Underground chunk data is NOT being hidden.");
        getLogger().severe("The plugin stays loaded for diagnostics, but the server is unprotected.");
        getLogger().severe("Install a ProtocolLib build compatible with Minecraft/Paper "
                + getServer().getMinecraftVersion() + ", then restart the server.");
        getLogger().severe("No fallback masking is used. Run /chunkveil compat for diagnostics.");
        getLogger().severe("============================================================");
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

    UpdateChecker updateChecker() {
        return updateChecker;
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

    String runtimeDisabledReason() {
        return runtimeDisabledReason == null ? "Runtime is disabled." : runtimeDisabledReason;
    }

    /** Reason of the last fail-closed shutdown since server start, or null. */
    String lastCriticalFailureReason() {
        return lastCriticalFailureReason;
    }

    long lastCriticalFailureAgeMillis() {
        return lastCriticalFailureReason == null ? -1L : System.currentTimeMillis() - lastCriticalFailureAtMillis;
    }

    private void fireProtectionStatusEvent(boolean active, VeilProtectionStatusEvent.Cause cause, String reason) {
        getServer().getPluginManager().callEvent(new VeilProtectionStatusEvent(active, cause, reason));
    }

    private void startVeil() {
        if (veilRuntimeEnabled) {
            return;
        }
        try {
            this.settings = VeilSettings.load(this);
            logConfigValidationWarnings(settings);
            this.veilEngine = new VeilEngine(this, settings, metrics);
            this.protocolChunkListener = ProtocolChunkListener.start(this, veilEngine, settings, metrics);
            this.veilEngine.start();

            this.veilListener = new VeilListener(veilEngine);
            getServer().getPluginManager().registerEvents(veilListener, this);
            runtimeDisabledReason = null;
            veilRuntimeEnabled = true;
            getLogger().info("ChunkVeil enabled for worlds " + settings.enabledWorlds());
            fireProtectionStatusEvent(true, VeilProtectionStatusEvent.Cause.ENABLED, "");
        } catch (RuntimeException exception) {
            runtimeDisabledReason = exception.getMessage();
            logProtectionInactiveBanner("ChunkVeil refused to enable runtime protection: " + exception.getMessage());
            stopVeil();
        }
    }

    private void logConfigValidationWarnings(VeilSettings settings) {
        if (settings.validationWarnings().isEmpty()) {
            return;
        }

        getLogger().warning("Config validation found " + settings.validationWarnings().size() + " warning(s):");
        for (String warning : settings.validationWarnings()) {
            getLogger().warning("- " + warning);
        }
    }

    private void stopVeil() {
        if (debugTask != null) {
            debugTask.cancel();
            debugTask = null;
        }
        debugEnabled = false;
        unregisterVeilListener();
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

    private void unregisterVeilListener() {
        if (veilListener != null) {
            HandlerList.unregisterAll(veilListener);
            veilListener = null;
        }
    }

    private void stopUpdateChecker() {
        if (updateChecker != null) {
            updateChecker.stop();
            updateChecker = null;
        }
    }

    private void stopTelemetry() {
        if (telemetry != null) {
            telemetry.shutdown();
            telemetry = null;
        }
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
                + " entityPacketsCancelled=" + metrics.entityPacketsCancelled()
                + " explosionPacketsCancelled=" + metrics.explosionPacketsCancelled()
                + " worldEventPacketsCancelled=" + metrics.worldEventPacketsCancelled()
                + " blockBreakAnimationPacketsCancelled=" + metrics.blockBreakAnimationPacketsCancelled()
                + " soundPacketsCancelled=" + metrics.soundPacketsCancelled()
                + " revealScanAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.revealScanAverageMillis())
                + " revealScanMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.revealScanMaxMillis())
                + " packetRewriteAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.packetChunkRewriteAverageMillis())
                + " packetRewriteMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.packetChunkRewriteMaxMillis())
                + " chunkUpdateMaskAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.chunkUpdateMaskAverageMillis())
                + " chunkUpdateMaskMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.chunkUpdateMaskMaxMillis())
                + " entityScanAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.entityScanAverageMillis())
                + " entityScanMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.entityScanMaxMillis())
                + " queueAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.queueProcessingAverageMillis())
                + " queueMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.queueProcessingMaxMillis()));
    }
}

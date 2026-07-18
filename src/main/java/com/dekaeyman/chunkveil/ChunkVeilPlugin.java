package com.dekaeyman.chunkveil;

import com.dekaeyman.chunkveil.api.VeilProtectionStatusEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ChunkVeilPlugin extends JavaPlugin {
    enum RuntimeStopCause { NONE, ADMIN_DISABLED, STARTUP_FAILURE, SECURITY_TRIPPED }
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
    private RuntimeStopCause runtimeStopCause = RuntimeStopCause.NONE;

    private static final long INACTIVE_REMINDER_TICKS = 20L * 60L * 30L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.lang = VeilLang.load(this);
        this.metrics = new VeilMetrics();

        registerCommand();
        startVeil(true);

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
        startVeil(false);
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
        runtimeStopCause = RuntimeStopCause.ADMIN_DISABLED;
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
        runtimeDisabledReason = reason;
        runtimeStopCause = RuntimeStopCause.SECURITY_TRIPPED;
        logProtectionInactiveBanner("ChunkVeil security state TRIPPED: " + reason);
        setDebugEnabled(false);
        unregisterVeilListener();
        if (veilEngine != null) {
            veilEngine.stopConfidentialityFirst();
            veilEngine = null;
        }
        // Deliberately retain the tripped ProtocolLib listener. It continues
        // cancelling every protected packet type until the server restarts.
        veilRuntimeEnabled = false;
        getLogger().severe("Protected packet traffic is quarantined. No real chunks were restored.");
        applyRuntimeTripAction();
        fireProtectionStatusEvent(false, VeilProtectionStatusEvent.Cause.FAILED_CLOSED, reason);
    }

    private void applyRuntimeTripAction() {
        String action = getConfig().getString("security.runtime-trip-action", "QUARANTINE")
                .trim().toUpperCase(java.util.Locale.ROOT);
        switch (action) {
            case "STOP_SERVER" -> Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
            case "KICK_PLAYERS" -> Bukkit.getScheduler().runTask(this, () -> {
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (settings != null && settings.isEnabledWorld(player.getWorld())) {
                        player.kick(net.kyori.adventure.text.Component.text(
                                "ChunkVeil protection failed closed. Please reconnect after the server owner fixes the issue."));
                    }
                }
            });
            case "QUARANTINE" -> { }
            default -> getLogger().severe("Unknown security.runtime-trip-action '" + action
                    + "'; retaining QUARANTINE behavior.");
        }
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
        getLogger().severe("Runtime protection is INACTIVE.");
        getLogger().severe("On a runtime security trip, protected packet traffic remains quarantined and real chunks are not restored.");
        getLogger().severe("Install a ProtocolLib build compatible with Minecraft/Paper "
                + getServer().getMinecraftVersion() + ", then restart the server.");
        getLogger().severe("No insecure fallback is used. Run /chunkveil verify for diagnostics.");
        getLogger().severe("============================================================");
    }

    boolean enableVeilRuntime() {
        if (veilRuntimeEnabled) {
            return true;
        }
        if (runtimeStopCause != RuntimeStopCause.ADMIN_DISABLED) {
            return false;
        }
        startVeil(false);
        return veilRuntimeEnabled;
    }

    RuntimeStopCause runtimeStopCause() {
        return runtimeStopCause;
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

    boolean securityTripped() {
        return protocolChunkListener != null && protocolChunkListener.securityTripped();
    }

    String securityTripPath() {
        return protocolChunkListener == null ? null : protocolChunkListener.securityTripPath();
    }

    PacketProtectionHealth.Snapshot packetHealth(PacketSecurityState.ProtectedPath... paths) {
        return protocolChunkListener == null
                ? new PacketProtectionHealth.Snapshot(PacketProtectionHealth.Status.DISABLED, 0L, 0L, 0L, 0L, 0L, null)
                : protocolChunkListener.health(paths);
    }

    String lastChunkPacketFormat() {
        return protocolChunkListener == null ? null : protocolChunkListener.lastChunkPacketFormat();
    }

    String lastChunkWorld() {
        return protocolChunkListener == null ? null : protocolChunkListener.lastChunkWorld();
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

    private void startVeil(boolean initialBoot) {
        if (veilRuntimeEnabled) {
            return;
        }
        if (protocolChunkListener != null) {
            protocolChunkListener.stop();
            protocolChunkListener = null;
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
            runtimeStopCause = RuntimeStopCause.NONE;
            veilRuntimeEnabled = true;
            getLogger().info("ChunkVeil enabled for worlds " + settings.enabledWorlds());
            fireProtectionStatusEvent(true, VeilProtectionStatusEvent.Cause.ENABLED, "");
        } catch (Throwable exception) {
            String failure = exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
            runtimeDisabledReason = failure;
            runtimeStopCause = RuntimeStopCause.STARTUP_FAILURE;
            logProtectionInactiveBanner("ChunkVeil refused to enable runtime protection: " + failure);
            stopVeil();
            if (initialBoot && getConfig().getBoolean("security.stop-server-on-startup-failure", true)) {
                getLogger().severe("Strict startup policy is stopping the server because ChunkVeil could not protect it.");
                Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
            }
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
                + " particlePacketsCancelled=" + metrics.particlePacketsCancelled()
                + " vibrationPacketsCancelled=" + metrics.vibrationPacketsCancelled()
                + " lightPacketsSanitized=" + metrics.lightPacketsSanitized()
                + " securityPacketsCancelled=" + metrics.securityPacketsCancelled()
                + " revealScanAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.revealScanAverageMillis())
                + " revealScanMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.revealScanMaxMillis())
                + " packetRewriteAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.packetChunkRewriteAverageMillis())
                + " packetRewriteMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.packetChunkRewriteMaxMillis())
                + " chunkUpdateMaskAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.chunkUpdateMaskAverageMillis())
                + " chunkUpdateMaskMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.chunkUpdateMaskMaxMillis())
                + " entityScanAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.entityScanAverageMillis())
                + " entityScanMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.entityScanMaxMillis())
                + " entityScanCandidates=" + metrics.entityScanCandidates()
                + " entityScanDeferred=" + metrics.entityScanCandidatesDeferred()
                + " queueAvgMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.queueProcessingAverageMillis())
                + " queueMaxMs=" + String.format(java.util.Locale.ROOT, "%.3f", metrics.queueProcessingMaxMillis()));
    }
}

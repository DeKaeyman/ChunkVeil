package com.dekaeyman.chunkveil;

import java.util.concurrent.atomic.AtomicLong;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

/**
 * Anonymous aggregate usage statistics via bStats (bstats.org).
 *
 * Only configuration posture and aggregate counters are reported: no world
 * data, no player data, and no server address. Server owners can disable it
 * with {@code metrics.enabled: false} in config.yml or globally in
 * {@code plugins/bStats/config.yml}.
 */
final class VeilTelemetry {
    /**
     * The bStats plugin id for ChunkVeil. Register the plugin at
     * https://bstats.org/getting-started (add plugin -> ChunkVeil -> Bukkit)
     * and put the assigned id here. Telemetry stays off until it is set.
     */
    private static final int BSTATS_PLUGIN_ID = 32677;

    private final Metrics metrics;
    private final AtomicLong lastRewrittenChunkPackets = new AtomicLong();

    private VeilTelemetry(Metrics metrics) {
        this.metrics = metrics;
    }

    static VeilTelemetry start(ChunkVeilPlugin plugin) {
        if (!plugin.getConfig().getBoolean("metrics.enabled", true)) {
            plugin.getLogger().info("bStats metrics are disabled in config.yml.");
            return new VeilTelemetry(null);
        }
        if (BSTATS_PLUGIN_ID <= 0) {
            plugin.getLogger().info("bStats metrics are inactive: no bStats plugin id is configured for this build.");
            return new VeilTelemetry(null);
        }

        Metrics metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
        VeilTelemetry telemetry = new VeilTelemetry(metrics);
        telemetry.registerCharts(plugin);
        return telemetry;
    }

    void shutdown() {
        if (metrics != null) {
            metrics.shutdown();
        }
    }

    private void registerCharts(ChunkVeilPlugin plugin) {
        metrics.addCustomChart(new SimplePie("runtime_state", () ->
                plugin.veilRuntimeEnabled() ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("packet_rewrite_active", () ->
                plugin.packetRewriteActive() ? "active" : "inactive"));
        metrics.addCustomChart(new SimplePie("enabled_worlds", () -> {
            VeilSettings settings = plugin.settings();
            return settings == null ? "unknown" : String.valueOf(settings.enabledWorlds().size());
        }));
        metrics.addCustomChart(new SimplePie("hide_air", () -> hideAirPosture(plugin.settings())));
        metrics.addCustomChart(new SimplePie("adaptive_scan_quality", () -> {
            VeilSettings settings = plugin.settings();
            if (settings == null) {
                return "unknown";
            }
            return settings.adaptiveScanQualityEnabled() ? "enabled" : "disabled";
        }));
        metrics.addCustomChart(new SingleLineChart("rewritten_chunk_packets", () -> {
            long current = plugin.metrics() == null ? 0L : plugin.metrics().rewrittenChunkPackets();
            long previous = lastRewrittenChunkPackets.getAndSet(current);
            long delta = current - previous;
            if (delta < 0L) {
                // Counters reset (reload); report nothing for this window.
                return 0;
            }
            return (int) Math.min(Integer.MAX_VALUE, delta);
        }));
    }

    private String hideAirPosture(VeilSettings settings) {
        if (settings == null) {
            return "unknown";
        }
        int enabledWorlds = 0;
        int hideAirWorlds = 0;
        for (VeilWorldSettings worldSettings : settings.worlds().values()) {
            if (!worldSettings.enabled()) {
                continue;
            }
            enabledWorlds++;
            if (worldSettings.hideAir()) {
                hideAirWorlds++;
            }
        }
        if (enabledWorlds == 0) {
            return "no-worlds";
        }
        if (hideAirWorlds == 0) {
            return "disabled";
        }
        return hideAirWorlds == enabledWorlds ? "enabled" : "mixed";
    }
}

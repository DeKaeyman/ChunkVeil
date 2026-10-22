package com.dekaeyman.chunkveil;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * TPS of the Folia region the calling thread is ticking, the same number
 * Folia's /tps reports for it (1-minute average). Folia has no global TPS
 * (Bukkit.getTPS() is not meaningful there), so without this adaptive scan
 * quality never reacted to a lagging region.
 *
 * <p>There is no API for this; it reads Folia internals by reflection
 * (verified on Folia 1.21.11 and 26.1.2):
 * {@code TickRegionScheduler.getCurrentRegion().getData()
 * .getRegionSchedulingHandle().getTickReport1m(nanoTime).tpsData().segmentAll().average()}.
 * Any failure disables the lookup for good and callers fall back to 20 TPS,
 * the previous behaviour.
 */
final class FoliaRegionTps {
    static final double FULL_SPEED = 20.0D;

    private static volatile boolean disabled;
    private static Method getCurrentRegion;
    private static Method getData;
    private static Method getRegionSchedulingHandle;
    private static Method getTickReport1m;
    private static Method tpsData;
    private static Method segmentAll;
    private static Method average;

    private FoliaRegionTps() {
    }

    /** Resolves the reflection chain once; logs and disables itself if it is not there. */
    static synchronized void initialize(Logger logger) {
        if (getCurrentRegion != null || disabled) {
            return;
        }
        try {
            Class<?> scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
            getCurrentRegion = scheduler.getMethod("getCurrentRegion");
            getData = Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer$ThreadedRegion")
                    .getMethod("getData");
            getRegionSchedulingHandle = Class.forName("io.papermc.paper.threadedregions.TickRegions$TickRegionData")
                    .getMethod("getRegionSchedulingHandle");
            getTickReport1m = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler$RegionScheduleHandle")
                    .getMethod("getTickReport1m", long.class);
            Class<?> report = getTickReport1m.getReturnType();
            tpsData = report.getMethod("tpsData");
            segmentAll = tpsData.getReturnType().getMethod("segmentAll");
            average = segmentAll.getReturnType().getMethod("average");
        } catch (Throwable throwable) {
            disable(logger, throwable);
        }
    }

    /** TPS of the current thread's region, or 20 when unknown (not a region thread, no data yet, lookup off). */
    static double currentRegionTps(Logger logger) {
        if (disabled || getCurrentRegion == null) {
            return FULL_SPEED;
        }
        try {
            Object region = getCurrentRegion.invoke(null);
            if (region == null) {
                return FULL_SPEED;
            }
            Object handle = getRegionSchedulingHandle.invoke(getData.invoke(region));
            Object report = getTickReport1m.invoke(handle, System.nanoTime());
            if (report == null) {
                return FULL_SPEED;
            }
            double tps = (Double) average.invoke(segmentAll.invoke(tpsData.invoke(report)));
            return clamp(tps);
        } catch (Throwable throwable) {
            disable(logger, throwable);
            return FULL_SPEED;
        }
    }

    static double clamp(double tps) {
        if (Double.isNaN(tps)) {
            return FULL_SPEED;
        }
        return Math.min(FULL_SPEED, Math.max(0.0D, tps));
    }

    private static void disable(Logger logger, Throwable throwable) {
        if (!disabled) {
            disabled = true;
            if (logger != null) {
                logger.warning("Folia region TPS is unavailable (" + throwable
                        + "); adaptive scan quality treats regions as full speed.");
            }
        }
    }
}

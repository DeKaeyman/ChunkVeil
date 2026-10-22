package com.dekaeyman.chunkveil.stress.driver;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Reads the TPS / MSPT of the Folia region the calling thread is ticking,
 * the same data Folia's /tps shows (5 second window). There is no API for
 * it, so this uses Folia internals by reflection (checked on Folia 26.x):
 * {@code TickRegionScheduler.getCurrentRegion().getData()
 * .getRegionSchedulingHandle().getTickReport5s(nanoTime)}.
 */
final class FoliaRegionProbe {
    private final Logger logger;
    private final Method getCurrentRegion;
    private final Method getData;
    private final Method getHandle;
    private final Method getTickReport5s;
    private final Method tpsData;
    private final Method timePerTickData;
    private final Method utilisation;
    private final Method segmentAll;
    private final Method average;
    private volatile boolean failed;

    private FoliaRegionProbe(Logger logger) throws ReflectiveOperationException {
        this.logger = logger;
        Class<?> scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
        getCurrentRegion = scheduler.getMethod("getCurrentRegion");
        getData = Class.forName("io.papermc.paper.threadedregions.ThreadedRegionizer$ThreadedRegion").getMethod("getData");
        getHandle = Class.forName("io.papermc.paper.threadedregions.TickRegions$TickRegionData")
                .getMethod("getRegionSchedulingHandle");
        getTickReport5s = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler$RegionScheduleHandle")
                .getMethod("getTickReport5s", long.class);
        Class<?> report = getTickReport5s.getReturnType();
        tpsData = report.getMethod("tpsData");
        timePerTickData = report.getMethod("timePerTickData");
        utilisation = report.getMethod("utilisation");
        segmentAll = tpsData.getReturnType().getMethod("segmentAll");
        average = segmentAll.getReturnType().getMethod("average");
    }

    static FoliaRegionProbe create(Logger logger) {
        try {
            return new FoliaRegionProbe(logger);
        } catch (Throwable throwable) {
            logger.warning("Folia region TPS probe unavailable: " + throwable);
            return null;
        }
    }

    /** Sample for the region ticking this thread, or null. */
    StressDriverPlugin.RegionSample sampleCurrentRegion() {
        if (failed) {
            return null;
        }
        try {
            Object region = getCurrentRegion.invoke(null);
            if (region == null) {
                return null;
            }
            Object handle = getHandle.invoke(getData.invoke(region));
            Object report = getTickReport5s.invoke(handle, System.nanoTime());
            if (report == null) {
                return null;
            }
            double tps = (Double) average.invoke(segmentAll.invoke(tpsData.invoke(report)));
            // Folia keeps tick times in nanoseconds.
            double mspt = (Double) average.invoke(segmentAll.invoke(timePerTickData.invoke(report))) / 1.0e6;
            double util = (Double) utilisation.invoke(report);
            return new StressDriverPlugin.RegionSample(System.identityHashCode(region), tps, mspt, util,
                    System.currentTimeMillis());
        } catch (Throwable throwable) {
            failed = true;
            logger.warning("Folia region TPS probe failed, region stats disabled: " + throwable);
            return null;
        }
    }
}

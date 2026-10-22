package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VeilMetricsTimingTest {
    private static final long W = VeilMetrics.Timing.WINDOW_MILLIS;
    private static final long MS = 1_000_000L;

    @Test
    void oneSpikeNoLongerPinsTheMaxForever() {
        VeilMetrics.Timing timing = new VeilMetrics.Timing();
        long start = 10 * W;
        timing.record(1380 * MS, start);       // GC pause
        timing.record(2 * MS, start + 1000);
        assertEquals(1380.0, timing.maxMillis(start + 1000), 1e-9);

        // Still visible during the next window...
        timing.record(3 * MS, start + W);
        assertEquals(1380.0, timing.maxMillis(start + W), 1e-9);

        // ...and gone after that.
        timing.record(4 * MS, start + 2 * W);
        assertEquals(4.0, timing.maxMillis(start + 2 * W), 1e-9);
        assertEquals(0.0, timing.maxMillis(start + 10 * W), 1e-9, "no samples recently");
    }

    @Test
    void averageStillCoversEverySample() {
        VeilMetrics.Timing timing = new VeilMetrics.Timing();
        timing.record(2 * MS, 0);
        timing.record(4 * MS, 5 * W);
        assertEquals(3.0, timing.averageMillis(), 1e-9);
        assertEquals(2, timing.samples());
    }
}

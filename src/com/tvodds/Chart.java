package com.tvodds;

import java.util.Arrays;

/**
 * The hi-fi chart pipeline: outlier cleaning, time buckets, pixel stabilization, nice axis bounds.
 * The constants are tuned together for the stepped look; change them with care. Units are dp, which
 * play the role of CSS px.
 *
 *   hampelClean (values) -> pin tick -> bucketFill (shared window, n from plot width)
 *   -> niceAxisBounds (all lines) -> map to dp -> stabilizePx -> 2dp linear polyline
 */
final class Chart {
    static final float RUN = 1.5f, VMOVE = 2.5f, STROKE = 2f;
    static final int BUCKET_BASE = 400;

    private Chart() {}

    static int bucketCount(float plotWidth) {
        return Math.max(2, Math.min(BUCKET_BASE, (int) Math.floor(plotWidth / RUN) + 1));
    }

    // Outlier rejection (Hampel, window ±2): a tick is pulled to its window median when it sits
    // further away than max(3·1.4826·MAD, 4¢). Windows read the ORIGINAL values; the last tick
    // is never touched; the first tick gets a forward-window check.
    static double[] hampelClean(double[] v) {
        double[] out = v.clone();
        if (v.length < 5) return out;
        {
            double[] win = Arrays.copyOfRange(v, 0, 5);
            Arrays.sort(win);
            double med = win[2];
            double[] dev = new double[5];
            for (int i = 0; i < 5; i++) dev[i] = Math.abs(win[i] - med);
            Arrays.sort(dev);
            if (Math.abs(v[0] - med) > Math.max(3 * 1.4826 * dev[2], 0.04)) out[0] = med;
        }
        for (int i = 1; i < v.length - 1; i++) {
            int lo = Math.max(0, i - 2), hi = Math.min(v.length, i + 3);
            double[] win = Arrays.copyOfRange(v, lo, hi);
            Arrays.sort(win);
            double med = win[win.length / 2];
            double[] dev = new double[win.length];
            for (int j = 0; j < win.length; j++) dev[j] = Math.abs(win[j] - med);
            Arrays.sort(dev);
            double mad = dev[dev.length / 2];
            if (Math.abs(v[i] - med) > Math.max(3 * 1.4826 * mad, 0.04)) out[i] = med;
        }
        return out;
    }

    // n uniform time slices across the window, each carrying the last trade at/before it.
    static double[] bucketFill(double[] times, double[] vals, double winMin, double winMax, int n) {
        double span = Math.max(1e-9, winMax - winMin);
        double[] out = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double tt = winMin + (i / (double) (n - 1)) * span;
            while (k + 1 < times.length && times[k + 1] <= tt) k++;
            out[i] = times[k] <= tt ? vals[k] : vals[0];
        }
        return out;
    }

    private static final double[] NICE_STEPS_S = {1, 2, 5, 10, 15, 20, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200};

    /**
     * Slice times for a live, growing window: the bucketFill slices, but on a fixed clock grid
     * (a nice step ≥ span/(n−1), aligned to multiples of the step) instead of n points stretched
     * across [winMin, winMax]. Between frames every older slice lands on the same instant, so the
     * stabilized steps don't shimmer as the window grows; only the newest slice moves. First and
     * last slices are clamped to the window so the line still ends on the latest value.
     */
    static double[] gridSlices(double winMin, double winMax, int n) {
        double raw = Math.max(1e-9, winMax - winMin) / Math.max(1, n - 1);
        double step = NICE_STEPS_S[NICE_STEPS_S.length - 1];
        for (double s : NICE_STEPS_S) if (s >= raw) { step = s; break; }
        if (raw > step) step = Math.ceil(raw / step) * step;
        long k0 = (long) Math.floor(winMin / step), k1 = (long) Math.ceil(winMax / step);
        double[] out = new double[(int) (k1 - k0) + 1];
        for (int i = 0; i < out.length; i++) out[i] = Math.min(winMax, Math.max(winMin, (k0 + i) * step));
        return out;
    }

    /** bucketFill's carry-forward sampling at explicit slice times (ascending). */
    static double[] sampleAt(double[] times, double[] vals, double[] slices) {
        double[] out = new double[slices.length];
        int k = 0;
        for (int i = 0; i < slices.length; i++) {
            double tt = slices[i];
            while (k + 1 < times.length && times[k + 1] <= tt) k++;
            out[i] = times[k] <= tt ? vals[k] : vals[0];
        }
        return out;
    }

    // Axis framing: span floored at 0.1, snapped out to a nice 1/2/5 step.
    static double[] niceAxisBounds(double lo, double hi) {
        if (hi - lo < 0.1) {
            double c = (lo + hi) / 2;
            lo = c - 0.05;
            hi = c + 0.05;
            if (lo < 0) { hi = Math.min(1, hi - lo); lo = 0; }
            if (hi > 1) { lo = Math.max(0, lo - (hi - 1)); hi = 1; }
        }
        for (double step : new double[] {0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5}) {
            double aMin = Math.max(0, Math.floor(lo / step + 1e-9) * step);
            double aMax = Math.min(1, Math.ceil(hi / step - 1e-9) * step);
            if (Math.round((aMax - aMin) / step) <= 6) return new double[] {aMin, aMax};
        }
        return new double[] {0, 1};
    }

    // A move renders only when it is >= VMOVE vertical and (>= RUN horizontal since the last
    // kept move, or it is the final point). Everything else holds the last visible y.
    static float[][] stabilizePx(float[][] pts) {
        if (pts.length < 3) return pts;
        float[][] out = new float[pts.length][];
        out[0] = pts[0];
        float[] lastVis = pts[0];
        for (int j = 1; j < pts.length; j++) {
            float[] p = pts[j];
            boolean vis = Math.abs(p[1] - lastVis[1]) >= VMOVE
                    && (Math.abs(p[0] - lastVis[0]) >= RUN || j == pts.length - 1);
            if (vis) { out[j] = p; lastVis = p; } else out[j] = new float[] {p[0], lastVis[1]};
        }
        return out;
    }
}

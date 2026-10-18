package com.tvodds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.io.InputStream;

/**
 * Top-left overlay: a subtle dark corner gradient, the two-line win-probability chart (hi-fi
 * pipeline, team colors), live % labels at the pulsing line endpoints, and "Live Odds on
 * [Polymarket]". Drawn in dp units. Geometry is rebuilt per price update; frames only animate.
 */
final class OddsView extends View {
    /** Immutable frame of data handed over from the service thread. */
    static final class Snap {
        String awayName, homeName;
        int awayColor, homeColor;
        double[] t, p; // seconds, away probability (pin tick already appended)
    }

    /** Everything onDraw needs, computed once per Snap. */
    private static final class Frame {
        Path awayPath = new Path(), homePath = new Path();
        float awayY, homeY, awayTop, homeTop;
        int awayColor, homeColor;
        String awayName, homeName, awayPct, homePct;
        /** Label column width for this game: widest team name or "100%" (fixed per game, so no jitter). */
        float lblW;
    }

    // Layout (dp). The chart bleeds off the left edge of the screen behind a fade; labels and the
    // brand row stay inside the TV's title-safe area.
    static final float VIEW_W = 600, VIEW_H = 320;
    /** Overall size of the overlay; layout below is authored at 1.0. */
    private static final float SCALE = 0.9f;
    private static final float PLOT_L = 0, PLOT_R = 348, PLOT_W = PLOT_R - PLOT_L, Y0 = 28, PLOT_H = 120;
    private static final float BRAND_X = 60, FADE_W = 96;
    private static final float LBL_GAP = 16, LBL_H = 46;
    // Content extents (for placing it in the 3×3 grid): labels may ride 14dp above the plot, the brand
    // row ends ~40dp below it, and the label column is at most ~104dp wide ("Commanders", "100%").
    private static final float CONTENT_TOP = Y0 - 14, CONTENT_BOTTOM = Y0 + PLOT_H + 40;
    private static final float CONTENT_RIGHT = PLOT_R + LBL_GAP + 104, EDGE_MARGIN_X = 36;
    private static final long PULSE_MS = 1800;

    // The logo PNG is the mark's 42.7×10 viewBox; glyph baseline at y≈6.82, cap top at y≈2.26.
    private static final float LOGO_VB_W = 42.7f, LOGO_VB_H = 10f, LOGO_BASELINE = 6.82f, LOGO_CAP = 6.82f - 2.26f;

    private final float d;
    private Frame frame;
    private final Paint bg = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG), pct = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG), axis = new Paint();
    private final Paint fade = new Paint();
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap logo;
    private final RectF logoDst = new RectF();
    // Grid position (column/row 0..2) and the resulting content offset inside the view.
    private int col, row;
    private float offX, offY;
    /** Backdrop strength: 1 = the authored gradient, 0 = none, up to 1.5 = darker (alphas clamp at opaque). */
    private float backdrop = 1f;
    private static final int[] BACKDROP_ALPHAS = {0xC8, 0xA0, 0x48, 0x00};

    OddsView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);

        setPosition(0);

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Chart.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);

        // Label type: SF Pro (names) and SF Pro Condensed Bold (the big %, -0.5px tracking),
        // with tabular digits so the odometer cells hold still.
        Typeface medium = font(c, "fonts/SFProDisplay-Medium.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTypeface(medium);
        name.setTextSize(13);
        pct.setTypeface(font(c, "fonts/SFProCondensed-Bold.ttf", Typeface.create("sans-serif-condensed", Typeface.BOLD)));
        pct.setTextSize(30);
        pct.setLetterSpacing(-0.018f);
        pct.setFontFeatureSettings("'tnum'");
        caption.setTypeface(medium);
        caption.setTextSize(15.5f);
        caption.setColor(0xD9FFFFFF);
        for (Paint p : new Paint[] {name, pct, caption}) p.setShadowLayer(4, 0, 1, 0x99000000);
        axis.setColor(0x1FFFFFFF);
        axis.setStrokeWidth(1);
        // Left-edge mask: the lines (and axis) fade in over the first FADE_W dp from the screen edge.
        fade.setShader(new LinearGradient(PLOT_L, 0, PLOT_L + FADE_W, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        logoPaint.setAlpha(0xD9);

        Bitmap b = null;
        try (InputStream in = c.getAssets().open("pm_logo.png")) {
            b = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) { }
        logo = b;
    }

    // Y-axis lock: bounds only widen when the price breaks out, and only tighten once the data
    // range has shrunk well inside them, so a live tick doesn't rescale the whole chart.
    private String axisKey;
    private double[] axisLock;

    private static Typeface font(Context c, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    void setSnap(Snap s) {
        if (s != null && !(s.awayName + "|" + s.homeName).equals(axisKey)) { awayOdo.reset(); homeOdo.reset(); }
        frame = s != null && s.t != null && s.t.length >= 1 ? build(s) : null;
        if (frame != null) {
            long now = SystemClock.uptimeMillis();
            awayOdo.set(frame.awayPct, now);
            homeOdo.set(frame.homePct, now);
        }
        invalidate();
    }

    /**
     * Places the overlay in a 3×3 grid (0 = top-left … 8 = bottom-right). The window itself is
     * anchored by the service; this shifts the content inside it and re-aims the backdrop gradient.
     * Left column: the chart bleeds off the screen edge. Middle/right: the whole widget sits inside,
     * labels {@link #EDGE_MARGIN_X} from the right edge.
     */
    void setPosition(int pos) {
        col = Math.max(0, Math.min(2, pos % 3));
        row = Math.max(0, Math.min(2, pos / 3));
        offX = col == 0 ? 0 : col == 1 ? (VIEW_W - CONTENT_RIGHT) / 2 : VIEW_W - EDGE_MARGIN_X - CONTENT_RIGHT;
        offY = row == 0 ? 0 : row == 1 ? (VIEW_H - CONTENT_TOP - CONTENT_BOTTOM) / 2 : VIEW_H - CONTENT_TOP - CONTENT_BOTTOM;

        // Subtle elliptical falloff from the anchoring corner/edge (or center): it reaches zero
        // before every view edge, so there are no hard edges anywhere.
        float cx = col == 0 ? 0 : col == 1 ? VIEW_W / 2 : VIEW_W, rx = col == 1 ? VIEW_W / 2 : VIEW_W;
        float cy = row == 0 ? 0 : row == 1 ? VIEW_H / 2 : VIEW_H, ry = row == 1 ? VIEW_H / 2 : VIEW_H;
        int[] colors = new int[BACKDROP_ALPHAS.length];
        for (int i = 0; i < colors.length; i++) colors[i] = Math.min(255, Math.round(BACKDROP_ALPHAS[i] * backdrop)) << 24;
        RadialGradient g = new RadialGradient(0, 0, 1, colors, new float[] {0f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        bg.setShader(g);
        invalidate();
    }

    /** Backdrop strength in percent (0–150); rebuilds the gradient at the current position. */
    void setBackdrop(int percent) {
        backdrop = Math.max(0, Math.min(150, percent)) / 100f;
        setPosition(row * 3 + col);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(VIEW_W * d * SCALE), Math.round(VIEW_H * d * SCALE));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(d * SCALE, d * SCALE);
        c.drawRect(0, 0, VIEW_W, VIEW_H, bg);
        Frame f = frame;
        if (f != null) {
            c.save();
            // Middle/right columns size to this game's real label column so the labels sit
            // EDGE_MARGIN_X from the screen edge (left column bleeds off the edge instead).
            float contentRight = PLOT_R + LBL_GAP + f.lblW;
            float x = col == 0 ? 0 : col == 1 ? (VIEW_W - contentRight) / 2 : VIEW_W - EDGE_MARGIN_X - contentRight;
            c.translate(x, offY);
            drawFrame(c, f);
            c.restore();
            if (getAlpha() > 0) postInvalidateOnAnimation(); // keep the endpoint pulse running
        }
        c.restore();
    }

    // ── geometry (once per price update) ──────────────────────────────────────

    private Frame build(Snap s) {
        Frame f = new Frame();
        float plotT = Y0, plotB = Y0 + PLOT_H;
        double[] t = s.t, away = Chart.hampelClean(s.p);
        double tMin = t[0], tMax = Math.max(t[t.length - 1], tMin + 1);
        double[] slices = Chart.gridSlices(tMin, tMax, Chart.bucketCount(PLOT_W));
        int n = slices.length;
        double[] a = Chart.sampleAt(t, away, slices);
        double[] h = new double[n];
        double lo = 1, hi = 0;
        for (int i = 0; i < n; i++) {
            h[i] = 1 - a[i];
            lo = Math.min(lo, Math.min(a[i], h[i]));
            hi = Math.max(hi, Math.max(a[i], h[i]));
        }
        double[] ax = Chart.niceAxisBounds(lo, hi);
        String key = s.awayName + "|" + s.homeName;
        if (key.equals(axisKey) && axisLock != null) {
            boolean inside = lo >= axisLock[0] && hi <= axisLock[1];
            boolean muchTighter = (ax[1] - ax[0]) < 0.5 * (axisLock[1] - axisLock[0]);
            if (inside && !muchTighter) ax = axisLock;
            else if (!inside) ax = Chart.niceAxisBounds(Math.min(lo, axisLock[0]), Math.max(hi, axisLock[1]));
        }
        axisKey = key;
        axisLock = ax;
        double vMin = ax[0], vRange = Math.max(ax[1] - ax[0], 0.1);

        float[][] pa = new float[n][], ph = new float[n][];
        for (int i = 0; i < n; i++) {
            float x = (float) (PLOT_L + (slices[i] - tMin) / (tMax - tMin) * PLOT_W);
            pa[i] = new float[] {x, (float) (plotB - ((a[i] - vMin) / vRange) * PLOT_H)};
            ph[i] = new float[] {x, (float) (plotB - ((h[i] - vMin) / vRange) * PLOT_H)};
        }
        pa = Chart.stabilizePx(pa);
        ph = Chart.stabilizePx(ph);
        toPath(pa, f.awayPath);
        toPath(ph, f.homePath);

        f.awayColor = legible(s.awayColor);
        f.homeColor = legible(s.homeColor);
        if (colorDistance(f.awayColor, f.homeColor) < 40) f.homeColor = 0xFFE6E8EC;
        f.awayY = pa[n - 1][1];
        f.homeY = ph[n - 1][1];

        // Labels beside the line ends, kept vertically separated.
        long awayPct = Math.round(s.p[s.p.length - 1] * 100);
        f.awayPct = awayPct + "%";
        f.homePct = (100 - awayPct) + "%";
        f.awayName = s.awayName;
        f.homeName = s.homeName;
        f.lblW = Math.max(pct.measureText("100%"), Math.max(name.measureText(s.awayName), name.measureText(s.homeName)));
        float awayTop = f.awayY - 8, homeTop = f.homeY - 8, minSep = LBL_H + 4;
        if (Math.abs(awayTop - homeTop) < minSep) {
            float mid = (awayTop + homeTop) / 2;
            if (awayTop < homeTop) { awayTop = mid - minSep / 2; homeTop = mid + minSep / 2; }
            else { homeTop = mid - minSep / 2; awayTop = mid + minSep / 2; }
        }
        float minTop = plotT - 14, maxTop = plotB + 14 - LBL_H;
        f.awayTop = Math.max(minTop, Math.min(maxTop, awayTop));
        f.homeTop = Math.max(minTop, Math.min(maxTop, homeTop));
        return f;
    }

    private static void toPath(float[][] pts, Path p) {
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) p.moveTo(pts[i][0], pts[i][1]);
            else p.lineTo(pts[i][0], pts[i][1]);
        }
    }

    // ── drawing (every frame) ─────────────────────────────────────────────────

    private void drawFrame(Canvas c, Frame f) {
        float plotR = PLOT_R, plotB = Y0 + PLOT_H;
        int layer = c.saveLayer(PLOT_L, 0, plotR + Chart.STROKE, VIEW_H, null);
        c.drawLine(PLOT_L, plotB + 8, plotR, plotB + 8, axis);
        line.setColor(f.homeColor);
        c.drawPath(f.homePath, line);
        line.setColor(f.awayColor);
        c.drawPath(f.awayPath, line);
        c.drawRect(PLOT_L, 0, PLOT_L + FADE_W, VIEW_H, fade);
        c.restoreToCount(layer);

        float phase = (SystemClock.uptimeMillis() % PULSE_MS) / (float) PULSE_MS;
        pulse(c, plotR, f.homeY, f.homeColor, phase);
        pulse(c, plotR, f.awayY, f.awayColor, phase);

        label(c, plotR + LBL_GAP, f.awayTop, f.awayName, awayOdo, f.awayColor);
        label(c, plotR + LBL_GAP, f.homeTop, f.homeName, homeOdo, f.homeColor);

        brandRow(c, BRAND_X, plotB + 34);
    }

    // Solid 8dp dot with a ring that swells from the dot to ~26dp while fading out (the live pulse).
    private void pulse(Canvas c, float x, float y, int color, float phase) {
        float ease = 1 - (1 - phase) * (1 - phase) * (1 - phase); // easeOutCubic
        dot.setColor(color);
        dot.setAlpha(Math.round(0.45f * (1 - phase) * 255));
        c.drawCircle(x, y, 4 + 9 * ease, dot);
        dot.setAlpha(38);
        c.drawCircle(x, y, 7, dot);
        dot.setAlpha(255);
        c.drawCircle(x, y, 4, dot);
    }

    private void label(Canvas c, float x, float top, String team, Odo odo, int color) {
        name.setColor(color);
        pct.setColor(color);
        Paint.FontMetrics nf = name.getFontMetrics(), pf = pct.getFontMetrics();
        float nameBase = top - nf.ascent;
        c.drawText(team, x, nameBase, name);
        drawOdo(c, odo, x, nameBase + nf.descent - pf.ascent - 6, pct, pf);
    }

    // ── odometer ──────────────────────────────────────────────────────────────
    // Only changed digits roll: the new digit rises into its cell from below on an uptick (drops in
    // from above on a downtick), overshooting 6% before settling, over 350ms. Cells are clipped.

    private static final long ROLL_MS = 350;
    private final Odo awayOdo = new Odo(), homeOdo = new Odo();
    private final android.graphics.Rect digitBounds = new android.graphics.Rect();

    private static final class Odo {
        String cur, prev;
        long t0;
        boolean up;

        void set(String next, long now) {
            if (next.equals(cur)) return;
            if (cur != null) {
                prev = cur;
                t0 = now;
                up = num(next) >= num(cur);
            }
            cur = next;
        }

        void reset() {
            cur = prev = null;
        }

        private static int num(String s) {
            try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
        }
    }

    private static float easeOutCubic(float p) {
        float q = 1 - p;
        return 1 - q * q * q;
    }

    /** Vertical offset of a rolling digit, in cell heights (1.1 → −0.06 overshoot → 0). */
    private static float flipOffsetFrac(float p) {
        if (p >= 1) return 0;
        if (p < 0.7f) return 1.1f + (-0.06f - 1.1f) * easeOutCubic(p / 0.7f);
        return -0.06f + 0.06f * easeOutCubic((p - 0.7f) / 0.3f);
    }

    private void drawOdo(Canvas c, Odo o, float x, float baseline, Paint p, Paint.FontMetrics fm) {
        if (o.cur == null) return;
        float rollP = o.prev == null ? 1 : Math.min(1f, (SystemClock.uptimeMillis() - o.t0) / (float) ROLL_MS);
        String prev = rollP < 1 ? o.prev : null;
        float off = flipOffsetFrac(rollP);
        boolean sameLen = prev != null && prev.length() == o.cur.length();
        float cell = p.getTextSize(), cx = x;
        p.getTextBounds("0", 0, 1, digitBounds); // clip to the digits' own height, not the font ascent
        float clipTop = baseline + digitBounds.top - cell * 0.06f, clipBottom = baseline + cell * 0.06f;
        for (int i = 0; i < o.cur.length(); i++) {
            String ch = o.cur.substring(i, i + 1);
            float w = p.measureText(ch);
            boolean changed = prev != null && (!sameLen || prev.charAt(i) != o.cur.charAt(i));
            if (changed) {
                c.save();
                c.clipRect(cx - 3, clipTop, cx + w + 3, clipBottom);
                c.drawText(ch, cx, baseline + (o.up ? off : -off) * cell, p);
                c.restore();
            } else {
                c.drawText(ch, cx, baseline, p);
            }
            cx += w;
        }
    }

    /** "Live Odds on" + the Polymarket logo on one baseline, the logo's "Polymarket" at the text's cap height. */
    private void brandRow(Canvas c, float x, float baseline) {
        String text = "Live Odds on";
        c.drawText(text, x, baseline, caption);
        if (logo == null) return;
        Paint.FontMetrics fm = caption.getFontMetrics();
        float textCap = -fm.ascent * 0.72f;
        float unit = textCap / LOGO_CAP; // dp per logo viewBox unit
        float lx = x + caption.measureText(text) + 7;
        float top = baseline - LOGO_BASELINE * unit;
        logoDst.set(lx, top, lx + LOGO_VB_W * unit, top + LOGO_VB_H * unit);
        c.drawBitmap(logo, null, logoDst, logoPaint);
    }

    // ── color ─────────────────────────────────────────────────────────────────

    /** Brightens team colors to read over live video, keeping hue and saturation (HSV value ≥ 0.82). */
    static int legible(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(hsv[2], 0.82f);
        return Color.HSVToColor(hsv);
    }

    private static double colorDistance(int x, int y) {
        int dr = Color.red(x) - Color.red(y), dg = Color.green(x) - Color.green(y), db = Color.blue(x) - Color.blue(y);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}

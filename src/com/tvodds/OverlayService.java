package com.tvodds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches what YouTube TV is playing, finds the matching Polymarket US moneyline, and keeps the
 * overlay chart fed: price history once per game, then live prices from the markets websocket
 * (REST /bbo when the socket is quiet or down).
 *
 * The chart is drawn on a delay so market moves line up with the broadcast: a base delay (set from
 * the phone control page) plus however far the viewer has paused/rewound behind live.
 */
public class OverlayService extends Service implements ControlServer.Host {
    static final String TAG = Poly.TAG;
    static final String YOUTUBE_TV = "com.google.android.youtube.tvunplugged";
    static volatile boolean running;
    static final String ACTION_TOGGLE = "com.tvodds.TOGGLE", ACTION_ON = "com.tvodds.ON";

    private static final long TITLE_POLL_MS = 2000, REST_FALLBACK_MS = 10_000, TICK_SPACING_S = 2;
    static final int DEFAULT_DELAY_S = 20, MAX_DELAY_S = 120;
    /** Position jitter below this is ignored when measuring how far behind live playback is. */
    private static final long DVR_JITTER_MS = 1500;

    private WindowManager wm;
    private OddsView view;
    private boolean shown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread netThread;
    private Handler net;

    // Owned by the net thread (and the websocket thread under `lock`).
    private final Object lock = new Object();
    private String curTitle;
    private Poly.Game game;
    private final List<double[]> ticks = new ArrayList<>();
    private long lastLivePxMs;
    private Thread wsThread;
    private volatile WsClient ws;
    private volatile boolean destroyed;

    // Delay: base seconds (persisted) + DVR lag measured from YouTube TV's playback position.
    private SharedPreferences prefs;
    private volatile int delayS = DEFAULT_DELAY_S;
    private volatile int position; // 3×3 grid, 0 = top-left (default) … 8 = bottom-right
    private volatile int backdropPct = 100; // corner gradient strength, 0–150%
    private volatile boolean enabled = true; // on/off switch: off hides the overlay, keeps the control page up
    private WindowManager.LayoutParams lp;
    private volatile long dvrMs;
    private volatile boolean playing;
    private long minLagMs = Long.MAX_VALUE;
    private ControlServer control;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            render(); // delayed data becomes visible with time, not only with new ticks
            main.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startAsForeground();

        wm = getSystemService(WindowManager.class);
        view = new OddsView(this);
        view.setAlpha(0f);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("tvodds");
        prefs = getSharedPreferences("tvodds", MODE_PRIVATE);
        position = prefs.getInt("pos", 0);
        backdropPct = prefs.getInt("bg", 100);
        enabled = prefs.getBoolean("on", true);
        lp.gravity = gravityFor(position);
        view.setBackdrop(backdropPct);
        view.setPosition(position);
        wm.addView(view, lp);

        netThread = new HandlerThread("tvodds-net");
        netThread.start();
        net = new Handler(netThread.getLooper());
        delayS = prefs.getInt("delay_s", DEFAULT_DELAY_S);
        net.post(this::tick);
        startWs();
        control = new ControlServer(this);
        control.start();
        main.post(heartbeat);
        Log.i(TAG, "overlay service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) setEnabled(!enabled);
        else if (ACTION_ON.equals(action)) setEnabled(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        destroyed = true;
        WsClient w = ws;
        if (w != null) w.close();
        if (wsThread != null) wsThread.interrupt();
        if (control != null) control.close();
        main.removeCallbacksAndMessages(null);
        netThread.quitSafely();
        try { wm.removeView(view); } catch (Exception ignored) { }
        Log.i(TAG, "overlay service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── main loop (net thread) ────────────────────────────────────────────────

    private void tick() {
        if (destroyed) return;
        try {
            Playback pb = playback();
            boolean nowPlaying = pb != null && pb.playing;
            if (nowPlaying != playing) {
                playing = nowPlaying;
                Log.i(TAG, nowPlaying ? "playing" : "not playing (overlay hidden, game kept)");
                render();
            }
            String title = pb != null ? pb.title : null;
            if (title != null && !title.equals(curTitle)) {
                Log.i(TAG, "now playing: \"" + title + "\"");
                curTitle = title;
                minLagMs = Long.MAX_VALUE;
                dvrMs = 0;
                setGame(null);
                Poly.Game g = Poly.find(title);
                if (g != null && title.equals(curTitle)) loadGame(g);
            }
            if (nowPlaying && pb.lagMs != Long.MIN_VALUE) {
                // lag = (last position update time) − position: constant at the live edge, grows by
                // exactly the time spent paused or rewound, shrinks back when you jump to live.
                minLagMs = Math.min(minLagMs, pb.lagMs);
                long extra = pb.lagMs - minLagMs;
                long dvr = extra < DVR_JITTER_MS ? 0 : extra;
                if (Math.abs(dvr - dvrMs) >= 1000) Log.i(TAG, "behind live by " + dvr / 1000 + "s");
                dvrMs = dvr;
            }
            if (title == null) {
                // Not watching YouTube TV right now; keep the game so a resume continues seamlessly.
            } else if (game != null && System.currentTimeMillis() - lastLivePxMs > REST_FALLBACK_MS) {
                double px = Poly.currentPx(game.marketSlug);
                if (!Double.isNaN(px)) addTick(game.marketSlug, px, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "tick failed: " + e);
        }
        net.postDelayed(this::tick, TITLE_POLL_MS);
    }

    private void loadGame(Poly.Game g) throws Exception {
        double[][] h = Poly.history(g);
        synchronized (lock) {
            ticks.clear();
            for (int i = 0; i < h[0].length; i++) ticks.add(new double[] {h[0][i], h[1][i]});
            game = g;
            lastLivePxMs = 0;
        }
        Log.i(TAG, "history " + h[0].length + " pts for " + g.marketSlug);
        double px = Poly.currentPx(g.marketSlug);
        addTick(g.marketSlug, Double.isNaN(px) && !ticks.isEmpty() ? ticks.get(ticks.size() - 1)[1] : px, false);
        WsClient w = ws;
        if (w != null) w.close(); // reconnects and subscribes to the new market
    }

    private void setGame(Poly.Game g) {
        synchronized (lock) {
            game = g;
            ticks.clear();
        }
        WsClient w = ws;
        if (w != null) w.close();
        render();
    }

    /** Appends the live price as a tick (spaced ≥2s apart; closer updates replace the last one). */
    private void addTick(String slug, double px, boolean fromWs) {
        if (Double.isNaN(px)) return;
        synchronized (lock) {
            if (game == null || !game.marketSlug.equals(slug)) return;
            double now = System.currentTimeMillis() / 1000.0;
            int last = ticks.size() - 1;
            if (last >= 1 && now - ticks.get(last - 1)[0] < TICK_SPACING_S) ticks.set(last, new double[] {now, px});
            else ticks.add(new double[] {now, px});
            if (fromWs) lastLivePxMs = System.currentTimeMillis();
            if (!fromWs || ++wsTicks % 25 == 1) Log.d(TAG, (fromWs ? "ws" : "rest") + " px " + px + " ticks=" + ticks.size());
        }
        render();
    }

    private int wsTicks;
    private long lastRenderMs;
    private boolean renderQueued;

    /** Builds an immutable frame and posts it to the UI thread, at most ~4x a second. */
    private void render() {
        synchronized (lock) {
            if (renderQueued) return;
            long wait = Math.max(0, 250 - (System.currentTimeMillis() - lastRenderMs));
            renderQueued = true;
            main.postDelayed(this::renderNow, wait);
        }
    }

    private void renderNow() {
        OddsView.Snap s = null;
        synchronized (lock) {
            renderQueued = false;
            lastRenderMs = System.currentTimeMillis();
            // Show the market as of (now − delay): ticks up to the cutoff, then a pin at the cutoff
            // carrying the last visible price so the line ends exactly "delay" seconds ago.
            double cutoff = System.currentTimeMillis() / 1000.0 - delayS - dvrMs / 1000.0;
            int n = 0;
            while (n < ticks.size() && ticks.get(n)[0] <= cutoff) n++;
            if (enabled && playing && game != null && n > 0) {
                s = new OddsView.Snap();
                s.awayName = game.away.name;
                s.homeName = game.home.name;
                s.awayColor = game.away.color;
                s.homeColor = game.home.color;
                s.t = new double[n + 1];
                s.p = new double[n + 1];
                for (int i = 0; i < n; i++) { s.t[i] = ticks.get(i)[0]; s.p[i] = ticks.get(i)[1]; }
                s.t[n] = Math.max(cutoff, s.t[n - 1]);
                s.p[n] = s.p[n - 1];
            }
        }
        if (s != null) view.setSnap(s);
        boolean show = s != null;
        if (show != shown) {
            shown = show;
            view.animate().alpha(show ? 1f : 0f).setDuration(show ? 450 : 250).start();
        }
    }

    // ── what's playing ────────────────────────────────────────────────────────

    private static final class Playback {
        String title;
        boolean playing;
        long lagMs = Long.MIN_VALUE;
    }

    /** YouTube TV's current program and play state, or null when it has no active session. */
    private Playback playback() {
        MediaSessionManager msm = getSystemService(MediaSessionManager.class);
        List<MediaController> sessions;
        try {
            sessions = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions not readable; run: adb shell cmd notification allow_listener "
                    + "com.tvodds/com.tvodds.MediaListener");
            return null;
        }
        for (MediaController mc : sessions) {
            if (!YOUTUBE_TV.equals(mc.getPackageName())) continue;
            PlaybackState st = mc.getPlaybackState();
            int state = st == null ? PlaybackState.STATE_NONE : st.getState();
            MediaMetadata md = mc.getMetadata();
            if (md == null) continue;
            MediaDescription desc = md.getDescription();
            CharSequence t = desc != null ? desc.getTitle() : null;
            if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null || t.length() == 0) continue;
            Playback pb = new Playback();
            pb.title = t.toString();
            pb.playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
            if (state == PlaybackState.STATE_PLAYING && st.getPlaybackSpeed() == 1f && st.getPosition() > 0) {
                pb.lagMs = st.getLastPositionUpdateTime() - st.getPosition();
            }
            return pb;
        }
        return null;
    }

    // ── control page (ControlServer.Host) ─────────────────────────────────────

    @Override
    public int delay() {
        return delayS;
    }

    @Override
    public void setDelay(int seconds) {
        delayS = Math.max(0, Math.min(MAX_DELAY_S, seconds));
        prefs.edit().putInt("delay_s", delayS).apply();
        Log.i(TAG, "delay set to " + delayS + "s");
        render();
    }

    @Override
    public int position() {
        return position;
    }

    @Override
    public void setPosition(int pos) {
        if (pos < 0 || pos > 8) return;
        position = pos;
        prefs.edit().putInt("pos", pos).apply();
        Log.i(TAG, "position set to " + pos);
        main.post(() -> {
            lp.gravity = gravityFor(pos);
            view.setPosition(pos);
            try { wm.updateViewLayout(view, lp); } catch (Exception e) { Log.w(TAG, "relayout: " + e); }
        });
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
        prefs.edit().putBoolean("on", on).apply();
        Log.i(TAG, "overlay " + (on ? "on" : "off"));
        render();
    }

    @Override
    public int backdrop() {
        return backdropPct;
    }

    @Override
    public void setBackdrop(int percent) {
        backdropPct = Math.max(0, Math.min(150, percent));
        prefs.edit().putInt("bg", backdropPct).apply();
        Log.i(TAG, "backdrop set to " + backdropPct + "%");
        int p = backdropPct;
        main.post(() -> view.setBackdrop(p));
    }

    /** Window anchor for a 3×3 grid cell: rows top/center/bottom × columns start/center/end. */
    private static int gravityFor(int pos) {
        int v = pos / 3 == 0 ? Gravity.TOP : pos / 3 == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM;
        int h = pos % 3 == 0 ? Gravity.START : pos % 3 == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.END;
        return v | h;
    }

    @Override
    public String statusJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("delay", delayS);
            j.put("pos", position);
            j.put("bg", backdropPct);
            j.put("on", enabled);
            j.put("dvr", Math.round(dvrMs / 1000.0));
            j.put("playing", playing);
            Poly.Game g;
            synchronized (lock) { g = game; }
            if (g != null) j.put("game", g.away.name + " at " + g.home.name);
        } catch (JSONException ignored) { }
        return j.toString();
    }

    // ── live prices (websocket thread) ────────────────────────────────────────

    private void startWs() {
        wsThread = new Thread(() -> {
            int backoffMs = 1000;
            while (!destroyed) {
                String slug;
                synchronized (lock) { slug = game != null ? game.marketSlug : null; }
                if (slug == null) {
                    sleep(1000);
                    continue;
                }
                final String sub = slug;
                WsClient w = new WsClient(Poly.MARKETS_WS, new WsClient.Listener() {
                    @Override
                    public void onOpen(WsClient c) throws IOException {
                        c.sendText("{\"subscribe\":{\"requestId\":\"tvodds\",\"subscriptionType\":"
                                + "\"SUBSCRIPTION_TYPE_MARKET_DATA_LITE\",\"marketSlugs\":[\"" + sub + "\"]}}");
                        Log.i(TAG, "ws subscribed " + sub);
                    }

                    private int seen;

                    @Override
                    public void onText(String text) {
                        if (seen++ < 2 || seen % 200 == 0) Log.d(TAG, "ws msg #" + seen + ": " + text.substring(0, Math.min(160, text.length())));
                        try {
                            JSONObject md = new JSONObject(text).optJSONObject("marketDataLite");
                            if (md == null || !sub.equals(md.optString("marketSlug"))) return;
                            addTick(sub, Poly.pxOf(md), true);
                        } catch (Exception e) {
                            Log.w(TAG, "ws parse: " + e);
                        }
                    }
                });
                ws = w;
                long started = System.currentTimeMillis();
                try {
                    w.run();
                } catch (IOException e) {
                    if (!destroyed) Log.w(TAG, "ws: " + e.getMessage());
                } finally {
                    w.close();
                    ws = null;
                }
                if (System.currentTimeMillis() - started > 30_000) backoffMs = 1000;
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }, "tvodds-ws");
        wsThread.setDaemon(true);
        wsThread.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("overlay", "Odds overlay", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "overlay")
                .setContentTitle("Odds overlay running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
}

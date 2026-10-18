package com.tvodds;

import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Polymarket US public REST (no token): league listings for matching, price history. */
final class Poly {
    static final String TAG = "tvodds";
    static final String GATEWAY = "https://gateway.polymarket.us";
    static final String MARKETS_WS = "wss://gateway-ws-markets.polymarket.us/v1/ws/markets";

    /** Searched in order when the playing title changes; listings are cached briefly. */
    static final String[] LEAGUES = {"nfl", "cfb", "nba", "nhl", "mlb", "cbb", "wnba", "ncaams",
            "mls", "epl", "ucl", "lal", "bun", "sea"};
    private static final long LISTING_TTL_MS = 5 * 60_000;
    private static final int PAGE = 50, MAX_PAGES = 3;

    private Poly() {}

    static final class Team {
        String name, abbr;
        int color;
        final Set<String> exact = new HashSet<>();
    }

    static final class Game {
        String eventSlug, marketSlug, title, league;
        Team away, home;
        long startMs;
        boolean live, ended;
    }

    // ── matching ──────────────────────────────────────────────────────────────

    private static final Pattern MATCHUP =
            Pattern.compile("^(.+?)\\s+(?:at|vs\\.?|v\\.?|@)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** "College Football: #7 Nebraska at Oregon (Replay)" -> ["Nebraska", "Oregon"]. */
    static String[] parseMatchup(String title) {
        if (title == null) return null;
        for (String seg : title.split("\\s*[:|•–—]\\s*|\\s+-\\s+")) {
            Matcher m = MATCHUP.matcher(seg.trim());
            if (m.matches()) return new String[] {cleanSide(m.group(1)), cleanSide(m.group(2))};
        }
        return null;
    }

    private static String cleanSide(String s) {
        return s.replaceAll("^\\s*(?:#|No\\.?\\s*)\\d+\\s+", "")
                .replaceAll("^\\(\\d+\\)\\s*", "")
                .replaceAll("\\s*\\(.*?\\)\\s*$", "")
                .trim();
    }

    static String norm(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.US).replace("&", " and ")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return n.replaceAll(" st$", " state");
    }

    /** 2 = exact name/alias/abbr, 1 = city-style prefix ("Philadelphia" ~ "Philadelphia Eagles"), 0 = none. */
    private static int sideScore(String side, Team t) {
        String s = norm(side);
        if (s.isEmpty()) return 0;
        if (t.exact.contains(s)) return 2;
        for (String v : t.exact) if (v.length() > s.length() && v.startsWith(s + " ")) return 1;
        return 0;
    }

    // ── listings ──────────────────────────────────────────────────────────────

    private static final Map<String, List<JSONObject>> listings = new HashMap<>();
    private static final Map<String, Long> listingAt = new HashMap<>();

    private static synchronized List<JSONObject> listing(String league) throws Exception {
        Long at = listingAt.get(league);
        if (at != null && System.currentTimeMillis() - at < LISTING_TTL_MS) return listings.get(league);
        List<JSONObject> out = new ArrayList<>();
        long horizon = System.currentTimeMillis() + 36 * 3600_000L;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONArray evs = new JSONObject(get(GATEWAY + "/v2/leagues/" + league + "/events?limit=" + PAGE
                    + "&offset=" + page * PAGE)).optJSONArray("events");
            if (evs == null) break;
            boolean pastHorizon = false;
            for (int i = 0; i < evs.length(); i++) {
                JSONObject e = evs.getJSONObject(i);
                out.add(e);
                if (parseTime(e.optString("startTime")) > horizon) pastHorizon = true;
            }
            if (evs.length() < PAGE || pastHorizon) break;
        }
        listings.put(league, out);
        listingAt.put(league, System.currentTimeMillis());
        return out;
    }

    /** Finds the Polymarket US game for a YouTube TV title, or null. Network; call off the UI thread. */
    static Game find(String title) {
        String[] sides = parseMatchup(title);
        if (sides == null) return null;
        Game best = null;
        double bestScore = 0;
        long now = System.currentTimeMillis();
        for (String league : LEAGUES) {
            List<JSONObject> evs;
            try {
                evs = listing(league);
            } catch (Exception e) {
                Log.w(TAG, "listing " + league + " failed: " + e);
                continue;
            }
            for (JSONObject e : evs) {
                Game g = parseGame(e, league);
                if (g == null) continue;
                int a = sideScore(sides[0], g.away), h = sideScore(sides[1], g.home);
                int a2 = sideScore(sides[0], g.home), h2 = sideScore(sides[1], g.away);
                int pair = Math.max(a > 0 && h > 0 ? a + h : 0, a2 > 0 && h2 > 0 ? a2 + h2 : 0);
                if (pair == 0) continue;
                // Prefer exact names, then a live game, then the one closest to now.
                double score = pair + (g.live ? 0.5 : 0) + (g.ended ? -0.5 : 0)
                        - Math.min(0.4, Math.abs(g.startMs - now) / (1000.0 * 3600 * 24 * 30));
                if (score > bestScore) { bestScore = score; best = g; }
            }
            if (bestScore >= 4) break;
        }
        if (best != null) Log.i(TAG, "matched \"" + title + "\" -> " + best.eventSlug + " (" + best.marketSlug + ")");
        else Log.i(TAG, "no market for \"" + title + "\"");
        return best;
    }

    private static Game parseGame(JSONObject e, String league) {
        JSONArray teams = e.optJSONArray("teams");
        JSONArray markets = e.optJSONArray("markets");
        if (teams == null || teams.length() != 2 || markets == null) return null;
        String marketSlug = null;
        for (int i = 0; i < markets.length(); i++) {
            JSONObject m = markets.optJSONObject(i);
            if (m != null && "moneyline".equals(m.optString("marketType"))) { marketSlug = m.optString("slug"); break; }
        }
        if (marketSlug == null || marketSlug.isEmpty()) return null;

        Team t0 = parseTeam(teams.optJSONObject(0)), t1 = parseTeam(teams.optJSONObject(1));
        Game g = new Game();
        g.eventSlug = e.optString("slug");
        g.marketSlug = marketSlug;
        g.title = e.optString("title");
        g.league = league;
        g.startMs = parseTime(e.optString("startTime"));
        g.live = e.optBoolean("live");
        g.ended = e.optBoolean("ended");
        // The event title runs away-first ("Nebraska vs. Oregon"), and the moneyline's long side is
        // the away team (longPrice = away-team probability).
        String[] ts = parseMatchup(g.title);
        boolean swap = ts != null && sideScore(ts[0], t1) > sideScore(ts[0], t0);
        g.away = swap ? t1 : t0;
        g.home = swap ? t0 : t1;
        return g;
    }

    private static Team parseTeam(JSONObject j) {
        Team t = new Team();
        if (j == null) j = new JSONObject();
        String name = j.optString("name"), alias = j.optString("alias"), safe = j.optString("safeName");
        t.abbr = j.optString("abbreviation").toUpperCase(Locale.US);
        // Label name: CFB's safeName is the school ("Nebraska"); NFL's is "PHI Eagles", so use the alias there.
        String[] safeWords = safe.split(" ", 2);
        boolean codePrefixed = safeWords.length == 2 && safeWords[0].matches("[A-Z]{2,4}") && !alias.isEmpty();
        t.name = codePrefixed ? alias : !safe.isEmpty() ? safe : name;
        // Prefer the dark-theme variant (color.dark): Polymarket's own team color tuned for dark UIs.
        JSONObject themed = j.optJSONObject("color");
        String hex = themed != null && !themed.optString("dark").isEmpty() ? themed.optString("dark")
                : j.optString("colorPrimary", "#9AA3AD");
        try {
            t.color = Color.parseColor(hex);
        } catch (IllegalArgumentException ex) {
            t.color = 0xFF9AA3AD;
        }
        for (String v : new String[] {name, alias, safe, name + " " + alias, safe + " " + alias, t.abbr}) {
            String n = norm(v);
            if (!n.isEmpty()) t.exact.add(n);
        }
        return t;
    }

    // ── prices ────────────────────────────────────────────────────────────────

    /**
     * Away-team probability history, sanitized for a game chart: sorted, book-coherent
     * (|long + short − 1| ≤ 0.05; listing placeholders print e.g. 0.96/0.96), and clamped to the
     * play window (snap to start, carrying the last pregame print as the opening value).
     * Returns {times(sec)[], values[]}.
     */
    static double[][] history(Game g) throws Exception {
        JSONArray h = new JSONObject(get(GATEWAY + "/v1/price-history?fidelity=1&fixedInterval=1&symbol="
                + g.marketSlug)).optJSONArray("history");
        List<double[]> all = new ArrayList<>(), coherent = new ArrayList<>();
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject p = h.getJSONObject(i);
            double t = p.optDouble("timestamp"), lp = p.optDouble("longPrice"), sp = p.optDouble("shortPrice");
            if (Double.isNaN(t) || Double.isNaN(lp)) continue;
            all.add(new double[] {t, lp});
            if (Double.isNaN(sp) || Math.abs(lp + sp - 1) <= 0.05) coherent.add(new double[] {t, lp});
        }
        List<double[]> pts = coherent.size() >= 2 ? coherent : all;
        pts.sort((x, y) -> Double.compare(x[0], y[0]));

        double nowS = System.currentTimeMillis() / 1000.0;
        double startS = g.startMs / 1000.0;
        // Live or finished: the game window. Pregame: the last 6 hours of trading.
        double winMin = (g.startMs > 0 && startS < nowS) ? startS : nowS - 6 * 3600;
        List<double[]> win = new ArrayList<>();
        double[] lastBefore = null;
        for (double[] p : pts) {
            if (p[0] < winMin) lastBefore = p;
            else win.add(p);
        }
        if (lastBefore != null) win.add(0, new double[] {winMin, lastBefore[1]});
        double[] t = new double[win.size()], v = new double[win.size()];
        for (int i = 0; i < win.size(); i++) { t[i] = win.get(i)[0]; v[i] = win.get(i)[1]; }
        return new double[][] {t, v};
    }

    /** REST fallback for the live price (the markets websocket is the primary feed). */
    static double currentPx(String marketSlug) throws Exception {
        JSONObject md = new JSONObject(get(GATEWAY + "/v1/markets/" + marketSlug + "/bbo")).optJSONObject("marketData");
        return md == null ? Double.NaN : pxOf(md);
    }

    /** currentPx from a marketData / marketDataLite object. */
    static double pxOf(JSONObject md) {
        JSONObject c = md.optJSONObject("currentPx");
        if (c == null) return Double.NaN;
        try {
            return Double.parseDouble(c.optString("value"));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ── http ──────────────────────────────────────────────────────────────────

    static long parseTime(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}

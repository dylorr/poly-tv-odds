package com.tvodds;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Tiny LAN-only control page for tuning the odds delay from a phone while the game keeps playing:
 * http://<chromecast-ip>:8765. Routes: / (page), /api (status JSON), /set?delay=N&pos=0..8&bg=0..150&on=0|1, /nudge?by=±N.
 */
final class ControlServer implements Runnable {
    static final int PORT = 8765;

    interface Host {
        int delay();
        void setDelay(int seconds);
        /** Grid cell 0..8 (row-major, 0 = top-left). */
        int position();
        void setPosition(int pos);
        /** On/off switch: off hides the overlay but keeps this page reachable. */
        boolean enabled();
        void setEnabled(boolean on);
        /** Corner gradient strength in percent, 0–150 (100 = default). */
        int backdrop();
        void setBackdrop(int percent);
        /** Status JSON: {"delay":20,"pos":0,"dvr":0,"game":"…"}. */
        String statusJson();
    }

    private final Host host;
    private volatile ServerSocket server;

    ControlServer(Host host) {
        this.host = host;
    }

    void start() {
        Thread t = new Thread(this, "tvodds-http");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        ServerSocket s = server;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            server = ss;
            Log.i(Poly.TAG, "control page at http://" + lanAddress() + ":" + PORT);
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(3000);
                    handle(s);
                } catch (IOException e) {
                    if (!ss.isClosed()) Log.w(Poly.TAG, "http: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.w(Poly.TAG, "control server stopped: " + e.getMessage());
        }
    }

    private void handle(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        String req = in.readLine();
        if (req == null) return;
        String h;
        while ((h = in.readLine()) != null && !h.isEmpty()) { /* skip headers */ }
        String[] parts = req.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

        switch (path) {
            case "/":
                respond(s, "text/html; charset=utf-8", PAGE);
                return;
            case "/set":
                Integer v = intParam(query, "delay");
                if (v != null) host.setDelay(v);
                Integer pos = intParam(query, "pos");
                if (pos != null) host.setPosition(pos);
                Integer bg = intParam(query, "bg");
                if (bg != null) host.setBackdrop(bg);
                Integer on = intParam(query, "on");
                if (on != null) host.setEnabled(on != 0);
                respond(s, "application/json", host.statusJson());
                return;
            case "/nudge":
                Integer by = intParam(query, "by");
                if (by != null) host.setDelay(host.delay() + by);
                respond(s, "application/json", host.statusJson());
                return;
            case "/api":
                respond(s, "application/json", host.statusJson());
                return;
            default:
                respond(s, "text/plain", "not found");
        }
    }

    private static Integer intParam(String query, String key) {
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                try {
                    return Integer.parseInt(p[1].replace("%2B", "+").replace("+", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void respond(Socket s, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /** The Chromecast's IPv4 address on the home network, for the "open this on your phone" hint. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "<chromecast-ip>";
    }

    // Phone-sized control page. Nudges apply instantly; status polls once a second.
    private static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
            <meta name="theme-color" content="#0b0d10"><title>Live Odds overlay</title>
            <style>
              :root{--bg:#0b0d10;--card:#15181d;--line:#262a31;--text:#f2f4f7;--dim:#8b939e;--accent:#2e7cf6}
              *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
              body{margin:0;background:var(--bg);color:var(--text);font:16px/1.4 -apple-system,system-ui,sans-serif;
                min-height:100vh;display:flex;justify-content:center;padding:28px 16px}
              main{width:100%;max-width:420px}
              h1{font-size:15px;font-weight:600;color:var(--dim);letter-spacing:.02em;margin:0 0 18px}
              .card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:24px 20px}
              .big{font-size:84px;font-weight:700;letter-spacing:-.03em;line-height:1;font-variant-numeric:tabular-nums}
              .big small{font-size:32px;font-weight:600;color:var(--dim);margin-left:4px}
              .sub{color:var(--dim);margin:10px 0 22px;font-size:14px}
              .row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
              button{font:inherit;font-weight:600;font-size:18px;color:var(--text);background:#1f242b;border:1px solid var(--line);
                border-radius:14px;height:56px;touch-action:manipulation}
              button:active{background:var(--accent);border-color:var(--accent)}
              input[type=range]{width:100%;margin:22px 0 4px;accent-color:var(--accent)}
              .scale{display:flex;justify-content:space-between;color:var(--dim);font-size:12px}
              .status{margin-top:18px;font-size:14px;color:var(--dim)}
              .status b{color:var(--text);font-weight:600}
              .hint{margin-top:22px;color:var(--dim);font-size:13px}
              h2{font-size:15px;font-weight:600;color:var(--dim);letter-spacing:.02em;margin:26px 0 12px}
              .screen{aspect-ratio:16/9;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);
                gap:8px;padding:10px;border:2px solid var(--line);border-radius:12px;background:#0f1216}
              .cell{border-radius:8px;border:1px solid var(--line);background:#1a1e24;display:flex;align-items:center;
                justify-content:center;font-size:12px;color:var(--dim);touch-action:manipulation;cursor:pointer}
              .cell.on{background:var(--accent);border-color:var(--accent);color:#fff;font-weight:600}
              .cell.on::after{content:'Odds'}
              .switchrow{display:flex;align-items:center;justify-content:space-between;margin:0 0 18px}
              .switchrow h1{margin:0}
              .sw{position:relative;width:56px;height:32px;border-radius:16px;background:#2a2f37;border:1px solid var(--line);
                transition:background .2s;cursor:pointer;touch-action:manipulation;flex:none}
              .sw::after{content:'';position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:50%;background:#fff;
                transition:transform .2s}
              .sw.on{background:#2fbf71;border-color:#2fbf71}
              .sw.on::after{transform:translateX(24px)}
              body.off .card,body.off h2,body.off .hint{opacity:.45}
            </style></head><body><main>
              <div class="switchrow"><h1>Live Odds overlay <span id="st"></span></h1><div class="sw" id="sw" role="switch" aria-label="Overlay on/off"></div></div>
              <div class="card">
                <div class="big"><span id="d">–</span><small>s</small></div>
                <div class="sub">The chart shows the market this far behind live, to line up with the broadcast.</div>
                <div class="row">
                  <button data-by="-5">−5</button><button data-by="-1">−1</button>
                  <button data-by="1">+1</button><button data-by="5">+5</button>
                </div>
                <input id="r" type="range" min="0" max="60" step="1">
                <div class="scale"><span>0s</span><span>30s</span><span>60s</span></div>
                <div class="status" id="s"></div>
              </div>
              <div class="hint">Pausing or rewinding on the TV adds to this automatically and drops back when you return to live.</div>
              <h2>Position</h2>
              <div class="card">
                <div class="screen" id="g"></div>
                <div class="sub" style="margin:14px 0 0">Move the overlay out of the way of the broadcast's scorebug.</div>
              </div>
              <h2>Backdrop <span id="bv" style="float:right;color:var(--text)"></span></h2>
              <div class="card">
                <input id="b" type="range" min="0" max="150" step="5" style="margin:4px 0 4px">
                <div class="scale" style="position:relative"><span>None</span><span style="position:absolute;left:66.7%;transform:translateX(-50%)">Default</span><span>Darker</span></div>
                <div class="sub" style="margin:14px 0 0">How dark the shading behind the chart is. Raise it for bright fields and ice.</div>
              </div>
            </main><script>
              const d=document.getElementById('d'),r=document.getElementById('r'),s=document.getElementById('s'),g=document.getElementById('g');
              const sw=document.getElementById('sw'),st=document.getElementById('st');
              sw.onclick=()=>call('/set?on='+(sw.classList.contains('on')?0:1));
              const b=document.getElementById('b'),bv=document.getElementById('bv');let bDrag=false;
              b.oninput=()=>{bDrag=true;bv.textContent=b.value+'%'};
              b.onchange=()=>{bDrag=false;call('/set?bg='+b.value)};
              const cells=[...Array(9)].map((_,i)=>{const c=document.createElement('div');c.className='cell';
                c.onclick=()=>call('/set?pos='+i);g.appendChild(c);return c;});
              let dragging=false;
              function show(j){d.textContent=j.delay;if(!dragging)r.value=j.delay;
                cells.forEach((c,i)=>c.classList.toggle('on',i===j.pos));
                if(!bDrag){b.value=j.bg;bv.textContent=j.bg+'%';}
                sw.classList.toggle('on',j.on);sw.setAttribute('aria-checked',j.on);
                st.textContent=j.on?'· on':'· off';document.body.classList.toggle('off',!j.on);
                s.innerHTML=(j.game?'Watching <b>'+j.game+'</b>':'No game matched right now')+
                  (j.dvr>0?' · paused/rewound <b>+'+j.dvr+'s</b> (total '+(j.delay+j.dvr)+'s)':'');}
              async function call(u){try{show(await (await fetch(u)).json())}catch(e){s.textContent='Overlay not reachable'}}
              document.querySelectorAll('[data-by]').forEach(b=>b.onclick=()=>call('/nudge?by='+b.dataset.by));
              r.oninput=()=>{dragging=true;d.textContent=r.value};
              r.onchange=()=>{dragging=false;call('/set?delay='+r.value)};
              call('/api');setInterval(()=>{if(!dragging&&!bDrag)call('/api')},1000);
            </script></body></html>
            """;
}

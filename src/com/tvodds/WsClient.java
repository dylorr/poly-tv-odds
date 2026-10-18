package com.tvodds;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RFC 6455 client over TLS: text frames, fragmentation, ping/pong, close. No dependencies. */
final class WsClient {
    interface Listener {
        void onOpen(WsClient ws) throws IOException;
        void onText(String text);
    }

    private static final SecureRandom RNG = new SecureRandom();
    private final URI uri;
    private final Listener listener;
    private volatile SSLSocket socket;
    private OutputStream out;

    WsClient(String url, Listener listener) {
        this.uri = URI.create(url);
        this.listener = listener;
    }

    /** Connects and reads until the connection closes or {@link #close()} is called. Blocks. */
    void run() throws IOException {
        String host = uri.getHost();
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, uri.getPort() > 0 ? uri.getPort() : 443);
        socket = s;
        s.setSoTimeout(90_000);
        s.startHandshake();
        out = s.getOutputStream();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

        byte[] nonce = new byte[16];
        RNG.nextBytes(nonce);
        String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\nUser-Agent: tvodds/1.0\r\n\r\n";
        synchronized (this) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        String status = readLine(in);
        if (status == null || !status.contains(" 101")) throw new IOException("handshake: " + status);
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) { /* skip headers */ }
        if (h == null) throw new IOException("handshake: EOF");

        listener.onOpen(this);
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            int op = b0 & 0x0F;
            boolean fin = (b0 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len < 0 || len > 16_000_000) throw new IOException("frame too large: " + len);
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
            byte[] p = new byte[(int) len];
            in.readFully(p);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            switch (op) {
                case 0x0: case 0x1: case 0x2:
                    msg.write(p, 0, p.length);
                    if (fin) {
                        listener.onText(msg.toString("UTF-8"));
                        msg.reset();
                    }
                    break;
                case 0x8:
                    try { send(0x8, p); } catch (IOException ignored) { }
                    close();
                    return;
                case 0x9:
                    send(0xA, p);
                    break;
                default:
                    break;
            }
        }
    }

    void sendText(String text) throws IOException {
        send(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void send(int op, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | op);
        if (payload.length < 126) {
            f.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            f.write(0x80 | 126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >>> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    void close() {
        SSLSocket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return c == -1 && b.size() == 0 ? null : b.toString("UTF-8");
    }
}

package com.samsungrevive;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny HTTP/1.1 server: one request per connection, routed through a single Handler. */
final class MiniHttp {
    static final String TAG = "SamsungRevive";

    interface Handler {
        Response handle(Request req) throws Exception;
    }

    /** Takes over the raw connection (proxy forwarding, CONNECT tunnels). */
    interface Raw {
        boolean wants(Request req);

        void handle(Request req, InputStream in, Socket s) throws IOException;
    }

    static final class Request {
        String method;
        String host = "";
        int port = 80;
        String path;
        /** Request target as sent, the origin-form part of it, and the HTTP version. */
        String target, originTarget, version = "HTTP/1.1";
        /** True when the client spoke to us as an HTTP proxy (absolute-form target). */
        boolean absolute;
        /** Header lines in their original order and spelling. */
        final List<String> headerLines = new ArrayList<String>();
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];

        String q(String key) {
            String v = query.get(key);
            return v == null ? "" : v;
        }
    }

    static final class Response {
        final int status;
        final String type;
        final byte[] body;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        static Response text(int status, String type, String body) {
            try {
                return new Response(status, type, body.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        static Response notFound() {
            return text(404, "text/plain", "not found");
        }
    }

    private final ServerSocket server;
    private final Handler handler;
    private final ExecutorService pool = Executors.newCachedThreadPool(); // tunnels hold threads
    private volatile Raw raw;
    private volatile boolean running = true;

    MiniHttp(ServerSocket server, Handler handler) {
        this.server = server;
        this.handler = handler;
    }

    void setRaw(Raw raw) {
        this.raw = raw;
    }

    void start(String name) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() {
                    public void run() {
                        serve(s);
                    }
                });
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void serve(Socket s) {
        Request req = null;
        try {
            s.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            req = parse(in);
            if (req == null) return;
            Raw r = raw;
            if (r != null && r.wants(req)) {
                r.handle(req, in, s);
                return;
            }
            Response res;
            try {
                res = handler.handle(req);
            } catch (Exception e) {
                Log.w(TAG, "handler failed for " + req.host + req.path, e);
                res = Response.text(502, "text/plain", "upstream error: " + e);
            }
            if (res == null) res = Response.notFound();
            Log.i(TAG, req.method + " " + req.host + req.path + " -> " + res.status + " (" + res.body.length + "b)");
            RequestLog.add(req.host + req.path + " -> " + res.status);
            write(s.getOutputStream(), res, "HEAD".equals(req.method));
        } catch (IOException e) {
            Log.d(TAG, "connection error " + (req == null ? "" : req.host + req.path) + ": " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Request parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.length() == 0) return null;
        int sp1 = line.indexOf(' ');
        if (sp1 <= 0) return null;
        Request r = new Request();
        r.method = line.substring(0, sp1);
        String target = line.substring(sp1 + 1).trim();
        // Yahoo's YQL clients sometimes send raw spaces in the query; keep them in the target.
        int sp2 = target.lastIndexOf(' ');
        if (sp2 > 0 && target.substring(sp2 + 1).startsWith("HTTP/")) {
            r.version = target.substring(sp2 + 1);
            target = target.substring(0, sp2).trim();
        }
        target = target.replace(" ", "%20");
        r.target = target;
        // Absolute-form targets arrive when a client talks to us as an HTTP proxy.
        if (target.startsWith("http://") || target.startsWith("https://")) {
            r.absolute = true;
            int slash = target.indexOf('/', target.indexOf("//") + 2);
            r.host = slash < 0 ? target.substring(target.indexOf("//") + 2) : target.substring(target.indexOf("//") + 2, slash);
            target = slash < 0 ? "/" : target.substring(slash);
        }
        String h;
        while ((h = readLine(in)) != null && h.length() > 0) {
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
            r.headerLines.add(h);
        }
        // POST bodies (the Stocks app's XML queries) always carry a Content-Length.
        String len = r.headers.get("content-length");
        if (len != null) {
            int n = Integer.parseInt(len.trim());
            if (n < 0 || n > 1 << 20) throw new IOException("bad content-length " + len);
            r.body = new byte[n];
            for (int off = 0, k; off < n; off += k) {
                k = in.read(r.body, off, n - off);
                if (k < 0) throw new IOException("short body");
            }
        }
        if (r.host.length() == 0 && r.headers.containsKey("host")) r.host = r.headers.get("host");
        r.originTarget = target;
        int colon = r.host.indexOf(':');
        if (colon >= 0) {
            try {
                r.port = Integer.parseInt(r.host.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
            r.host = r.host.substring(0, colon);
        }
        r.host = r.host.toLowerCase(Locale.US);
        int qm = target.indexOf('?');
        r.path = qm < 0 ? target : target.substring(0, qm);
        if (qm >= 0) {
            for (String kv : target.substring(qm + 1).split("&")) {
                if (kv.length() == 0) continue;
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                String v = eq < 0 ? "" : kv.substring(eq + 1);
                try {
                    r.query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
                } catch (IllegalArgumentException e) {
                    r.query.put(k, v);
                }
            }
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void write(OutputStream out, Response res, boolean head) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        sb.append("Content-Type: ").append(res.type).append("\r\n");
        sb.append("Content-Length: ").append(res.body.length).append("\r\n");
        sb.append("Cache-Control: no-cache\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        if (!head) out.write(res.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }
}

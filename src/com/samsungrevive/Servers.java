package com.samsungrevive;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/** The local AccuWeather stand-in on 127.0.0.1 (HTTP + HTTPS), shared by all redirect modes. */
final class Servers {
    static volatile String status = "stopped";

    /** Hostname suffixes we answer for. */
    static final String[] HOSTS = {"accu-weather.com", NewsHandler.HOST, NewsHandler.DEV_HOST, YahooHandler.YQL_HOST, YahooHandler.FIN_HOST};

    private static MiniHttp http, https;

    private Servers() {}

    static boolean matches(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        for (String h : HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        return false;
    }

    static synchronized void start(Context ctx) throws Exception {
        if (http != null) return;
        Context app = ctx.getApplicationContext();
        if (ClockFix.wrong()) {
            ClockFix.start(app); // wolfSSL is initialised once the clock is right
        } else {
            try {
                com.htcrevive.tls.WolfClient.init(app); // TLS 1.2 for upstream HTTPS (news feeds)
            } catch (Throwable t) {
                Log.w(MiniHttp.TAG, "wolfSSL unavailable, using the platform TLS", t);
            }
        }
        final WeatherHandler weather = new WeatherHandler(app);
        final NewsHandler news = new NewsHandler(app);
        final YahooHandler yahoo = new YahooHandler(app, news);
        MiniHttp.Handler router = new MiniHttp.Handler() {
            public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
                if (req.host.equals(NewsHandler.HOST) || req.host.equals(NewsHandler.DEV_HOST)) return news.handle(req);
                if (YahooHandler.handles(req.host)) return yahoo.handle(req);
                if (matches(req.host)) return weather.handle(req);
                return MiniHttp.Response.notFound();
            }
        };
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        ServerSocket plain = new ServerSocket();
        plain.setReuseAddress(true);
        plain.bind(new InetSocketAddress(lo, Prefs.HTTP_PORT), 50);
        MiniHttp h = new MiniHttp(plain, router);
        h.setRaw(new Proxy()); // only used in Wi-Fi proxy mode; harmless otherwise
        try {
            // Android 2.2's OpenSSLServerSocketImpl deadlocks close() against a blocked accept(),
            // and nothing on the Galaxy S uses HTTPS, so do not open the port there.
            if (WeatherHandler.LEGACY) throw new java.io.IOException("not used on Android 2.2");
            // Self-signed cert for *.accu-weather.com; the widget's GPS client trusts any certificate.
            // Android 4.x only reads PKCS#12 with the legacy SHA-1/3DES algorithms.
            KeyStore ks = KeyStore.getInstance("PKCS12");
            InputStream p12 = app.getAssets().open("server.p12");
            ks.load(p12, "samsungrevive".toCharArray());
            p12.close();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "samsungrevive".toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(kmf.getKeyManagers(), null, null);
            SSLServerSocket secure = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(Prefs.HTTPS_PORT, 50, lo);
            secure.setEnabledProtocols(secure.getSupportedProtocols());
            MiniHttp s = new MiniHttp(secure, router);
            s.start("revive-https");
            https = s;
        } catch (Exception e) {
            // Only current-location lookups on newer TouchWiz use HTTPS; keep plain HTTP running
            // even if this Android cannot load the key store (e.g. 2.3 on the Galaxy S2).
            Log.w(MiniHttp.TAG, "HTTPS server not available", e);
            RequestLog.add("HTTPS server unavailable: " + e.getMessage());
        }
        h.start("revive-http");
        http = h;
        Log.i(MiniHttp.TAG, "servers on 127.0.0.1:" + Prefs.HTTP_PORT + "/" + Prefs.HTTPS_PORT);
    }

    static synchronized void stop() {
        final MiniHttp a = http, b = https;
        http = https = null;
        // Closing a server socket can block (see above); never do it on the main thread.
        Thread t = new Thread(new Runnable() {
            public void run() {
                if (a != null) a.stop();
                if (b != null) b.stop();
            }
        }, "revive-stop");
        t.setDaemon(true);
        t.start();
    }
}

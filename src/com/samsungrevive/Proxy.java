package com.samsungrevive;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Wi-Fi proxy mode: with 127.0.0.1:28081 set as the Wi-Fi proxy, every HTTP request and HTTPS
 * CONNECT arrives here. AccuWeather traffic is answered locally (CONNECT goes to our TLS server);
 * everything else is passed through untouched.
 */
final class Proxy implements MiniHttp.Raw {
    public boolean wants(MiniHttp.Request r) {
        return "CONNECT".equals(r.method) || (r.absolute && !Servers.matches(r.host));
    }

    public void handle(MiniHttp.Request r, InputStream in, Socket client) throws IOException {
        Socket up = new Socket();
        try {
            OutputStream co = client.getOutputStream();
            if ("CONNECT".equals(r.method)) {
                String hp = r.target;
                int c = hp.lastIndexOf(':');
                String host = c < 0 ? hp : hp.substring(0, c);
                int port = 443;
                try {
                    if (c >= 0) port = Integer.parseInt(hp.substring(c + 1));
                } catch (NumberFormatException ignored) {
                }
                if (Servers.matches(host)) {
                    up.connect(new InetSocketAddress("127.0.0.1", port == 80 ? Prefs.HTTP_PORT : Prefs.HTTPS_PORT), 5000);
                    RequestLog.add("CONNECT " + host + ":" + port + " -> local");
                } else {
                    up.connect(new InetSocketAddress(host, port), 10000);
                }
                co.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("ISO-8859-1"));
                co.flush();
            } else {
                up.connect(new InetSocketAddress(r.host, r.port), 10000);
                StringBuilder sb = new StringBuilder();
                sb.append(r.method).append(' ').append(r.originTarget).append(' ').append(r.version).append("\r\n");
                for (String h : r.headerLines) {
                    String l = h.toLowerCase(Locale.US);
                    if (l.startsWith("proxy-connection:") || l.startsWith("connection:") || l.startsWith("keep-alive:")) continue;
                    sb.append(h).append("\r\n");
                }
                sb.append("Connection: close\r\n\r\n");
                OutputStream uo = up.getOutputStream();
                uo.write(sb.toString().getBytes("ISO-8859-1"));
                uo.write(r.body);
                uo.flush();
            }
            client.setSoTimeout(0);
            up.setSoTimeout(0);
            final InputStream cin = in;
            final Socket upf = up;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        copy(cin, upf.getOutputStream());
                    } catch (IOException ignored) {
                    }
                    try {
                        upf.shutdownOutput();
                    } catch (IOException ignored) {
                    }
                }
            }, "proxy-up");
            t.setDaemon(true);
            t.start();
            copy(up.getInputStream(), co);
        } catch (IOException e) {
            Log.d(MiniHttp.TAG, "proxy " + r.method + " " + r.target + ": " + e);
        } finally {
            try {
                up.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }
}

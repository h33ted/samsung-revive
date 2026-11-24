package com.samsungrevive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Upstream fetches. Plain HTTP uses the platform HttpURLConnection (Open-Meteo). HTTPS goes
 * through HTC Revive's bundled wolfSSL client when it loaded, since Android 2.3/4.0 stop at
 * TLS 1.0 and most news sites require TLS 1.2; otherwise the platform stack is tried.
 */
final class Net {
    private Net() {}

    static byte[] get(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            if (u.getProtocol().equals("https") && com.htcrevive.tls.WolfClient.isReady()) {
                String path = u.getFile();
                if (path == null || path.length() == 0) path = "/";
                com.htcrevive.tls.Https.Response r = com.htcrevive.tls.Https.get(u.getHost(),
                        u.getPort() == -1 ? 443 : u.getPort(), path, 15000);
                if (r.status >= 300 && r.status < 400 && r.location != null) {
                    url = new URL(u, r.location).toString();
                    continue;
                }
                if (r.status != 200) throw new IOException("HTTPS " + r.status + " from " + url);
                return r.body;
            }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.0.4) SamsungRevive/1.0");
            c.setRequestProperty("Accept-Encoding", "gzip");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(u, c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code + " from " + url);
                InputStream in = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects: " + url);
    }

    static String getString(String url) throws IOException {
        return new String(get(url), "UTF-8");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}

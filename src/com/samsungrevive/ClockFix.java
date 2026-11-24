package com.samsungrevive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Old phones often lose the time (a Galaxy S2 came up in January 2000, an S4 in 2013). Every
 * HTTPS certificate is then "not yet valid", and wolfSSL even refuses to load its roots. When the
 * clock is clearly wrong, read the time from the Date header of a plain-HTTP request and, in
 * root mode, set it with `date -s`. Without root we can only warn.
 */
final class ClockFix {
    /** Anything before this is certainly wrong. */
    private static final long SANE = 1704067200000L; // 2024-01-01T00:00:00Z

    static volatile String warning = "";

    private ClockFix() {}

    static boolean wrong() {
        return System.currentTimeMillis() < SANE;
    }

    /** Retries in the background until the network answers; then (re)initialises wolfSSL. */
    static void start(final Context ctx) {
        if (!wrong()) return;
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 30 && wrong(); i++) {
                    try {
                        fix(ctx);
                    } catch (Exception e) {
                        Log.w(MiniHttp.TAG, "clock fix attempt failed: " + e);
                    }
                    if (!wrong()) break;
                    try {
                        Thread.sleep(i < 3 ? 10000 : 60000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!wrong() && !com.htcrevive.tls.WolfClient.isReady()) {
                    try {
                        com.htcrevive.tls.WolfClient.init(ctx.getApplicationContext());
                    } catch (Throwable t) {
                        Log.w(MiniHttp.TAG, "wolfSSL init after clock fix failed", t);
                    }
                }
            }
        }, "clock-fix").start();
    }

    private static void fix(Context ctx) throws Exception {
        // A small GET on the endpoint the weather already uses (a HEAD on "/" hung on Android 2.3).
        HttpURLConnection c = (HttpURLConnection) new URL(
                "http://api.open-meteo.com/v1/forecast?latitude=0&longitude=0&current=is_day").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        long now;
        try {
            c.getResponseCode();
            now = c.getDate();
            Net.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
        if (now < SANE) throw new Exception("no usable Date header");
        String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date(System.currentTimeMillis()));
        if (Prefs.mode(ctx) != Prefs.MODE_ROOT || !Prefs.hasSu()) {
            warning = "The phone's clock says " + year + ". Set the date in Settings, or HTTPS (current "
                    + "location, news) will fail.";
            return;
        }
        // toolbox `date -s` takes local time as YYYYMMDD.hhmmss.
        String stamp = new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US).format(new Date(now));
        File f = new File(ctx.getFilesDir(), "clock.sh");
        FileOutputStream out = new FileOutputStream(f);
        out.write(("date -s " + stamp + "\n").getBytes("UTF-8"));
        out.close();
        Process p = new ProcessBuilder("su", "-c", "sh " + f.getAbsolutePath()).redirectErrorStream(true).start();
        String o = new String(Net.readAll(p.getInputStream()), "UTF-8").trim();
        p.waitFor();
        Log.i(MiniHttp.TAG, "clock was " + year + ", set to " + stamp + ": " + o);
        RequestLog.add("clock corrected from " + year + " to " + stamp);
        warning = wrong() ? "Could not set the clock (" + o + ")" : "";
    }
}

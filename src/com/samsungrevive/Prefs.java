package com.samsungrevive;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    /** Loopback port of the local AccuWeather stand-in; the VPN bridges port 80 of the fake IP to it. */
    static final int HTTP_PORT = 28081, HTTPS_PORT = 28444;

    static final int MODE_ROOT = 0, MODE_VPN = 1, MODE_PROXY = 2;

    private Prefs() {}

    static boolean hasSu() {
        return new java.io.File("/system/xbin/su").exists() || new java.io.File("/system/bin/su").exists();
    }

    /** VpnService, the certificate installer and per-network Wi-Fi proxies all arrived in Android 4.0. */
    static boolean modern() {
        return android.os.Build.VERSION.SDK_INT >= 14;
    }

    static int mode(Context c) {
        int m = sp(c).getInt("mode", hasSu() || !modern() ? MODE_ROOT : MODE_VPN);
        return modern() ? m : MODE_ROOT; // Android 2.3 (Galaxy S2): root mode only
    }

    static void setMode(Context c, int mode) {
        sp(c).edit().putInt("mode", mode).commit();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("revive", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).commit();
    }

    /** Display names for our OM_ location codes, since weather-data.asp only receives the code. */
    static String cityName(Context c, String code) {
        return sp(c).getString("name:" + code, "");
    }

    /** News categories for the AP News widget, one "Name|feed url" per line. */
    static String newsFeedsText(Context c) {
        return sp(c).getString("newsFeeds", NewsHandler.DEFAULT_FEEDS);
    }

    static void setNewsFeedsText(Context c, String text) {
        sp(c).edit().putString("newsFeeds", text).commit();
    }

    static java.util.List<String[]> newsFeeds(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        for (String line : newsFeedsText(c).split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String name = line.substring(0, bar).trim(), url = line.substring(bar + 1).trim();
            if (name.length() > 0 && url.startsWith("http")) out.add(new String[]{name, url});
        }
        return out;
    }

    /** Cached "lat,lon" for a geocoded AccuWeather cityId, or null. */
    static String geo(Context c, String id) {
        return sp(c).getString("geo:" + id, null);
    }

    static void setGeo(Context c, String id, String latLon) {
        sp(c).edit().putString("geo:" + id, latLon).commit(); // apply() is API 9
    }

    static void setCityName(Context c, String code, String name) {
        sp(c).edit().putString("name:" + code, name).commit(); // apply() is API 9
    }
}

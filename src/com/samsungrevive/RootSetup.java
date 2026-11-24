package com.samsungrevive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Root mode: a hosts entry sends the AccuWeather host to 127.0.0.1, and iptables redirects
 * loopback 80/443 to our ports. The stock ICS ROM has no grep or printf, so the hosts file is
 * rewritten from Java and the script only uses toolbox commands.
 */
final class RootSetup {
    static final String[] HOSTS = {"samsungmobile.accu-weather.com", NewsHandler.HOST, NewsHandler.DEV_HOST,
            YahooHandler.YQL_HOST, YahooHandler.FIN_HOST};
    private static final String MARK = "# samsungrevive";
    private static final String HOSTS_FILE = "/system/etc/hosts";
    private static final String USER_CA_DIR = "/data/misc/keychain/cacerts-added";
    /**
     * Remount /system. Stock toolbox mount (Android 2.x) only remounts when given the type and block
     * device, and the 2.2 shell has no "[" either, so the full command is built from /proc/mounts here.
     */
    private static String remount(String mode) {
        try {
            for (String line : read(new File("/proc/mounts")).split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 3 && f[1].equals("/system")) {
                    return "mount -o remount," + mode + " -t " + f[2] + " " + f[0] + " /system\n";
                }
            }
        } catch (IOException ignored) {
        }
        return "mount -o remount," + mode + " /system\n";
    }

    private RootSetup() {}

    static String apply(Context ctx) throws IOException, InterruptedException {
        File dir = ctx.getFilesDir();
        File orig = new File(dir, "hosts.orig");
        String cur = read(new File(HOSTS_FILE));
        String base;
        if (cur.contains(MARK)) {
            base = orig.exists() ? read(orig) : strip(cur);
        } else {
            base = cur;
            write(orig, cur);
        }
        StringBuilder sb = new StringBuilder(base);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        sb.append(MARK).append('\n');
        for (String h : HOSTS) sb.append("127.0.0.1 ").append(h).append('\n');
        sb.append(MARK).append(" end\n");
        File next = new File(dir, "hosts.new");
        write(next, sb.toString());
        File ca = new File(dir, "ca.der");
        FileOutputStream caOut = new FileOutputStream(ca);
        caOut.write(Cert.der(ctx));
        caOut.close();
        if (android.os.Build.VERSION.SDK_INT >= 9) ca.setReadable(true, false); // root reads it anyway
        String caFile = USER_CA_DIR + "/" + Cert.hash(ctx) + ".0";

        String script = remount("rw")
                + "cat " + next.getAbsolutePath() + " > " + HOSTS_FILE + "\n"
                + "chmod 644 " + HOSTS_FILE + "\n"
                + remount("ro")
                // Our CA in the user trust store, for apps that verify HTTPS (the S4's 2013 app).
                // Android 2.3 has no user store, and its widget only uses plain HTTP.
                + (!Prefs.modern() ? "" :
                  "mkdir " + USER_CA_DIR + " 2>/dev/null\n"
                + "chown system.system " + USER_CA_DIR + "; chmod 755 " + USER_CA_DIR + "\n"
                + "cat " + ca.getAbsolutePath() + " > " + caFile + "\n"
                + "chown system.system " + caFile + "; chmod 644 " + caFile + "\n")
                + clearRules()
                + rule("-I", 80, Prefs.HTTP_PORT) + " || echo IPTFAIL\n"
                + rule("-I", 443, Prefs.HTTPS_PORT) + " || echo IPTFAIL\n"
                + "ndc resolver flushdefaultif >/dev/null 2>&1\n"
                + "echo applied\n";
        String out = su(ctx, "apply.sh", script);
        if (!read(new File(HOSTS_FILE)).contains(MARK)) throw new IOException("hosts not updated: " + out);
        if (out.contains("IPTFAIL")) throw new IOException("iptables failed: " + out);
        return out;
    }

    static String remove(Context ctx) throws IOException, InterruptedException {
        File orig = new File(ctx.getFilesDir(), "hosts.orig");
        String cur = read(new File(HOSTS_FILE));
        StringBuilder script = new StringBuilder();
        if (cur.contains(MARK)) {
            File next = new File(ctx.getFilesDir(), "hosts.new");
            write(next, orig.exists() ? read(orig) : strip(cur));
            script.append(remount("rw"))
                    .append("cat ").append(next.getAbsolutePath()).append(" > ").append(HOSTS_FILE).append('\n')
                    .append("chmod 644 ").append(HOSTS_FILE).append('\n')
                    .append(remount("ro"));
        }
        script.append("rm -f ").append(USER_CA_DIR).append('/').append(Cert.hash(ctx)).append(".0\n");
        script.append(clearRules()).append("ndc resolver flushdefaultif >/dev/null 2>&1\necho removed\n");
        return su(ctx, "remove.sh", script.toString());
    }

    private static String clearRules() {
        return "while " + rule("-D", 80, Prefs.HTTP_PORT) + " 2>/dev/null; do :; done\n"
                + "while " + rule("-D", 443, Prefs.HTTPS_PORT) + " 2>/dev/null; do :; done\n";
    }

    private static String rule(String op, int from, int to) {
        String pos = op.equals("-I") ? " 1" : "";
        return "iptables -t nat " + op + " OUTPUT" + pos + " -d 127.0.0.1 -p tcp --dport " + from
                + " -j REDIRECT --to-ports " + to;
    }

    /** Drops our block from a hosts file. */
    private static String strip(String s) {
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : s.split("\n", -1)) {
            if (line.startsWith(MARK)) {
                in = !line.equals(MARK + " end") && !in ? true : false;
                continue;
            }
            if (!in) out.append(line).append('\n');
        }
        String r = out.toString();
        while (r.endsWith("\n\n")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String su(Context ctx, String name, String script) throws IOException, InterruptedException {
        File f = new File(ctx.getFilesDir(), name);
        write(f, script);
        Process p = new ProcessBuilder("su", "-c", "sh " + f.getAbsolutePath()).redirectErrorStream(true).start();
        String output = new String(Net.readAll(p.getInputStream()), "UTF-8").trim();
        int rc = p.waitFor();
        Log.i(MiniHttp.TAG, name + " rc=" + rc + ": " + output);
        if (rc != 0 && !output.contains("applied") && !output.contains("removed")) {
            throw new IOException(name + " failed (" + rc + "): " + output);
        }
        return output;
    }

    private static String read(File f) throws IOException {
        return new String(Net.readAll(new FileInputStream(f)), "UTF-8");
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
        if (android.os.Build.VERSION.SDK_INT >= 9) f.setReadable(true, false);
    }
}

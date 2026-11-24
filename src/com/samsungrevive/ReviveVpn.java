package com.samsungrevive;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * No-root redirect. The VPN routes only two addresses: a DNS stub (10.211.0.53), which Android
 * makes the system resolver while the VPN is up, and a fake server (10.211.0.80). The stub answers
 * AccuWeather names with the fake IP and relays every other query upstream through a protected
 * socket. TCP to the fake IP's ports 80 and 443 is terminated here and bridged to the local
 * MiniHttp servers (search uses HTTP; the GPS lookup uses HTTPS with a trust-all client). All other traffic never enters the tunnel.
 */
public class ReviveVpn extends VpnService implements Runnable {
    static final String ACTION_STOP = "com.samsungrevive.STOP";

    private static final String[] UPSTREAM_DNS = {"8.8.8.8", "1.1.1.1"};

    private static final byte[] TUN_IP = {10, (byte) 211, 0, 1};
    private static final byte[] DNS_IP = {10, (byte) 211, 0, 53};
    private static final byte[] FAKE_IP = {10, (byte) 211, 0, 80};
    private static final long M = 0xffffffffL;
    private static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
    private static final int SEGMENT = 1360;

    private ParcelFileDescriptor tun;
    private FileOutputStream out;
    private final Object outLock = new Object();
    private Thread loop;
    private ExecutorService pool;
    private final Map<Integer, Tcp> sessions = new ConcurrentHashMap<Integer, Tcp>();
    private final Random random = new Random();
    private int ipId;

    private static final class Tcp {
        final int port, ourPort;
        final byte[] client;
        long ours, theirs;
        Socket backend;
        boolean finSent, finRecv;

        Tcp(int port, int ourPort, byte[] client) {
            this.port = port;
            this.ourPort = ourPort;
            this.client = client;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (loop != null) return START_STICKY;
        try {
            pool = Executors.newCachedThreadPool();
            Servers.start(this);
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
            tun = new Builder()
                    .setSession("Samsung Revive")
                    .setConfigureIntent(open)
                    .setMtu(1500)
                    .addAddress("10.211.0.1", 32)
                    .addRoute("10.211.0.53", 32)
                    .addRoute("10.211.0.80", 32)
                    .addDnsServer("10.211.0.53")
                    .establish();
            if (tun == null) {
                Servers.status = "VPN not approved, open Samsung Revive and enable it";
                shutdown();
                stopSelf();
                return START_NOT_STICKY;
            }
            out = new FileOutputStream(tun.getFileDescriptor());
            loop = new Thread(this, "revive-tun");
            loop.start();
            Servers.status = "active (VPN)";
        } catch (Exception e) {
            Log.e(MiniHttp.TAG, "start failed", e);
            shutdown();
            Servers.status = "start failed: " + e;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        Prefs.setEnabled(this, false);
        shutdown();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private synchronized void shutdown() {
        if (loop != null) loop.interrupt();
        loop = null;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
        }
        tun = null;
        Servers.stop();
        for (Tcp s : sessions.values()) close(s);
        if (pool != null) pool.shutdownNow();
        pool = null;
        Servers.status = "stopped";
    }

    static boolean matches(String host) {
        return Servers.matches(host);
    }

    public void run() {
        ParcelFileDescriptor fd = tun;
        if (fd == null) return;
        FileInputStream in = new FileInputStream(fd.getFileDescriptor());
        byte[] buf = new byte[32767];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) {
                    Thread.sleep(10); // the tun fd may be non-blocking on 4.0
                    continue;
                }
                try {
                    packet(buf, n);
                } catch (RuntimeException e) {
                    Log.w(MiniHttp.TAG, "bad packet", e);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (tun != null) Log.w(MiniHttp.TAG, "tun read failed", e);
        }
    }

    private void packet(byte[] b, int n) {
        if (n < 20 || ((b[0] >> 4) & 0x0f) != 4) return;
        int ihl = (b[0] & 0x0f) * 4;
        int total = Math.min(u16(b, 2), n);
        int proto = b[9] & 0xff;
        byte[] src = {b[12], b[13], b[14], b[15]};
        if (proto == 17 && same(b, 16, DNS_IP) && total >= ihl + 8) {
            if (u16(b, ihl + 2) != 53) return;
            int len = Math.min(u16(b, ihl + 4), total - ihl) - 8;
            if (len < 12) return;
            byte[] q = new byte[len];
            System.arraycopy(b, ihl + 8, q, 0, len);
            dns(src, u16(b, ihl), q);
        } else if (proto == 6 && same(b, 16, FAKE_IP) && total >= ihl + 20) {
            tcp(b, ihl, total, src);
        }
    }

    // ---- DNS -------------------------------------------------------------------------------

    private void dns(final byte[] client, final int port, final byte[] q) {
        StringBuilder name = new StringBuilder();
        int p = 12;
        while (true) {
            if (p >= q.length) return;
            int l = q[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xc0) != 0 || p + 1 + l > q.length) return;
            if (name.length() > 0) name.append('.');
            name.append(new String(q, p + 1, l));
            p += l + 1;
        }
        if (p + 4 > q.length) return;
        int qtype = u16(q, p);
        int qend = p + 4;

        if (matches(name.toString())) {
            boolean a = qtype == 1;
            byte[] r = new byte[qend + (a ? 16 : 0)];
            System.arraycopy(q, 0, r, 0, qend);
            r[2] = (byte) (0x80 | (q[2] & 0x01)); // response, keep RD
            r[3] = (byte) 0x80;                    // RA, NOERROR
            r[4] = 0; r[5] = 1;
            r[6] = 0; r[7] = (byte) (a ? 1 : 0);
            r[8] = r[9] = r[10] = r[11] = 0;
            if (a) {
                int o = qend;
                r[o] = (byte) 0xc0; r[o + 1] = 12;    // name: pointer to the question
                r[o + 2] = 0; r[o + 3] = 1;           // type A
                r[o + 4] = 0; r[o + 5] = 1;           // class IN
                r[o + 6] = 0; r[o + 7] = 0; r[o + 8] = 0; r[o + 9] = 60; // TTL 60s
                r[o + 10] = 0; r[o + 11] = 4;
                System.arraycopy(FAKE_IP, 0, r, o + 12, 4);
            }
            udp(client, port, r, r.length);
            return;
        }

        ExecutorService ex = pool;
        if (ex == null) return;
        ex.execute(new Runnable() {
            public void run() {
                forward(client, port, q);
            }
        });
    }

    private void forward(byte[] client, int port, byte[] q) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            if (!protect(s)) throw new IOException("protect failed");
            s.setSoTimeout(4000);
            byte[] rb = new byte[4096];
            for (String up : UPSTREAM_DNS) {
                try {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName(up), 53));
                    DatagramPacket rp = new DatagramPacket(rb, rb.length);
                    s.receive(rp);
                    udp(client, port, rb, rp.getLength());
                    return;
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(MiniHttp.TAG, "dns forward failed", e);
        } finally {
            if (s != null) s.close();
        }
    }

    private void udp(byte[] client, int port, byte[] payload, int len) {
        int total = 28 + len;
        byte[] p = new byte[total];
        ipHeader(p, total, 17, DNS_IP, client);
        put16(p, 20, 53);
        put16(p, 22, port);
        put16(p, 24, 8 + len);
        put16(p, 26, 0); // UDP checksum is optional over IPv4
        System.arraycopy(payload, 0, p, 28, len);
        write(p);
    }

    // ---- TCP -------------------------------------------------------------------------------

    private void tcp(byte[] b, int ihl, int total, byte[] client) {
        int sport = u16(b, ihl), dport = u16(b, ihl + 2);
        long seq = u32(b, ihl + 4), ack = u32(b, ihl + 8);
        int doff = ((b[ihl + 12] >> 4) & 0x0f) * 4;
        int flags = b[ihl + 13] & 0x3f;
        int start = ihl + doff, len = total - start;
        if (len < 0) return;
        Tcp s = sessions.get(sport);

        if ((flags & RST) != 0) {
            if (s != null) close(s);
            return;
        }
        if ((flags & SYN) != 0 && (flags & ACK) == 0) {
            if (s != null) close(s);
            if (dport != 80 && dport != 443) {
                sendTcp(client, sport, dport, 0, (seq + 1) & M, RST | ACK, null, 0, 0);
                return;
            }
            open(client, sport, dport, seq);
            return;
        }
        if (s == null) return;

        synchronized (s) {
            if (len > 0) {
                if (seq == s.theirs) {
                    try {
                        s.backend.getOutputStream().write(b, start, len);
                    } catch (IOException e) {
                        sendTcp(client, sport, s.ourPort, s.ours, s.theirs, RST | ACK, null, 0, 0);
                        close(s);
                        return;
                    }
                    s.theirs = (s.theirs + len) & M;
                }
                send(s, ACK, null, 0, 0); // also re-acks retransmissions
            }
            if ((flags & FIN) != 0 && !s.finRecv && ((seq + len) & M) == s.theirs) {
                s.theirs = (s.theirs + 1) & M;
                s.finRecv = true;
                send(s, ACK, null, 0, 0);
                try {
                    s.backend.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
            if (s.finSent && s.finRecv) close(s);
        }
    }

    private void open(byte[] client, int sport, int dport, long seq) {
        final Tcp s = new Tcp(sport, dport, client);
        s.theirs = (seq + 1) & M;
        s.ours = random.nextInt() & M;
        try {
            s.backend = new Socket();
            s.backend.connect(new InetSocketAddress("127.0.0.1", dport == 443 ? Prefs.HTTPS_PORT : Prefs.HTTP_PORT), 2000);
        } catch (IOException e) {
            Log.w(MiniHttp.TAG, "local server unreachable", e);
            sendTcp(client, sport, dport, 0, s.theirs, RST | ACK, null, 0, 0);
            return;
        }
        sessions.put(sport, s);
        synchronized (s) {
            send(s, SYN | ACK, null, 0, 0);
            s.ours = (s.ours + 1) & M;
        }
        ExecutorService ex = pool;
        if (ex == null) {
            close(s);
            return;
        }
        ex.execute(new Runnable() {
            public void run() {
                pump(s);
            }
        });
    }

    /** Copies the local server's response back to the client, then closes our side. */
    private void pump(Tcp s) {
        byte[] buf = new byte[SEGMENT];
        try {
            InputStream in = s.backend.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (s) {
                    send(s, PSH | ACK, buf, 0, n);
                    s.ours = (s.ours + n) & M;
                }
            }
        } catch (IOException ignored) {
        }
        synchronized (s) {
            if (!s.finSent) {
                send(s, FIN | ACK, null, 0, 0);
                s.ours = (s.ours + 1) & M;
                s.finSent = true;
            }
            if (s.finRecv) close(s);
        }
    }

    private void close(Tcp s) {
        sessions.remove(s.port);
        if (s.backend != null) {
            try {
                s.backend.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void send(Tcp s, int flags, byte[] d, int off, int len) {
        sendTcp(s.client, s.port, s.ourPort, s.ours, s.theirs, flags, d, off, len);
    }

    private void sendTcp(byte[] client, int clientPort, int ourPort, long seq, long ack, int flags,
                         byte[] d, int off, int len) {
        int total = 40 + len;
        byte[] p = new byte[total];
        ipHeader(p, total, 6, FAKE_IP, client);
        put16(p, 20, ourPort);
        put16(p, 22, clientPort);
        put32(p, 24, seq);
        put32(p, 28, ack);
        p[32] = 0x50; // 20-byte header, no options
        p[33] = (byte) flags;
        put16(p, 34, 65535);
        if (len > 0) System.arraycopy(d, off, p, 40, len);
        long pseudo = words(FAKE_IP) + words(client) + 6 + (20 + len);
        put16(p, 36, checksum(p, 20, 20 + len, pseudo));
        write(p);
    }

    // ---- IP helpers ------------------------------------------------------------------------

    private void ipHeader(byte[] p, int total, int proto, byte[] src, byte[] dst) {
        p[0] = 0x45;
        put16(p, 2, total);
        synchronized (outLock) {
            put16(p, 4, ipId++ & 0xffff);
        }
        put16(p, 6, 0x4000); // don't fragment
        p[8] = 64;
        p[9] = (byte) proto;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, checksum(p, 0, 20, 0));
    }

    private void write(byte[] p) {
        synchronized (outLock) {
            FileOutputStream o = out;
            if (o == null || tun == null) return;
            try {
                o.write(p);
            } catch (IOException e) {
                Log.w(MiniHttp.TAG, "tun write failed", e);
            }
        }
    }

    private static int checksum(byte[] b, int off, int len, long sum) {
        for (int i = 0; i + 1 < len; i += 2) sum += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        if ((len & 1) != 0) sum += (b[off + len - 1] & 0xff) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int) (~sum & 0xffff);
    }

    private static long words(byte[] ip) {
        return (((ip[0] & 0xff) << 8) | (ip[1] & 0xff)) + (((ip[2] & 0xff) << 8) | (ip[3] & 0xff));
    }

    private static boolean same(byte[] b, int off, byte[] ip) {
        return b[off] == ip[0] && b[off + 1] == ip[1] && b[off + 2] == ip[2] && b[off + 3] == ip[3];
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long u32(byte[] b, int off) {
        return ((long) u16(b, off) << 16) | u16(b, off + 2);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    private static void put32(byte[] b, int off, long v) {
        put16(b, off, (int) (v >> 16) & 0xffff);
        put16(b, off + 2, (int) v & 0xffff);
    }
}

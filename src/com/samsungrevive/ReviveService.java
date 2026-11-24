package com.samsungrevive;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Root and Wi-Fi proxy modes: hosts the local servers and, in root mode, keeps the redirects applied. */
public class ReviveService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Servers.start(ReviveService.this);
                    if (Prefs.mode(ReviveService.this) == Prefs.MODE_ROOT) {
                        Servers.status = "applying root redirect...";
                        RootSetup.apply(ReviveService.this);
                        Servers.status = "active (root: hosts + iptables)";
                    } else {
                        Servers.status = "active (Wi-Fi proxy 127.0.0.1:" + Prefs.HTTP_PORT + ")";
                    }
                } catch (Exception e) {
                    Log.e(MiniHttp.TAG, "start failed", e);
                    Servers.status = "failed: " + e.getMessage();
                }
            }
        }, "revive-start").start();
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        if (android.os.Build.VERSION.SDK_INT < 11) { // Android 2.3: no Notification.Builder
            Notification n = new Notification(android.R.drawable.ic_menu_compass, null, 0);
            n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            n.setLatestEventInfo(this, "Samsung Revive", "Serving AccuWeather data locally", open);
            return n;
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Samsung Revive")
                .setContentText("Serving AccuWeather data locally")
                .setContentIntent(open)
                .setOngoing(true)
                .getNotification();
    }

    @Override
    public void onDestroy() {
        Servers.stop();
        Servers.status = "stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

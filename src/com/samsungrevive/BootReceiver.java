package com.samsungrevive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** BOOT_COMPLETED and MY_PACKAGE_REPLACED: an update kills the running service, so start it again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        start(context);
    }

    static void start(Context context) {
        if (!Prefs.enabled(context)) return;
        if (Prefs.mode(context) == Prefs.MODE_VPN) {
            // prepare() returns null once the user has approved this app as a VPN.
            if (VpnService.prepare(context) == null) context.startService(new Intent(context, ReviveVpn.class));
        } else {
            context.startService(new Intent(context, ReviveService.class));
        }
    }
}

package com.samsungrevive;

import android.content.Context;
import android.content.Intent;
import android.security.KeyChain;

import java.security.KeyStore;

/**
 * The local CA that signs our *.accu-weather.com certificate. The 2013 TouchWiz AccuWeather app
 * (Galaxy S4) verifies HTTPS against the system trust store, so the CA has to be trusted:
 * root mode copies it into the user store, the other modes use Android's certificate installer.
 */
final class Cert {
    private Cert() {}

    static String hash(Context ctx) throws java.io.IOException {
        return new String(Net.readAll(ctx.getAssets().open("ca.hash")), "UTF-8").trim();
    }

    static byte[] der(Context ctx) throws java.io.IOException {
        return Net.readAll(ctx.getAssets().open("ca.der"));
    }

    static boolean installed(Context ctx) {
        if (!Prefs.modern()) return false; // no user CA store before Android 4.0
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            return ks.containsAlias("user:" + hash(ctx) + ".0");
        } catch (Exception e) {
            return false;
        }
    }

    static Intent installIntent(Context ctx) throws java.io.IOException {
        Intent i = KeyChain.createInstallIntent();
        i.putExtra(KeyChain.EXTRA_CERTIFICATE, der(ctx));
        i.putExtra(KeyChain.EXTRA_NAME, "Samsung Revive");
        return i;
    }
}

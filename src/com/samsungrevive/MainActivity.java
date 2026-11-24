package com.samsungrevive;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings-style screen built the same way as Samsung's own Settings app, so each phone draws it
 * in its own TouchWiz (TouchWiz 4 on the Galaxy S2, Nature UX on the S3/S4): the app theme is
 * the system's DeviceDefault, which Samsung skins, rather than compiling against twframework,
 * whose classes and resources differ between those three ROMs.
 */
public class MainActivity extends PreferenceActivity {
    private static final int REQ_VPN = 1, REQ_CERT = 2;
    private static final String PROXY = "127.0.0.1:" + Prefs.HTTP_PORT;

    private static final String WEATHER_PKG = "com.sec.android.widgetapp.ap.hero.accuweather";
    private static final String NEWS_PKG = "com.sec.android.widgetapp.apnews";
    private static final String YNEWS_PKG = "com.sec.android.daemonapp.ap.yahoonews";

    private CheckBoxPreference enable;
    private Preference log;
    /** What the screen was last built for; rebuilt when it changes. */
    private String builtFor = "";
    private final Handler ui = new Handler();
    private final Runnable refresh = new Runnable() {
        public void run() {
            update();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Only the 2013 TouchWiz AccuWeather app (Galaxy S4, versionCode 14010301) checks HTTPS
     * certificates; the S2/S3 generations (versionCode 1) accept any.
     */
    private boolean weatherNeedsCert() {
        try {
            return getPackageManager().getPackageInfo(WEATHER_PKG, 0).versionCode >= 13000000;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean showCert() {
        return Prefs.modern() && Prefs.mode(this) != Prefs.MODE_ROOT && weatherNeedsCert() && !Cert.installed(this);
    }

    private String shape() {
        return Prefs.enabled(this) + "/" + Prefs.mode(this) + "/" + showCert();
    }

    @SuppressWarnings("deprecation")
    private void build() {
        builtFor = shape();
        final int m = Prefs.mode(this);
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(this);

        PreferenceCategory services = category(root, "Services");
        enable = new CheckBoxPreference(this);
        enable.setPersistent(false);
        enable.setTitle("Revive services");
        enable.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference p, Object v) {
                if ((Boolean) v) enable();
                else disable();
                update();
                return false; // update() shows the real state
            }
        });
        services.addPreference(enable);

        if (Prefs.modern()) { // Android 2.3 has root mode only: nothing to choose
            ListPreference mode = new ListPreference(this);
            mode.setPersistent(false);
            mode.setTitle("Redirect method");
            mode.setDialogTitle("Redirect method");
            String[] names = {"Root", "VPN", "Wi-Fi proxy"};
            String[] about = {"Hosts file and iptables", "No root, Wi-Fi and mobile data", "No root, Wi-Fi only"};
            String[] entries = new String[3];
            for (int i = 0; i < 3; i++) entries[i] = names[i] + " (" + about[i].toLowerCase(java.util.Locale.US) + ")";
            mode.setEntries(entries);
            mode.setEntryValues(new String[]{"0", "1", "2"});
            mode.setValue(String.valueOf(m));
            mode.setSummary(names[m] + ": " + about[m] + (Prefs.enabled(this) ? ". Disable to change." : ""));
            mode.setEnabled(!Prefs.enabled(this));
            mode.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference p, Object v) {
                    Prefs.setMode(MainActivity.this, Integer.parseInt((String) v));
                    ui.post(new Runnable() {
                        public void run() {
                            build();
                        }
                    });
                    return true;
                }
            });
            services.addPreference(mode);
        }

        if (m == Prefs.MODE_PROXY) {
            pref(services, "Wi-Fi proxy " + PROXY, "Tap for setup steps", new Runnable() {
                public void run() {
                    proxyHelp();
                }
            });
        }

        if (showCert()) {
            pref(services, "Install certificate", "This phone's weather app needs it for current location",
                    new Runnable() {
                        public void run() {
                            try {
                                startActivityForResult(Cert.installIntent(MainActivity.this), REQ_CERT);
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Cannot open installer: " + e, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
        }

        boolean ap = installed(NEWS_PKG), yn = installed(YNEWS_PKG);
        if (ap || yn) {
            PreferenceCategory news = category(root, ap && yn ? "News widgets" : ap ? "AP News widget" : "Yahoo! News widget");
            List<String[]> feeds = Prefs.newsFeeds(this);
            StringBuilder sb = new StringBuilder();
            for (String[] f : feeds) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(f[0]);
            }
            pref(news, "Categories", feeds.size() + ": " + sb, new Runnable() {
                public void run() {
                    editFeeds();
                }
            });
        }

        PreferenceCategory info = category(root, "Info");
        log = pref(info, "Recent requests", "", new Runnable() {
            public void run() {
                showLog();
            }
        });
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        pref(info, "About", "Samsung Revive " + version + ". Weather by Open-Meteo. Based on HTC Revive.", new Runnable() {
            public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("About")
                        .setMessage("Brings back the TouchWiz AccuWeather, AP News and Yahoo! News/Finance widgets by answering "
                                + "their dead servers on the phone itself.\n\nWeather: Open-Meteo (CC BY 4.0). "
                                + "News: the feeds you choose. Based on HTC Revive. Not affiliated with Samsung, "
                                + "AccuWeather or AP.")
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });

        setPreferenceScreen(root);
        update();
    }

    private void proxyHelp() {
        new AlertDialog.Builder(this).setTitle("Wi-Fi proxy")
                .setMessage("1. Wi-Fi settings: long-press your network, Modify network.\n"
                        + "2. Show advanced options, Proxy: Manual.\n"
                        + "3. Host 127.0.0.1, port " + Prefs.HTTP_PORT + ".\n\n"
                        + "Remove the proxy before disabling or uninstalling, or Wi-Fi loses internet.")
                .setPositiveButton("Copy address", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard(PROXY);
                        Toast.makeText(MainActivity.this, "Copied " + PROXY, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @SuppressWarnings("deprecation")
    private PreferenceCategory category(PreferenceScreen root, String title) {
        PreferenceCategory c = new PreferenceCategory(this);
        c.setTitle(title);
        root.addPreference(c);
        return c;
    }

    private Preference pref(PreferenceCategory cat, String title, String summary, final Runnable click) {
        Preference p = new Preference(this);
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        if (click != null) {
            p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                public boolean onPreferenceClick(Preference x) {
                    click.run();
                    return true;
                }
            });
        } else {
            p.setSelectable(false);
        }
        cat.addPreference(p);
        return p;
    }

    private void editFeeds() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine(false);
        e.setMinLines(4);
        e.setTextSize(13);
        e.setText(Prefs.newsFeedsText(this));
        new AlertDialog.Builder(this)
                .setTitle("News categories")
                .setMessage("One category per line: Name|feed URL (RSS or Atom). Refresh the categories "
                        + "in the widget's settings afterwards.")
                .setView(e)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setNewsFeedsText(MainActivity.this, e.getText().toString());
                        Toast.makeText(MainActivity.this, Prefs.newsFeeds(MainActivity.this).size()
                                + " categories saved", Toast.LENGTH_SHORT).show();
                        build();
                    }
                })
                .setNeutralButton("Defaults", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setNewsFeedsText(MainActivity.this, NewsHandler.DEFAULT_FEEDS);
                        build();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLog() {
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setPadding(pad, pad, pad, pad);
        String dump = RequestLog.dump();
        t.setText(dump.length() > 0 ? dump : "No requests yet.");
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("Recent requests").setView(sv)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @SuppressWarnings("deprecation")
    private void copyToClipboard(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("proxy", text));
        } else {
            ((android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setText(text);
        }
    }

    private void enable() {
        if (Prefs.mode(this) == Prefs.MODE_VPN) {
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, REQ_VPN);
            else onActivityResult(REQ_VPN, RESULT_OK, null);
        } else {
            Prefs.setEnabled(this, true);
            startService(new Intent(this, ReviveService.class));
        }
    }

    private void disable() {
        Prefs.setEnabled(this, false);
        final int m = Prefs.mode(this);
        if (m == Prefs.MODE_VPN) {
            startService(new Intent(this, ReviveVpn.class).setAction(ReviveVpn.ACTION_STOP));
            return;
        }
        stopService(new Intent(this, ReviveService.class));
        if (m == Prefs.MODE_ROOT) {
            final Context app = getApplicationContext();
            Servers.status = "removing root redirect...";
            new Thread(new Runnable() {
                public void run() {
                    try {
                        RootSetup.remove(app);
                        Servers.status = "stopped, redirect removed";
                    } catch (Exception e) {
                        Servers.status = "remove failed: " + e.getMessage();
                    }
                }
            }, "revive-remove").start();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN && res == RESULT_OK) {
            Prefs.setEnabled(this, true);
            startService(new Intent(this, ReviveVpn.class));
        }
        build();
    }

    private void update() {
        if (!shape().equals(builtFor)) {
            build();
            return;
        }
        enable.setChecked(Prefs.enabled(this));
        String status = Servers.status;
        if (ClockFix.warning.length() > 0) status += ". " + ClockFix.warning;
        enable.setSummary(status.substring(0, 1).toUpperCase(java.util.Locale.US) + status.substring(1));
        String dump = RequestLog.dump();
        int nl = dump.indexOf('\n');
        log.setSummary(dump.length() == 0 ? "None yet" : (nl > 0 ? dump.substring(0, nl) : dump).replaceFirst("^\\S+\\s+", ""));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An update or a crash leaves "enabled" set with nothing running; bring it back.
        if (Prefs.enabled(this) && Servers.status.startsWith("stopped")) BootReceiver.start(this);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}

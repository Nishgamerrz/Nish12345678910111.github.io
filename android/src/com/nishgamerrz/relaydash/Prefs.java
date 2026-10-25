package com.nishgamerrz.relaydash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Everything the app remembers on the TV: the password, the port, and whether
 * to come back after a reboot.
 *
 * The password is kept twice on purpose:
 *  - in clear, so the TV app can show it to someone sitting on the sofa,
 *  - as a PBKDF2 salt+hash, which is what the web login actually checks.
 * Both live in the app's private SharedPreferences, readable only by this app.
 */
public final class Prefs {

    private static final String FILE = "relay_host";
    private static final String K_PASSWORD = "password";
    private static final String K_SALT = "salt";
    private static final String K_HASH = "hash";
    private static final String K_PORT = "port";
    private static final String K_BOOT = "start_on_boot";

    public static final int DEFAULT_PORT = 8080;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The password shown in the app; generated once on first run. */
    public static String password(Context c) {
        SharedPreferences p = sp(c);
        String pw = p.getString(K_PASSWORD, null);
        if (pw == null || pw.length() == 0) {
            pw = Auth.randomPassword(6);
            p.edit().putString(K_PASSWORD, pw).apply();
        }
        return pw;
    }

    public static void setPassword(Context c, String password) {
        Auth a = new Auth(null, null);
        a.setPassword(password);
        sp(c).edit()
                .putString(K_PASSWORD, password)
                .putString(K_SALT, a.getSaltHex())
                .putString(K_HASH, a.getHashHex())
                .apply();
    }

    /** The verifier the web server uses; self-heals if prefs were half-written. */
    public static Auth auth(Context c) {
        SharedPreferences p = sp(c);
        Auth a = new Auth(p.getString(K_SALT, null), p.getString(K_HASH, null));
        if (!a.isConfigured()) {
            a.setPassword(password(c));
            p.edit()
                    .putString(K_SALT, a.getSaltHex())
                    .putString(K_HASH, a.getHashHex())
                    .apply();
        }
        return a;
    }

    public static int port(Context c) {
        int p = sp(c).getInt(K_PORT, DEFAULT_PORT);
        if (p < MIN_PORT || p > MAX_PORT) p = DEFAULT_PORT;
        return p;
    }

    public static void setPort(Context c, int port) {
        sp(c).edit().putInt(K_PORT, port).apply();
    }

    public static boolean startOnBoot(Context c) {
        return sp(c).getBoolean(K_BOOT, true);
    }

    public static void setStartOnBoot(Context c, boolean on) {
        sp(c).edit().putBoolean(K_BOOT, on).apply();
    }
}

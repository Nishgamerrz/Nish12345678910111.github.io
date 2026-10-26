package com.nishgamerrz.nishgamerr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Everything the app remembers on the TV: the password, the port, and whether
 * to come back after a reboot.
 *
 * The password is kept twice on purpose:
 *  - in clear, so the TV app can show it to someone sitting on the sofa,
 *  - as a PBKDF2 salt+hash, which is what the web login actually checks.
 * Both live in the app's private SharedPreferences, readable only by this app.
 *
 * Prefs die with the app, so the same three values are also mirrored into a
 * vault file on shared storage (/sdcard/Nishgamerr/password). Uninstalling the
 * app wipes /data but not the shared volume, so reinstalling or downloading a
 * newer apk finds the old password again instead of inventing a new one.
 */
public final class Prefs {

    private static final String FILE = "relay_host";
    private static final String K_PASSWORD = "password";
    private static final String K_SALT = "salt";
    private static final String K_HASH = "hash";
    private static final String K_PORT = "port";
    private static final String K_BOOT = "start_on_boot";
    private static final String K_THEME = "theme";
    private static final String K_UPDATE_SHA = "update_sha";
    private static final String K_UPDATE_CHECK = "update_checked_at";

    /** Shared-storage folder; the only place that outlives an uninstall. */
    public static final String VAULT_DIR = "Nishgamerr";
    private static final String VAULT_FILE = "password";

    public static final int DEFAULT_PORT = 8080;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ── the vault ───────────────────────────────────────────────────────── */

    /**
     * Where the surviving copy lives, or null when no shared volume is usable.
     * Never throws: on LOCKED_BOOT (direct boot, before the user unlocks) the
     * shared volume is not mounted yet, and the boot receiver still has to work.
     */
    public static File vaultDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext == null) return null;
            File dir = new File(ext, VAULT_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File vaultFile() {
        File dir = vaultDir();
        return dir == null ? null : new File(dir, VAULT_FILE);
    }

    /** password\nsalt\nhash, or null. */
    private static String[] readVault(Context c) {
        File f = vaultFile();
        if (f == null || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(buf);
            if (n <= 0) return null;
            String[] lines = new String(buf, 0, n, "UTF-8").split("\n");
            if (lines.length < 3) return null;
            String pw = lines[0].trim();
            String salt = lines[1].trim();
            String hash = lines[2].trim();
            if (pw.length() == 0 || salt.length() == 0 || hash.length() == 0) return null;
            return new String[]{pw, salt, hash};
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    private static void writeVault(String password, String salt, String hash) {
        File f = vaultFile();
        if (f == null) return;
        OutputStream out = null;
        try {
            File tmp = new File(f.getParentFile(), VAULT_FILE + ".tmp");
            out = new FileOutputStream(tmp);
            out.write((password + "\n" + salt + "\n" + hash + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(f)) {
                // some volumes refuse rename across the same dir; write in place
                FileOutputStream direct = new FileOutputStream(f);
                direct.write((password + "\n" + salt + "\n" + hash + "\n").getBytes("UTF-8"));
                direct.close();
                tmp.delete();
            }
        } catch (Throwable ignored) {
            // read-only or unmounted volume: prefs still hold the password
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    /** Pull a surviving password back into prefs after a reinstall. */
    public static boolean restoreFromVault(Context c) {
        SharedPreferences p = sp(c);
        String current = p.getString(K_PASSWORD, null);
        if (current != null && current.length() > 0) return false;
        String[] v = readVault(c);
        if (v == null) return false;
        p.edit()
                .putString(K_PASSWORD, v[0])
                .putString(K_SALT, v[1])
                .putString(K_HASH, v[2])
                .apply();
        return true;
    }

    /** The password shown in the app; generated once on first run. */
    public static String password(Context c) {
        SharedPreferences p = sp(c);
        String pw = p.getString(K_PASSWORD, null);
        if (pw == null || pw.length() == 0) {
            if (!restoreFromVault(c)) {
                pw = Auth.randomPassword(6);
                Auth a = new Auth(null, null);
                a.setPassword(pw);
                p.edit()
                        .putString(K_PASSWORD, pw)
                        .putString(K_SALT, a.getSaltHex())
                        .putString(K_HASH, a.getHashHex())
                        .apply();
                // never clobber a surviving vault with a freshly invented
                // password just because the volume was not mounted yet
                File vf = vaultFile();
                if (vf == null || !vf.isFile()) writeVault(pw, a.getSaltHex(), a.getHashHex());
            }
            pw = p.getString(K_PASSWORD, null);
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
        writeVault(password, a.getSaltHex(), a.getHashHex());
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
            File vf = vaultFile();
            if (vf == null || !vf.isFile()) writeVault(password(c), a.getSaltHex(), a.getHashHex());
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

    /* ── dashboard style ─────────────────────────────────────────────────── */

    public static String theme(Context c) {
        String theme = sp(c).getString(K_THEME, null);
        return theme == null || !Dashboard.isTheme(theme) ? Dashboard.DEFAULT_THEME : theme;
    }

    public static void setTheme(Context c, String theme) {
        if (!Dashboard.isTheme(theme)) return;
        sp(c).edit().putString(K_THEME, theme).apply();
    }

    /* ── the installed version, for the updater ──────────────────────────── */

    private static int cachedCode = -1;
    private static String cachedName = "";

    private static void readVersion(Context c) {
        if (cachedCode > 0) return;
        try {
            android.content.pm.PackageInfo info = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            cachedCode = info.versionCode;
            cachedName = info.versionName == null ? String.valueOf(info.versionCode) : info.versionName;
        } catch (Throwable t) {
            cachedCode = 1;
            cachedName = HostState.VERSION;
        }
    }

    public static int versionCode(Context c) {
        readVersion(c);
        return cachedCode;
    }

    public static String versionName(Context c) {
        readVersion(c);
        return cachedName;
    }

    /* ── updater bookkeeping ─────────────────────────────────────────────── */

    public static String updateSha(Context c) {
        return sp(c).getString(K_UPDATE_SHA, "");
    }

    public static void setUpdateSha(Context c, String sha) {
        sp(c).edit().putString(K_UPDATE_SHA, sha == null ? "" : sha).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong(K_UPDATE_CHECK, 0L);
    }

    public static void setLastUpdateCheck(Context c, long when) {
        sp(c).edit().putLong(K_UPDATE_CHECK, when).apply();
    }
}

package com.nishgamerrz.nishgamerr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Quiet self-update from the project's own main branch.
 *
 * The TV is normally headless, so the whole cycle has to be visible somewhere:
 * every step posts a notification and is mirrored on the TV screen and in the
 * web dashboard ("Checking", "Downloading 43%", "Update ready", "Updated").
 *
 * The apk is only installed after the download has been checked twice - the
 * package name must be ours, the version code must be newer, and the signing
 * certificate must be identical to the running app, so a tampered file on the
 * network can never replace this app.
 */
public final class Updater {

    /** What the updater is doing right now, for the UI and the dashboard. */
    public static final String IDLE = UpdateState.IDLE;
    public static final String CHECKING = UpdateState.CHECKING;
    public static final String DOWNLOADING = UpdateState.DOWNLOADING;
    public static final String READY = UpdateState.READY;
    public static final String INSTALLING = UpdateState.INSTALLING;
    public static final String UPTODATE = UpdateState.UPTODATE;
    public static final String FAILED = UpdateState.FAILED;

    public static final String AUTHORITY = "com.nishgamerrz.nishgamerr.updates";
    public static final String APK_NAME = "Nishgamerr.apk";

    private static final String API =
            "https://api.github.com/repos/Nishgamerrz/Nish12345678910111.github.io/contents/"
                    + APK_NAME + "?ref=main";
    private static final String FALLBACK =
            "https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/"
                    + APK_NAME;

    private static final String CHANNEL_ID = "nishgamerr_updates";
    private static final int NOTIFICATION_ID = 2002;

    private static final long AUTO_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static volatile boolean busy;

    private Updater() {
    }

    /* ── state, read by MainActivity and the dashboard ───────────────────── */

    public static String state() {
        return UpdateState.state();
    }

    public static String latest() {
        return UpdateState.latest();
    }

    public static int percent() {
        return UpdateState.percent();
    }

    public static String error() {
        return UpdateState.error();
    }

    public static long checkedAt() {
        return UpdateState.checkedAt();
    }

    public static String currentVersion(Context c) {
        return Prefs.versionName(c);
    }

    /* ── scheduling ──────────────────────────────────────────────────────── */

    /** Called from the app, the boot receiver and the watchdog job. */
    public static void maybeCheck(final Context context, final boolean interactive) {
        if (busy) return;
        long now = System.currentTimeMillis();
        long last = Prefs.lastUpdateCheck(context);
        if (!interactive && last > 0 && now - last < AUTO_INTERVAL_MS) return;
        check(context, interactive);
    }

    public static void check(final Context context, final boolean interactive) {
        if (busy) return;
        busy = true;
        UpdateState.setState(CHECKING);
        UpdateState.setError("");
        UpdateState.setPercent(0);
        final Context app = context.getApplicationContext();
        notify(context, "Checking for updates", "Looking at the main branch…", false);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    checkRemote(app, interactive);
                } catch (Throwable e) {
                    fail(app, describe(e));
                } finally {
                    busy = false;
                    Prefs.setLastUpdateCheck(app, System.currentTimeMillis());
                    UpdateState.setCheckedAt(System.currentTimeMillis());
                }
            }
        }, "nishgamerr-update");
        t.setDaemon(true);
        t.start();
    }

    private static void checkRemote(Context context, boolean interactive) {
        String remoteSha = remoteSha(context);
        String known = Prefs.updateSha(context);
        if (!interactive && remoteSha != null && remoteSha.equals(known)) {
            // The main branch has not moved since we last looked.
            UpdateState.setState(UPTODATE);
            return;
        }

        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail(context, "cannot write the download folder");
            return;
        }
        File apk = new File(dir, APK_NAME);
        UpdateState.setState(DOWNLOADING);
        UpdateState.setPercent(0);
        notify(context, "Downloading update", "Fetching " + APK_NAME + "…", false);
        download(context, FALLBACK, apk);

        PackageManager pm = context.getPackageManager();
        PackageInfo remote = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA);
        if (remote == null || remote.packageName == null) {
            apk.delete();
            fail(context, "the downloaded file is not an app");
            return;
        }
        if (!context.getPackageName().equals(remote.packageName)) {
            apk.delete();
            fail(context, "the download is a different app");
            return;
        }
        String remoteName = remote.versionName == null ? String.valueOf(remote.versionCode) : remote.versionName;
        int installed = Prefs.versionCode(context);
        if (remote.versionCode <= installed) {
            apk.delete();
            if (remoteSha != null) Prefs.setUpdateSha(context, remoteSha);
            UpdateState.setState(UPTODATE);
            notify(context, "Nishgamerr is up to date", "Already on v" + currentVersion(context), false);
            return;
        }
        if (!sameSignature(context, pm, apk)) {
            apk.delete();
            fail(context, "the download is not signed with this app's key");
            return;
        }
        UpdateState.setLatest(remoteName);
        UpdateState.setState(READY);
        UpdateState.setPercent(100);
        notify(context, "Update v" + remoteName + " is ready",
                "Open Nishgamerr and press Install to finish.", true);
    }

    /* ── install ─────────────────────────────────────────────────────────── */

    /**
     * Hands the downloaded apk to the system installer.
     *
     * Android does not allow an app to replace itself without the user
     * agreeing at least once, so this opens the installer with the file
     * already selected: one press on the TV remote finishes it.
     */
    public static boolean install(Context context) {
        File apk = new File(new File(context.getCacheDir(), "update"), APK_NAME);
        if (!apk.isFile()) {
            UpdateState.setState(FAILED);
            UpdateState.setError("no downloaded update");
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse("content://" + AUTHORITY + "/" + APK_NAME),
                    "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            UpdateState.setState(INSTALLING);
            notify(context, "Finish the update", "Press Install on the TV screen.", true);
            return true;
        } catch (Throwable e) {
            fail(context, "could not open the installer: " + describe(e));
            return false;
        }
    }

    /** Called from the boot receiver after MY_PACKAGE_REPLACED. */
    public static void onNewVersion(Context context) {
        File dir = new File(context.getCacheDir(), "update");
        File apk = new File(dir, APK_NAME);
        if (apk.isFile()) apk.delete();
        UpdateState.setState(UPTODATE);
        UpdateState.setLatest("");
        UpdateState.setPercent(0);
        String sha = Prefs.updateSha(context);
        if (sha == null) Prefs.setUpdateSha(context, "");
        notify(context, "Nishgamerr updated",
                "Now running v" + currentVersion(context) + " — consoles kept.", false);
    }

    /* ── network ─────────────────────────────────────────────────────────── */

    /** Blob sha of the apk on main, or null when GitHub cannot be asked. */
    private static String remoteSha(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Nishgamerr-Updater");
            int code = connection.getResponseCode();
            if (code != 200) return null;
            String body = read(connection.getInputStream(), 200000);
            String sha = field(body, "sha");
            if (sha != null && sha.length() > 0) Prefs.setUpdateSha(context, sha);
            return sha;
        } catch (Throwable e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void download(Context context, String url, File target) {
        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Nishgamerr-Updater");
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            int total = connection.getContentLength();
            in = connection.getInputStream();
            out = new FileOutputStream(target);
            byte[] buffer = new byte[16384];
            int read;
            long done = 0;
            int lastPercent = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int p = (int) (done * 100 / total);
                    if (p != lastPercent) {
                        lastPercent = p;
                        UpdateState.setPercent(p);
                        if (p % 10 == 0) {
                            notify(context, "Downloading update", p + "% of " + APK_NAME, false);
                        }
                    }
                }
            }
            out.flush();
            UpdateState.setPercent(100);
        } catch (Throwable e) {
            throw new RuntimeException(describe(e));
        } finally {
            close(in);
            close(out);
            if (connection != null) connection.disconnect();
        }
    }

    /** The apk we are about to install must be signed by the key we run under. */
    private static boolean sameSignature(Context context, PackageManager pm, File apk) {
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (installed == null || installed.signatures == null || installed.signatures.length == 0) {
                return false;
            }
            if (candidate == null || candidate.signatures == null || candidate.signatures.length == 0) {
                return false;
            }
            for (android.content.pm.Signature a : installed.signatures) {
                for (android.content.pm.Signature b : candidate.signatures) {
                    if (a.equals(b)) return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /* ── notifications: the messages the TV shows ────────────────────────── */

    public static void notify(Context context, String title, String text, boolean alert) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "App updates", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("When Nishgamerr downloads or installs an update");
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getActivity(context, 1, open, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(context, CHANNEL_ID);
            } else {
                b = new Notification.Builder(context);
                b.setPriority(alert ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_LOW);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pending)
                    .setOnlyAlertOnce(!alert)
                    .setShowWhen(false)
                    .setAutoCancel(alert);
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static void fail(Context context, String why) {
        UpdateState.setState(FAILED);
        UpdateState.setError(why);
        notify(context, "Update failed", why, false);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        String name = e.getClass().getSimpleName();
        return message == null || message.length() == 0 ? name : name + ": " + message;
    }

    private static String read(InputStream in, int limit) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            sb.append(new String(buffer, 0, n, "UTF-8"));
            if (sb.length() > limit) break;
        }
        return sb.toString();
    }

    /** Tiny JSON field reader - the API response has no nested surprises for us. */
    private static String field(String json, String name) {
        String needle = "\"" + name + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}

package com.nishgamerrz.nishgamerr;

/**
 * What the updater is doing, in a class with no Android imports at all.
 *
 * The web server is deliberately plain Java so it can run on a desktop JVM in
 * the test harness; if it touched Updater (which does use Notification, Intent
 * and friends) the JVM would fail to load the class. Everything the dashboard
 * needs to know therefore lives here, and Updater writes into it.
 */
public final class UpdateState {

    public static final String IDLE = "idle";
    public static final String CHECKING = "checking";
    public static final String DOWNLOADING = "downloading";
    public static final String READY = "ready";
    public static final String INSTALLING = "installing";
    public static final String UPTODATE = "uptodate";
    public static final String FAILED = "failed";

    private static volatile String state = IDLE;
    private static volatile String latest = "";
    private static volatile int percent;
    private static volatile String error = "";
    private static volatile long checkedAt;

    private UpdateState() {
    }

    public static String state() {
        return state;
    }

    public static String latest() {
        return latest;
    }

    public static int percent() {
        return percent;
    }

    public static String error() {
        return error;
    }

    public static long checkedAt() {
        return checkedAt;
    }

    public static void setState(String value) {
        state = value == null ? IDLE : value;
        if (!DOWNLOADING.equals(state) && !INSTALLING.equals(state)) {
            // keep the last percentage only while a transfer is running
        }
    }

    public static void setLatest(String value) {
        latest = value == null ? "" : value;
    }

    public static void setPercent(int value) {
        percent = value < 0 ? 0 : (value > 100 ? 100 : value);
    }

    public static void setError(String value) {
        error = value == null ? "" : value;
    }

    public static void setCheckedAt(long value) {
        checkedAt = value;
    }
}

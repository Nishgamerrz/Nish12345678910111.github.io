package com.nishgamerrz.relaydash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the dashboard server alive while the TV app is closed or the screen is
 * off.
 *
 * Android 8 requires a foreground service for this ("fully background" hosting
 * still means a visible notification), so this service is the one that owns the
 * lifetime: START_STICKY, restart on the next power-on via BootReceiver.
 */
public final class HostService extends Service {

    public static final String ACTION_START = "com.nishgamerrz.relaydash.START";
    public static final String ACTION_STOP = "com.nishgamerrz.relaydash.STOP";

    private static final String CHANNEL_ID = "relay_host";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        public void run() {
            updateNotification();
            handler.postDelayed(this, 30000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            HostState.stop();
            handler.removeCallbacks(refresher);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String error = HostState.start(this);
        startForeground(NOTIFICATION_ID, buildNotification(error));
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, 30000L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresher);
        // Do not kill the server here: the Activity may be recreating the
        // service. Whoever really wants it stopped sends ACTION_STOP.
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ── notification ────────────────────────────────────────────────────── */

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Dashboard host",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the address of the built-in dashboard server");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String error) {
        boolean running = HostState.isRunning();
        String url = running ? HostState.url(this) : "Not running";
        String title = running ? "Dashboard running" : "Dashboard stopped";

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(title)
                .setContentText(error != null ? error : url)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(content);

        if (error != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(error));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(
                    url + "\nOpen this address from any device on the same Wi-Fi."));
        }
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(HostState.lastError()));
        } catch (Exception ignored) {
        }
    }
}

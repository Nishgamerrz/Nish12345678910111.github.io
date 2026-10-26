package com.nishgamerrz.nishgamerr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Brings the dashboard back after the TV has been switched off at the wall.
 *
 * BOOT_COMPLETED is one of the few places Android 8 still allows a foreground
 * service to be started from the background, which is exactly what this needs.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        // During direct boot the user's storage is still locked and prefs throw.
        // A quick-boot / locked-boot wake-up means "the TV just came on": start
        // anyway, because that is exactly the case the app used to miss.
        boolean locked = "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action);
        if (!locked) {
            try {
                if (!Prefs.startOnBoot(context)) return;
            } catch (Throwable ignored) {
                // prefs unreadable: the default is to start
            }
        }
        try {
            Intent service = new Intent(context, HostService.class);
            service.setAction(HostService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            // belt and braces: if the service is dropped (vendor launchers love
            // to do that while the TV boots) the watchdog brings it back.
            WatchdogJob.schedule(context);
            if (locked) WatchdogJob.nudge(context);
        } catch (Throwable ignored) {
            // Never crash the boot path: the user can always open the app.
        }
    }
}

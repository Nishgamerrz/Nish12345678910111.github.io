package com.nishgamerrz.relaydash;

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
        try {
            if (!Prefs.startOnBoot(context)) return;
            Intent service = new Intent(context, HostService.class);
            service.setAction(HostService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
            // Never crash the boot path: the user can always open the app.
        }
    }
}

package com.nishgamerrz.nishgamerr;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * "It did not start when the TV came on."
 *
 * BOOT_COMPLETED alone is not enough on TVs that come back from standby, get
 * force-stopped by the vendor launcher, or lose the service to low memory. This
 * job wakes up every 15 minutes and restarts the host if it is not running.
 * Android 8 also allows starting a foreground service from a job, which is why
 * this is a JobScheduler job and not an alarm.
 */
public final class WatchdogJob extends JobService {

    private static final int JOB_ID = 4711;
    private static final int NUDGE_ID = 4712;
    private static final long INTERVAL_MS = 15L * 60L * 1000L;

    public static void schedule(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 21) return;
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, WatchdogJob.class))
                    .setPersisted(true)
                    .setPeriodic(INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
            scheduler.schedule(builder.build());
        } catch (Exception ignored) {
        }
    }

    /**
     * A one-shot check about a minute after boot. Some TVs drop the foreground
     * service while the launcher is still coming up; waiting 15 minutes for the
     * periodic job would mean the dashboard is unreachable for that long.
     */
    public static void nudge(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo.Builder builder = new JobInfo.Builder(NUDGE_ID,
                    new ComponentName(context, WatchdogJob.class))
                    .setPersisted(true)
                    .setMinimumLatency(60L * 1000L)
                    .setOverrideDeadline(120L * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
            scheduler.schedule(builder.build());
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            boolean wanted;
            try {
                wanted = Prefs.startOnBoot(this);
            } catch (Throwable locked) {
                wanted = true;      // direct boot: prefs not readable yet
            }
            try {
                Updater.maybeCheck(this, false);   // at most every six hours
            } catch (Throwable ignored) {
            }
            if (wanted && !HostState.isRunning()) {
                Intent service = new Intent(this, HostService.class);
                service.setAction(HostService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // ask for another run
    }
}

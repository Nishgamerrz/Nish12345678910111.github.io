package com.nishgamerrz.nishgamerr;

import android.content.Context;
import android.os.Build;

/**
 * The one place that owns the embedded web server, so the Activity, the
 * foreground Service and the boot receiver all agree on what is running.
 *
 * Deliberately all-static: the server has to keep serving while the TV UI is in
 * the background, and there is exactly one of it per process.
 */
public final class HostState {

    public static final String VERSION = "2.1";

    private static WebServer server;
    private static DeviceLog devices = new DeviceLog();
    private static ShellRuntime runtime;
    private static ConsoleManager consoles;
    private static volatile String shellState = "not installed";
    private static volatile int provisionPercent;
    private static volatile String provisionStep = "";
    private static volatile String lastError;
    private static volatile long startedAt;

    private HostState() {
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static synchronized String lastError() {
        return lastError;
    }

    public static synchronized DeviceLog devices() {
        return devices;
    }

    public static synchronized WebServer server() {
        return server;
    }

    public static synchronized long startedAt() {
        return startedAt;
    }

    public static synchronized String shellState() {
        return shellState;
    }

    public static synchronized int provisionPercent() {
        return provisionPercent;
    }

    public static synchronized String provisionStep() {
        return provisionStep;
    }

    public static synchronized ConsoleManager consoles(Context c) {
        if (consoles == null) {
            consoles = new ConsoleManager(runtime(c));
        }
        return consoles;
    }

    public static synchronized ShellRuntime runtime(Context c) {
        if (runtime == null) {
            Context app = c.getApplicationContext();
            final Context ctx = app;
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
            runtime = new ShellRuntime(new java.io.File(app.getFilesDir(), "shell"), abi,
                    new ShellRuntime.Source() {
                        public byte[] read(String name) throws java.io.IOException {
                            java.io.InputStream in = ctx.getAssets().open(name);
                            try {
                                return ShellRuntime.readAll(in).toByteArray();
                            } finally {
                                in.close();
                            }
                        }
                    });
        }
        return runtime;
    }

    /**
     * Unpacks BusyBox + applets in the background. Runs on every host start and
     * is a no-op once installed, so "packages" appear without the user doing
     * anything - the auto-install the app is expected to do.
     */
    public static void provision(final Context context) {
        final ShellRuntime rt = runtime(context);
        final ConsoleManager cm = consoles(context);
        if (rt.isInstalled()) {
            shellState = rt.version();
            provisionPercent = 100;
            if (server != null) server.setShellVersion(shellState);
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    rt.install(new ShellRuntime.Progress() {
                        public void onStep(String step, int percent) {
                            provisionStep = step;
                            provisionPercent = percent;
                        }
                    });
                    shellState = rt.version();
                    provisionPercent = 100;
                    provisionStep = "Installed";
                    if (server != null) server.setShellVersion(shellState);
                } catch (Exception e) {
                    shellState = "install failed: " + e.getMessage();
                    provisionStep = shellState;
                    provisionPercent = 0;
                }
            }
        }, "shell-provision");
        t.setDaemon(true);
        t.start();
    }

    public static String url(Context c) {
        int port;
        synchronized (HostState.class) {
            port = server != null ? server.getPort() : Prefs.port(c);
        }
        return "http://" + WebServer.lanAddress() + ":" + port;
    }

    public static String deviceModel() {
        String model = Build.MODEL == null ? "Android device" : Build.MODEL;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        if (brand.length() > 0 && model.toLowerCase().indexOf(brand.toLowerCase()) != 0) {
            return brand + " " + model;
        }
        return model;
    }

    public static String androidVersion() {
        return Build.VERSION.RELEASE == null ? "?" : Build.VERSION.RELEASE;
    }

    /** Idempotent: starting an already-running host is a no-op. */
    public static synchronized String start(Context context) {
        if (server != null && server.isRunning()) {
            return null;
        }
        Context app = context.getApplicationContext();
        provision(app);                       // unpack busybox in the background
        ConsoleManager cm = consoles(app);
        String shellVersion = shellState;
        int port = Prefs.port(app);
        Auth auth = Prefs.auth(app);
        WebServer fresh = new WebServer(port, auth, devices, cm,
                deviceModel(), VERSION, deviceModel(), "Android " + androidVersion(),
                shellVersion,
                new WebServer.Listener() {
                    public void onState(boolean running, String error) {
                        if (error != null) lastError = error;
                        else if (running) lastError = null;
                    }

                    public void onRequest(String ip, String agent, String path) {
                        // DeviceLog is updated by the server itself; nothing to do.
                    }
                });
        try {
            fresh.start();
            server = fresh;
            startedAt = System.currentTimeMillis();
            lastError = null;
            return null;
        } catch (Exception e) {
            String message = e.getClass().getSimpleName();
            String detail = e.getMessage();
            if (detail != null && detail.length() > 0) message = message + " - " + detail;
            lastError = "Could not start on port " + port + ": " + message;
            server = null;
            return lastError;
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        server = null;
        startedAt = 0;
    }

    public static synchronized void clearDevices() {
        devices.clear();
    }
}

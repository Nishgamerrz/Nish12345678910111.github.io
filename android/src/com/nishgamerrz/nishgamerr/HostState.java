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

    public static final String VERSION = "2.5";

    private static WebServer server;
    private static DeviceLog devices = new DeviceLog();
    private static ShellRuntime runtime;
    private static ConsoleManager consoles;
    private static volatile String shellState = "not installed";
    private static volatile int provisionPercent;
    private static volatile String provisionStep = "";
    private static volatile boolean termuxRunning;
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

    /** True when the consoles are driven by Termux's own bash. */
    public static boolean usingTermux(Context context) {
        return runtime(context).usingTermux();
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
     * The real Termux userland: downloaded once, relocated into this app's data
     * directory, and used for every console as soon as bash has proven it runs.
     */
    private static void startTermux(final Context context, final ShellRuntime rt,
                                    final Runnable after) {
        if (rt.usingTermux()) {
            rt.termux().refreshShims();
            shellState = rt.termux().state();
            if (server != null) server.setShellVersion(shellState);
            return;
        }
        if (termuxRunning) return;
        termuxRunning = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    boolean ready = rt.termux().install(new TermuxRuntime.Progress() {
                        public void onStep(String step, int percent) {
                            provisionStep = step;
                            if (percent > 0) provisionPercent = percent;
                            shellState = "Nishgamerr " + step.toLowerCase();
                        }
                    });
                    if (ready) {
                        shellState = rt.termux().state();
                        provisionStep = "Termux ready";
                        // consoles that were already started on ash move to bash
                        try {
                            consoles(context).rebindShells();
                        } catch (Throwable ignored) {
                        }
                        installExtras(rt, consoles(context));
                    } else {
                        String why = rt.termux().lastError();
                        shellState = rt.version() + (why.length() == 0
                                ? " (Termux unavailable)" : " (Termux failed: " + why + ")");
                        provisionStep = "Bundled shell in use";
                        installExtras(rt, consoles(context));
                    }
                    provisionPercent = 100;
                    if (server != null) server.setShellVersion(shellState);
                } catch (Throwable ignored) {
                } finally {
                    termuxRunning = false;
                    if (after != null) after.run();
                }
            }
        }, "termux-install");
        t.setDaemon(true);
        t.start();
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
            // an update may bring a new pkg script; refresh it in place
            rt.refreshShims();
            shellState = rt.usingTermux() ? rt.termux().state() : rt.version();
            provisionPercent = 100;
            if (server != null) server.setShellVersion(shellState);
            startTermux(context, rt, null);
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
                    // The bundled shell works already; the real Termux userland
                    // downloads and unpacks behind it, so nobody has to wait.
                    startTermux(context, rt, null);
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

    /**
     * The packages a fresh Termux has, fetched in the background whatever shell
     * is running. The real bash is installed and *proven* first; only then are
     * git and nano fetched, so a TV where Termux binaries cannot run keeps a
     * working console instead of half-installed binaries.
     */
    private static void installExtras(final ShellRuntime rt, final ConsoleManager cm) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                java.io.File marker = new java.io.File(rt.getPrefix(), "var/lib/nishgamerr/extras.done");
                try {
                    if (marker.isFile()) return;
                    provisionStep = "Installing bash";
                    String out = rt.run("pkg install bash", 900000);
                    String line = tail(out);
                    rt.termux().log("extras bash: " + line);
                    String proof = rt.run("bash -c 'echo NISH_BASH_OK'", 60000);
                    boolean bashWorks = proof != null && proof.contains("NISH_BASH_OK");
                    if (!bashWorks) {
                        rt.termux().log("extras: the downloaded bash does not run here ("
                                + tail(proof) + "), keeping the bundled shell");
                        new java.io.File(rt.getPrefix(), "bin/bash").delete();
                        provisionStep = "Bundled shell (downloaded bash cannot run here)";
                        return;
                    }
                    java.io.File okMarker = rt.bashOk();
                    java.io.File dir = okMarker.getParentFile();
                    if (dir != null && !dir.isDirectory()) dir.mkdirs();
                    new java.io.FileOutputStream(okMarker).close();
                    rt.termux().log("extras: bash works, consoles move to it");
                    try {
                        if (cm != null) cm.rebindShells();
                    } catch (Throwable ignored) {
                    }

                    boolean all = true;
                    String[] steps = {"git", "nano"};
                    for (int i = 0; i < steps.length; i++) {
                        provisionStep = "Installing " + steps[i] + " in the background";
                        String result = rt.run("pkg install " + steps[i], 900000);
                        rt.termux().log("extras " + steps[i] + ": " + tail(result));
                        if (result == null || result.contains("[!]")) all = false;
                    }
                    if (all) {
                        new java.io.FileOutputStream(marker).close();
                        rt.termux().log("extras done");
                    } else {
                        rt.termux().log("extras incomplete, will retry next start");
                    }
                    provisionStep = "Ready";
                    if (server != null) server.setShellVersion(rt.usingTermux()
                            ? rt.termux().state() : rt.version());
                } catch (Throwable e) {
                    rt.termux().log("extras failed: " + e);
                }
            }
        }, "termux-extras");
        t.setDaemon(true);
        t.start();
    }

    private static String tail(String out) {
        if (out == null) return "no answer";
        String line = out.replace('\n', ' ').trim();
        return line.length() > 300 ? line.substring(line.length() - 300) : line;
    }

    /** The Termux side of the story, for the TV card and /api/termux. */
    public static TermuxRuntime termux(Context context) {
        return runtime(context).termux();
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
        fresh.setContext(app);
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

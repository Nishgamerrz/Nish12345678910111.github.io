package com.nishgamerrz.relaydash;

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

    public static final String VERSION = "2.0";

    private static WebServer server;
    private static DeviceLog devices = new DeviceLog();
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
        int port = Prefs.port(app);
        Auth auth = Prefs.auth(app);
        CommandRunner runner = new CommandRunner("/system/bin/sh");
        WebServer fresh = new WebServer(port, auth, devices, runner,
                deviceModel(), VERSION, deviceModel(), "Android " + androidVersion(),
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

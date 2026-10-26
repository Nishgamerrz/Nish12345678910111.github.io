package com.nishgamerrz.nishgamerr;

import java.io.File;
import java.io.FileInputStream;

/**
 * Desktop harness: provisions the shell exactly as the app does (from a local
 * copy of the bundled BusyBox), starts the real WebServer, and prints the URL
 * so tools/test-server.sh can drive it with curl.
 *
 * Usage: ServerHarness <port> <password> <busybox-path> <workdir>
 */
public final class ServerHarness {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18099;
        String password = args.length > 1 ? args[1] : "test1234";
        final String busybox = args.length > 2 ? args[2] : "assets/busybox/busybox-x86_64";
        String workdir = args.length > 3 ? args[3] : null;
        final File root = workdir == null
                ? new File(System.getProperty("java.io.tmpdir"), "nishgamerr-shell-" + port)
                : new File(workdir);

        Auth auth = new Auth(null, null);
        if (password.length() > 0) auth.setPassword(password);

        ShellRuntime runtime = new ShellRuntime(root, "x86_64", new ShellRuntime.Source() {
            public byte[] read(String name) throws java.io.IOException {
                File file = new File(busybox);
                if (name != null && !name.startsWith("busybox-")) {
                    File asset = new File(System.getProperty("nishgamerr.assets", "assets"), name);
                    if (asset.isFile()) {
                        FileInputStream in = new FileInputStream(asset);
                        try {
                            return ShellRuntime.readAll(in).toByteArray();
                        } finally {
                            in.close();
                        }
                    }
                }
                if (!file.isFile()) return null;
                FileInputStream in = new FileInputStream(file);
                try {
                    return ShellRuntime.readAll(in).toByteArray();
                } finally {
                    in.close();
                }
            }
        });
        runtime.install(new ShellRuntime.Progress() {
            public void onStep(String step, int percent) {
                System.out.println("STEP " + percent + "% " + step);
                System.out.flush();
            }
        });
        System.out.println("SHELL " + runtime.version());
        System.out.flush();

        ConsoleManager consoles = new ConsoleManager(runtime);
        DeviceLog devices = new DeviceLog();

        WebServer server = new WebServer(port, auth, devices, consoles,
                "Test TV", "2.2-test", "Harness", "Android 8.0.0", runtime.version(),
                new WebServer.Listener() {
                    public void onState(boolean running, String error) {
                        System.out.println("STATE " + running + " " + (error == null ? "" : error));
                        System.out.flush();
                    }

                    public void onRequest(String ip, String agent, String path) {
                        System.out.println("REQ " + ip + " " + path);
                        System.out.flush();
                    }
                });

        server.start();
        System.out.println("URL http://127.0.0.1:" + port + "/");
        System.out.println("LISTENING");
        System.out.flush();

        Thread.sleep(300000L);
        server.stop();
    }
}

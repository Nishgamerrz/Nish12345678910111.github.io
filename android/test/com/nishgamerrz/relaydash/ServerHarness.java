package com.nishgamerrz.relaydash;

/**
 * Desktop harness: starts the real WebServer on a free port and prints what it
 * bound to, so tools/test-server.sh can drive it with curl.
 *
 * Uses /bin/sh instead of /system/bin/sh for the console, and prints state
 * changes on stdout so the test can wait for "LISTENING".
 */
public final class ServerHarness {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18099;
        String password = args.length > 1 ? args[1] : "test1234";

        Auth auth = new Auth(null, null);
        if (password.length() > 0) auth.setPassword(password);

        DeviceLog devices = new DeviceLog();
        CommandRunner runner = new CommandRunner("/bin/sh", 5000L);

        WebServer server = new WebServer(port, auth, devices, runner,
                "Test TV", "2.0-test", "Harness", "8.0",
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

        // stay alive until killed
        Thread.sleep(120000L);
        server.stop();
    }
}

package com.nishgamerrz.relaydash;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Runs a command in the device's own shell and returns its output.
 *
 * This is the "console" the web dashboard exposes. It runs as the app's own
 * user (no root, no Termux): /system/bin/sh is part of Android itself.
 *
 * The shell path is a constructor argument so the JVM test harness can point it
 * at /bin/sh instead.
 */
public final class CommandRunner {

    private static final int MAX_OUTPUT_BYTES = 32 * 1024;
    private static final long DEFAULT_TIMEOUT_MS = 10000L;

    private final String shell;
    private final long timeoutMs;

    public CommandRunner(String shell) {
        this(shell, DEFAULT_TIMEOUT_MS);
    }

    public CommandRunner(String shell, long timeoutMs) {
        this.shell = shell;
        this.timeoutMs = timeoutMs;
    }

    public String shellPath() {
        return shell;
    }

    /** Never throws: failures come back as text, because this feeds a web page. */
    public String run(String command) {
        if (command == null || command.trim().length() == 0) {
            return "Type a command first, for example: id or getprop ro.product.model";
        }
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        Process process = null;
        Thread reader = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File("/"));
            process = pb.start();

            final InputStream in = process.getInputStream();
            reader = new Thread(new Runnable() {
                public void run() {
                    byte[] chunk = new byte[4096];
                    int n;
                    try {
                        while ((n = in.read(chunk)) > 0) {
                            synchronized (buffer) {
                                int room = MAX_OUTPUT_BYTES - buffer.size();
                                if (room > 0) buffer.write(chunk, 0, Math.min(room, n));
                            }
                        }
                    } catch (Exception ignored) {
                        // process died or was killed: whatever was read stands
                    }
                }
            }, "cmd-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean finished = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    process.exitValue();
                    finished = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(40L);
                }
            }
            if (!finished) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
            // give the reader a moment to drain the last bytes
            try {
                reader.join(400L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            String out;
            synchronized (buffer) {
                out = buffer.toString("UTF-8");
            }
            if (out.length() == 0) {
                out = finished ? "(no output)" : "(no output before the timeout)";
            }
            if (!finished) {
                out = out + "\n[stopped after " + (timeoutMs / 1000) + "s]";
            } else if (buffer.size() >= MAX_OUTPUT_BYTES) {
                out = out + "\n[output truncated]";
            }
            return out;
        } catch (Exception e) {
            return "Could not run the command: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }
}

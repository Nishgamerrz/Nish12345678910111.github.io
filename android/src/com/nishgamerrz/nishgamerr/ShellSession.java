package com.nishgamerrz.nishgamerr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One persistent console: a single long-lived ash process with pipes.
 *
 * Because the shell stays alive, cd/env/exports/variables survive between
 * commands - the same behaviour Console Manager gets from GNU screen, without
 * needing screen.
 *
 * Each command is followed by a unique marker echo; the reader stops at the
 * marker, which is how output boundaries are known without a terminal emulator.
 */
public final class ShellSession {

    public interface Progress {
        void onStep(String step, int percent);
    }

    private static final int MAX_LINES = 800;

    private final String id;
    private final String name;
    private final String shell;
    private final Map<String, String> env;
    private final java.io.File cwd;
    private final List<String> lines = new ArrayList<String>();
    private final Object lock = new Object();

    private Process process;
    private OutputStream stdin;
    private volatile boolean alive;
    private volatile String lastError;
    private long createdAt;
    private long lastUsedAt;
    private int commands;
    private volatile String pendingMarker;
    private volatile String lastOutput;
    private volatile StringBuilder pending = new StringBuilder();

    public ShellSession(String id, String name, String shell, Map<String, String> env,
                        java.io.File cwd) {
        this.id = id;
        this.name = name;
        this.shell = shell;
        this.env = env;
        this.cwd = cwd;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = this.createdAt;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isAlive() {
        return alive && process != null && isRunning(process);
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastUsedAt() {
        return lastUsedAt;
    }

    public int commands() {
        return commands;
    }

    public String lastError() {
        return lastError;
    }

    public void start() throws IOException {
        shutdown();
        ProcessBuilder pb = new ProcessBuilder(shell, "-i");
        pb.directory(cwd);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        process = pb.start();
        stdin = process.getOutputStream();
        alive = true;
        lastError = null;
        append("Nishgamerr console \"" + name + "\" (ash) started.");
        Thread reader = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "console-" + id);
        reader.setDaemon(true);
        reader.start();
        // one marker round-trip so the prompt noise is out of the way
        exec("export PS1='$ '");
    }

    private void readLoop() {
        try {
            InputStream in = process.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                String chunk = new String(buf, 0, n, "UTF-8");
                synchronized (lock) {
                    pending.append(chunk);
                    String marker = pendingMarker;
                    if (marker != null) {
                        int at = pending.indexOf(marker);
                        if (at >= 0) {
                            String out = pending.substring(0, at);
                            pending.setLength(0);
                            pendingMarker = null;
                            lastOutput = out;
                            appendLines(out);
                            lock.notifyAll();
                            continue;
                        }
                    }
                    // no marker pending: this is unsolicited output (tail -f etc.)
                    if (marker == null) {
                        appendLines(pending.toString());
                        pending.setLength(0);
                    }
                }
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        } finally {
            alive = false;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /** Runs one command and returns only the output it produced. */
    public String exec(String command) {
        if (!isAlive()) {
            try {
                start();
            } catch (IOException e) {
                return "console is not running: " + e.getMessage();
            }
        }
        String marker = "__NISH_DONE_" + System.nanoTime() + "__";
        synchronized (lock) {
            lastOutput = null;
            try {
                stdin.write((command + "\n").getBytes("UTF-8"));
                stdin.write(("echo " + marker + "\n").getBytes("UTF-8"));
                stdin.flush();
                pendingMarker = marker;
                long deadline = System.currentTimeMillis() + 20000L;
                while (pendingMarker != null && System.currentTimeMillis() < deadline) {
                    lock.wait(120L);
                }
                if (pendingMarker != null) {
                    // timed out: take what we have and drop the marker
                    String partial = pending.toString();
                    pending.setLength(0);
                    pendingMarker = null;
                    appendLines(partial);
                    lastUsedAt = System.currentTimeMillis();
                    commands++;
                    return (partial.length() == 0 ? "" : partial.trim())
                            + "\n[still running after 20 s]";
                }
            } catch (Exception e) {
                lastError = e.toString();
                return "console error: " + e.getMessage();
            }
            lastUsedAt = System.currentTimeMillis();
            commands++;
            String out = lastOutput;
            lastOutput = null;
            return out == null ? "" : out;
        }
    }

    /** Last commands' output, newest last, as one string. */
    public String tail(int maxLines) {
        synchronized (lock) {
            int from = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i));
                if (i < lines.size() - 1) sb.append('\n');
            }
            return sb.toString();
        }
    }

    public int lineCount() {
        synchronized (lock) {
            return lines.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            lines.clear();
        }
    }

    public void shutdown() {
        alive = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }
        process = null;
        stdin = null;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void append(String line) {
        appendLines(line);
    }

    private void appendLines(String text) {
        if (text == null || text.length() == 0) return;
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalised.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String line = parts[i];
            // strip a bare trailing empty line from a chunk, keep real ones
            if (line.length() == 0 && i == parts.length - 1) continue;
            if (line.startsWith("bash-") || line.indexOf("/usr/bin/sh:") == 0) continue;
            lines.add(line.length() > 4000 ? line.substring(0, 4000) + "…" : line);
        }
        while (lines.size() > MAX_LINES) lines.remove(0);
    }

    private static boolean isRunning(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
}

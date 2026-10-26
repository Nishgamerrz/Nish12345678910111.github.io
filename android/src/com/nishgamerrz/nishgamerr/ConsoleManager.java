package com.nishgamerrz.nishgamerr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named consoles, like the ones Console Manager creates - but each one is a
 * live ash process from the bundled BusyBox, so cwd/env persist between
 * commands and several consoles can be used at the same time.
 *
 * Console names are saved next to the shell so they survive app restarts.
 */
public final class ConsoleManager {

    public static final String DEFAULT_ID = "main";

    private final Map<String, ShellSession> sessions = new LinkedHashMap<String, ShellSession>();
    private final ShellRuntime runtime;
    private final File namesFile;
    private volatile String lastError;

    public ConsoleManager(ShellRuntime runtime) {
        this.runtime = runtime;
        this.namesFile = new File(runtime.getRoot(), "consoles/consoles.txt");
        load();
        if (sessions.isEmpty()) {
            create("main", "Main console");
        }
    }

    /* ── persistence of the console list (not the processes) ─────────────── */

    private void load() {
        if (!namesFile.isFile()) return;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(namesFile), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String id = line.substring(0, tab).trim();
                String name = line.substring(tab + 1).trim();
                if (id.length() == 0 || name.length() == 0) continue;
                sessions.put(id, new ShellSession(id, name, runtime.starter(),
                        runtime.environment(), runtime.getHome()));
            }
            r.close();
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            File dir = namesFile.getParentFile();
            if (!dir.isDirectory()) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (ShellSession s : sessions.values()) {
                sb.append(s.id()).append('\t').append(s.name()).append('\n');
            }
            FileOutputStream out = new FileOutputStream(namesFile);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /* ── consoles ────────────────────────────────────────────────────────── */

    /** The shell runtime this manager drives (PATH, prefix, home). */
    public ShellRuntime runtime() {
        return runtime;
    }

    /** Where consoles, the saved style and the shell itself live. */
    public java.io.File root() {
        return runtime.getRoot();
    }

    public synchronized List<ShellSession> list() {
        return new ArrayList<ShellSession>(sessions.values());
    }

    public synchronized ShellSession get(String id) {
        ShellSession s = sessions.get(id);
        if (s == null) s = sessions.get(DEFAULT_ID);
        if (s == null && !sessions.isEmpty()) s = sessions.values().iterator().next();
        return s;
    }

    /** The reference dashboard addresses consoles by name, not by id. */
    public synchronized ShellSession byName(String name) {
        if (name == null) return null;
        for (ShellSession s : sessions.values()) {
            if (s.name().equals(name)) return s;
        }
        for (ShellSession s : sessions.values()) {
            if (s.name().equalsIgnoreCase(name)) return s;
        }
        for (ShellSession s : sessions.values()) {
            if (s.id().equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    public synchronized boolean removeByName(String name) {
        ShellSession s = byName(name);
        return s != null && remove(s.id());
    }

    public synchronized int size() {
        return sessions.size();
    }

    public static String sanitiseName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() == 0) name = "console";
        if (name.length() > 24) name = name.substring(0, 24);
        // letters, digits, spaces, dash, underscore, dot only
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        return clean.length() == 0 ? "console" : clean;
    }

    public synchronized ShellSession create(String requestedId, String requestedName) {
        String base = requestedId == null || requestedId.trim().length() == 0
                ? slug(requestedName) : slug(requestedId);
        if (base.length() == 0) base = "console";
        String id = base;
        int n = 2;
        while (sessions.containsKey(id)) {
            id = base + "-" + n;
            n++;
        }
        String name = sanitiseName(requestedName == null || requestedName.trim().length() == 0
                ? id : requestedName);
        ShellSession s = new ShellSession(id, name, runtime.starter(),
                runtime.environment(), runtime.getHome());
        sessions.put(id, s);
        save();
        Thread starter = new Thread(new Runnable() {
            public void run() {
                // sessions start lazily on first use, this just warms the process
            }
        });
        starter.setDaemon(true);
        return s;
    }

    public synchronized boolean remove(String id) {
        if (sessions.size() <= 1) {
            lastError = "Keep at least one console";
            return false;
        }
        ShellSession s = sessions.remove(id);
        if (s == null) return false;
        s.shutdown();
        save();
        return true;
    }

    /** Run a command in one console and return its output. */
    public String run(String id, String command) {
        ShellSession s = get(id);
        if (s == null) return "no console available";
        String out = s.exec(command);
        return out;
    }

    public String tail(String id, int lines) {
        ShellSession s = get(id);
        return s == null ? "" : s.tail(lines);
    }

    public void clear(String id) {
        ShellSession s = get(id);
        if (s != null) s.clear();
    }

    public synchronized void shutdownAll() {
        for (ShellSession s : sessions.values()) s.shutdown();
        sessions.clear();
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        for (ShellSession s : list()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.name()).append(s.isAlive() ? "" : " (idle)");
        }
        return sb.toString();
    }

    private static String slug(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean dash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                dash = false;
            } else if (!dash && sb.length() > 0) {
                sb.append('-');
                dash = true;
            }
        }
        return sb.toString();
    }
}

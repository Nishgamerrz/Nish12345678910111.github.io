package com.nishgamerrz.nishgamerr;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dashboard server that lives inside the APK.
 *
 * No Termux, no Python, no external process: a plain HTTP/1.1 server on a
 * ServerSocket, one thread per request, serving a password-protected console.
 *
 * Pure Java (no android.* imports) so the whole thing can be started and
 * exercised on a desktop JVM by tools/test-server.sh.
 */
public final class WebServer implements Runnable {

    public interface Listener {
        /** Called when the listening state changes; error may be null. */
        void onState(boolean running, String error);

        /** Called for every handled request, for the app's device list. */
        void onRequest(String ip, String agent, String path);
    }

    private static final long SESSION_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_BODY = 16 * 1024;
    private static final int MAX_FAILS = 8;
    private static final long FAIL_WINDOW_MS = 5L * 60L * 1000L;

    private final int port;
    private volatile Auth auth;
    private final DeviceLog devices;
    private final ConsoleManager consoles;
    private volatile String shellVersion;
    private final String hostName;
    private final String version;
    private final String deviceModel;
    private final String androidVersion;
    private final Listener listener;

    /** Optional: only the updater and the version label need an Android context. */
    private volatile android.content.Context appContext;
    private volatile String theme = Dashboard.DEFAULT_THEME;
    private java.io.File themeFile;

    private volatile ServerSocket socket;
    private volatile boolean running;
    private volatile String lastError;
    private volatile long startedAt;

    private final Map<String, Long> sessions = new ConcurrentHashMap<String, Long>();
    private final Map<String, long[]> failures = new ConcurrentHashMap<String, long[]>();

    public void setContext(android.content.Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
        if (this.appContext != null) theme = Prefs.theme(this.appContext);
    }

    /** The saved style lives next to the consoles, exactly like preferences.json. */
    private void loadTheme() {
        if (themeFile == null) themeFile = new java.io.File(consoles.root(), "preferences.json");
        theme = readTheme(themeFile);
    }

    private static String readTheme(java.io.File file) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            in.close();
            String text = new String(out.toByteArray(), "UTF-8");
            int at = text.indexOf("\"theme\"");
            if (at < 0) return Dashboard.DEFAULT_THEME;
            int colon = text.indexOf(':', at);
            int open = text.indexOf('"', colon + 1);
            int close = text.indexOf('"', open + 1);
            String value = text.substring(open + 1, close);
            return Dashboard.isTheme(value) ? value : Dashboard.DEFAULT_THEME;
        } catch (Exception ignored) {
            return Dashboard.DEFAULT_THEME;
        }
    }

    private String saveTheme(String wanted) {
        if (!Dashboard.isTheme(wanted)) return null;
        theme = wanted;
        if (appContext != null) Prefs.setTheme(appContext, wanted);
        try {
            if (themeFile == null) themeFile = new java.io.File(consoles.root(), "preferences.json");
            java.io.File tmp = new java.io.File(themeFile.getPath() + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            out.write(("{\n  \"theme\": \"" + wanted + "\"\n}\n").getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(themeFile)) {
                java.io.FileOutputStream direct = new java.io.FileOutputStream(themeFile);
                direct.write(("{\n  \"theme\": \"" + wanted + "\"\n}\n").getBytes("UTF-8"));
                direct.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
        return wanted;
    }

    /** The shell installs in the background, long after the socket is up. */
    public void setShellVersion(String version) {
        this.shellVersion = version == null ? "" : version;
    }

    public WebServer(int port, Auth auth, DeviceLog devices, ConsoleManager consoles,
                     String hostName, String version, String deviceModel,
                     String androidVersion, String shellVersion, Listener listener) {
        this.port = port;
        this.auth = auth;
        this.devices = devices;
        this.consoles = consoles;
        this.shellVersion = shellVersion == null ? "" : shellVersion;
        this.hostName = hostName;
        this.version = version;
        this.deviceModel = deviceModel;
        this.androidVersion = androidVersion;
        this.listener = listener;
    }

    /* ── lifecycle ───────────────────────────────────────────────────────── */

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        // Bind on all interfaces: phones, laptops and tablets on the Wi-Fi.
        s.bind(new InetSocketAddress((InetAddress) null, port), 32);
        socket = s;
        running = true;
        lastError = null;
        startedAt = System.currentTimeMillis();
        Thread t = new Thread(this, "relay-http");
        t.setDaemon(true);
        t.start();
        if (listener != null) listener.onState(true, null);
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        sessions.clear();
        if (listener != null) listener.onState(false, null);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public String getLastError() {
        return lastError;
    }

    public long uptimeMs() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    public int sessionCount() {
        pruneSessions();
        return sessions.size();
    }

    public void revokeSessions() {
        sessions.clear();
    }

    /** Swap the password verifier at runtime; everyone has to sign in again. */
    public void setAuth(Auth replacement) {
        this.auth = replacement;
        revokeSessions();
        failures.clear();
    }

    public void run() {
        while (running) {
            Socket client = null;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) {
                    lastError = "accept failed: " + e.getMessage();
                    if (listener != null) listener.onState(false, lastError);
                }
                break;
            }
            final Socket c = client;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    handle(c);
                }
            }, "relay-req");
            t.setDaemon(true);
            t.start();
        }
        if (running) {
            running = false;
            if (listener != null) listener.onState(false, lastError);
        }
    }

    /* ── request handling ────────────────────────────────────────────────── */

    private void handle(Socket c) {
        String ip = "?";
        try {
            c.setSoTimeout(15000);
            ip = c.getInetAddress() == null ? "?" : c.getInetAddress().getHostAddress();
            if (ip.indexOf(':') >= 0 && ip.indexOf("::ffff:") == 0) {
                ip = ip.substring(7);
            }
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.length() == 0) {
                writeSimple(out, 400, "text/plain", "bad request");
                return;
            }
            String[] first = requestLine.split(" ");
            if (first.length < 2) {
                writeSimple(out, 400, "text/plain", "bad request");
                return;
            }
            String method = first[0];
            String target = first[1];

            Map<String, String> headers = new HashMap<String, String>();
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && line.length() > 0 && guard++ < 64) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            String body = "";
            String lenHeader = headers.get("content-length");
            if (lenHeader != null) {
                int len = 0;
                try {
                    len = Integer.parseInt(lenHeader.trim());
                } catch (NumberFormatException ignored) {
                }
                if (len > MAX_BODY) {
                    writeSimple(out, 413, "text/plain", "request too large");
                    return;
                }
                body = readBody(in, len);
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            String agent = headers.get("user-agent");

            if (listener != null) listener.onRequest(ip, agent, path);
            route(out, method, path, query, headers, body, ip, agent);
        } catch (Exception e) {
            // A half-open connection from a phone going to sleep is normal.
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(OutputStream out, String method, String path, String query,
                       Map<String, String> headers, String body, String ip,
                       String agent) throws IOException {
        String token = cookie(headers.get("cookie"), "rd_session");
        boolean authed = token != null && isSessionValid(token);
        boolean isGet = "GET".equals(method);
        boolean isPost = "POST".equals(method);

        // favicon: browsers always ask, and a 204 keeps the device list clean
        if ("/favicon.ico".equals(path)) {
            writeSimple(out, 204, "text/plain", "");
            return;
        }

        if (isPost && "/login".equals(path)) {
            handleLogin(out, body, ip, agent);
            return;
        }

        if (isPost && "/logout".equals(path)) {
            if (token != null) sessions.remove(token);
            devices.touch(ip, agent, "/logout");
            writeRedirect(out, "/", token == null ? null : "rd_session=; Path=/; Max-Age=0; HttpOnly");
            return;
        }

        if (!authed) {
            if (isPost || path.startsWith("/api/")) {
                writeJson(out, 401, "{\"ok\":false,\"error\":\"not signed in\"}");
                return;
            }
            if ("/".equals(path)) {
                devices.touch(ip, agent, "/ (login page)");
                writeHtml(out, 200, Pages.login(hostName, null, !auth.isConfigured(), version));
                return;
            }
            writeRedirect(out, "/", null);
            return;
        }

        // ── authenticated ──
        if (isGet && ("/".equals(path) || "/index.html".equals(path))) {
            devices.touch(ip, agent, "/");
            loadTheme();
            writeHtml(out, 200, Dashboard.html(theme));
            return;
        }

        if (isGet && "/status".equals(path)) {
            // the older status page is still one click away
            devices.touch(ip, agent, "/status");
            String selected = parseForm(query).get("c");
            writeHtml(out, 200, Pages.dashboard(hostName, version, url(), uptimeMs(),
                    port, deviceModel, androidVersion, devices.snapshot(),
                    System.currentTimeMillis(), auth.isConfigured(), consoles, selected,
                    shellVersion));
            return;
        }

        /* ── the console manager dashboard's own API ─────────────────────── */

        if (isGet && "/api/state".equals(path)) {
            devices.touch(ip, agent, "/api/state");
            loadTheme();
            writeJson(out, 200, stateJson());
            return;
        }

        if (isPost && "/api/preferences".equals(path)) {
            Map<String, String> form = parseForm(body);
            String saved = saveTheme(form.get("theme"));
            if (saved == null) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Unknow style. Choose one of the available themes.\"}");
                return;
            }
            writeJson(out, 200, "{\"ok\":true,\"preferences\":{\"theme\":\"" + json(saved) + "\"}}");
            return;
        }

        if (isPost && "/api/create".equals(path)) {
            String name = parseForm(body).get("name");
            String clean = consoleName(name);
            if (clean == null) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"" +
                        json("Use 1-32 letters, numbers, underscores or hyphens; start with a letter or number.") + "\"}");
                return;
            }
            if (consoles.byName(clean) != null) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"A console with that name already exists.\"}");
                return;
            }
            if (consoles.size() >= 8) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"8 consoles is the limit.\"}");
                return;
            }
            consoles.create(clean, clean);
            devices.touch(ip, agent, "/api/create");
            writeJson(out, 200, "{\"ok\":true,\"name\":\"" + json(clean) + "\"}");
            return;
        }

        if (isPost && "/api/delete".equals(path)) {
            String name = parseForm(body).get("name");
            String clean = consoleName(name);
            if (clean == null) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Unknown console.\"}");
                return;
            }
            if (!consoles.removeByName(clean)) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Keep at least one console.\"}");
                return;
            }
            devices.touch(ip, agent, "/api/delete");
            writeJson(out, 200, "{\"ok\":true}");
            return;
        }

        if (isPost && "/api/execute".equals(path)) {
            Map<String, String> form = parseForm(body);
            String name = form.get("name");
            String command = form.get("command");
            String clean = consoleName(name);
            ShellSession session = clean == null ? null : consoles.byName(clean);
            if (session == null) {
                writeJson(out, 404, "{\"ok\":false,\"error\":\"Console not found.\"}");
                return;
            }
            if (command == null || command.trim().length() == 0) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Enter a command first.\"}");
                return;
            }
            if (command.length() > 4000) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Command is too long (4000 characters maximum).\"}");
                return;
            }
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Enter one command line at a time.\"}");
                return;
            }
            devices.touch(ip, agent, "/api/execute");
            runAsync(session, command);
            writeJson(out, 200, "{\"ok\":true}");
            return;
        }

        if (isGet && "/api/suggest".equals(path)) {
            Map<String, String> q = parseForm(query);
            String name = q.get("name");
            String query2 = q.get("q");
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"suggestions\":[");
            List<String> suggestions = suggestions(name, query2);
            for (int i = 0; i < suggestions.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(json(suggestions.get(i))).append('"');
            }
            sb.append("]}");
            writeJson(out, 200, sb.toString());
            return;
        }

        if (isGet && "/api/update".equals(path)) {
            writeJson(out, 200, updateJson());
            return;
        }

        if (isPost && "/api/update/check".equals(path)) {
            android.content.Context context = appContext;
            if (context != null) Updater.check(context, true);
            writeJson(out, 200, updateJson());
            return;
        }

        if (isPost && "/api/update/install".equals(path)) {
            android.content.Context context = appContext;
            boolean started = context != null && Updater.install(context);
            writeJson(out, started ? 200 : 400,
                    started ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"no downloaded update\"}");
            return;
        }

        if (isGet && "/api/output".equals(path) && parseForm(query).containsKey("name")) {
            Map<String, String> q = parseForm(query);
            String name = q.get("name");
            String clean = consoleName(name);
            ShellSession session = clean == null ? null : consoles.byName(clean);
            if (session == null) {
                writeJson(out, 404, "{\"ok\":false,\"error\":\"Console not found or its output is unavailable.\"}");
                return;
            }
            Thread.yield();
            String text = session.tail(4000);
            writeJson(out, 200, "{\"ok\":true,\"name\":\"" + json(session.name())
                    + "\",\"output\":\"" + json(text)
                    + "\",\"truncated\":" + (text.length() > 200000) + "}");
            return;
        }

        if (isGet && "/api/output".equals(path)) {
            Map<String, String> q = parseForm(query);
            String id = q.get("id");
            int lines = 400;
            try {
                lines = Integer.parseInt(q.get("lines") == null ? "400" : q.get("lines"));
            } catch (NumberFormatException ignored) {
            }
            if (lines < 20) lines = 20;
            if (lines > 800) lines = 800;
            String tail = consoles.tail(id, lines);
            writeJson(out, 200, "{\"ok\":true,\"id\":\"" + json(consoles.get(id) == null
                    ? ConsoleManager.DEFAULT_ID : consoles.get(id).id())
                    + "\",\"tail\":\"" + json(tail) + "\"}");
            return;
        }

        if (isPost && "/api/console/new".equals(path)) {
            Map<String, String> form = parseForm(body);
            String name = form.get("name");
            if (name == null || name.trim().length() == 0) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"give the console a name\"}");
                return;
            }
            if (consoles.size() >= 8) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"8 consoles is the limit\"}");
                return;
            }
            ShellSession created = consoles.create(null, name);
            devices.touch(ip, agent, "/api/console/new");
            writeJson(out, 200, "{\"ok\":true,\"id\":\"" + json(created.id())
                    + "\",\"name\":\"" + json(created.name()) + "\"}");
            return;
        }

        if (isPost && "/api/console/delete".equals(path)) {
            Map<String, String> form = parseForm(body);
            boolean done = consoles.remove(form.get("id"));
            devices.touch(ip, agent, "/api/console/delete");
            if (!done) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"" +
                        json(consoles.size() <= 1 ? "Keep at least one console"
                                : "no such console") + "\"}");
                return;
            }
            writeJson(out, 200, "{\"ok\":true}");
            return;
        }

        if (isPost && "/api/console/clear".equals(path)) {
            Map<String, String> form = parseForm(body);
            consoles.clear(form.get("id"));
            writeJson(out, 200, "{\"ok\":true}");
            return;
        }

        if (isGet && "/api/ping".equals(path)) {
            devices.touch(ip, agent, "/api/ping");
            writeJson(out, 200, "{\"ok\":true,\"status\":\"running\",\"uptime_ms\":" + uptimeMs() + "}");
            return;
        }

        if (isGet && "/api/status".equals(path)) {
            devices.touch(ip, agent, "/api/status");
            writeJson(out, 200, statusJson());
            return;
        }

        if (isGet && "/api/devices".equals(path)) {
            devices.touch(ip, agent, "/api/devices");
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"devices\":[");
            List<DeviceLog.Entry> list = devices.snapshot();
            for (int i = 0; i < list.size(); i++) {
                DeviceLog.Entry e = list.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"ip\":\"").append(json(e.ip))
                  .append("\",\"agent\":\"").append(json(e.agent))
                  .append("\",\"path\":\"").append(json(e.path))
                  .append("\",\"hits\":").append(e.hits)
                  .append(",\"last_seen_ms\":").append(System.currentTimeMillis() - e.lastSeen)
                  .append('}');
            }
            sb.append("]}");
            devices.touch(ip, agent, "/api/devices");
            writeJson(out, 200, sb.toString());
            return;
        }

        if (isPost && "/api/run".equals(path)) {
            Map<String, String> form = parseForm(body);
            String cmd = form.get("cmd");
            String consoleId = form.get("console");
            if (cmd == null || cmd.trim().length() == 0) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"empty command\"}");
                return;
            }
            ShellSession session = consoles.get(consoleId);
            devices.touch(ip, agent, "/api/run");
            String output = consoles.run(consoleId, cmd);
            String tail = consoles.tail(consoleId, 400);
            writeJson(out, 200, "{\"ok\":true,\"console\":\"" +
                    json(session == null ? ConsoleManager.DEFAULT_ID : session.id())
                    + "\",\"output\":\"" + json(output)
                    + "\",\"tail\":\"" + json(tail) + "\"}");
            return;
        }

        if (isGet && "/healthz".equals(path)) {
            writeSimple(out, 200, "text/plain", "ok");
            return;
        }

        writeHtml(out, 404, Pages.notFound(hostName, version));
    }

    private void handleLogin(OutputStream out, String body, String ip, String agent)
            throws IOException {
        if (!auth.isConfigured()) {
            devices.touch(ip, agent, "/login (no password set)");
            writeHtml(out, 403, Pages.login(hostName,
                    "No password is set on the TV yet. Open the app and set one.",
                    true, version));
            return;
        }
        if (isLockedOut(ip)) {
            writeHtml(out, 429, Pages.login(hostName,
                    "Too many wrong passwords from this device. Wait five minutes.",
                    false, version));
            return;
        }
        Map<String, String> form = parseForm(body);
        String given = form.get("password");
        if (given != null && auth.check(given)) {
            String token = Auth.newToken();
            sessions.put(token, System.currentTimeMillis() + SESSION_TTL_MS);
            failures.remove(ip);
            devices.touch(ip, agent, "/login (signed in)");
            writeRedirect(out, "/", "rd_session=" + token + "; Path=/; Max-Age=43200; HttpOnly; SameSite=Lax");
            return;
        }
        noteFailure(ip);
        devices.touch(ip, agent, "/login (wrong password)");
        writeHtml(out, 401, Pages.login(hostName, "Wrong password. Try again.",
                false, version));
    }

    /* ── sessions / throttling ───────────────────────────────────────────── */

    private boolean isSessionValid(String token) {
        Long expiry = sessions.get(token);
        if (expiry == null) return false;
        if (expiry < System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    private void pruneSessions() {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<String>();
        for (Map.Entry<String, Long> e : sessions.entrySet()) {
            if (e.getValue() < now) dead.add(e.getKey());
        }
        for (int i = 0; i < dead.size(); i++) sessions.remove(dead.get(i));
    }

    private boolean isLockedOut(String ip) {
        long[] rec = failures.get(ip);
        if (rec == null) return false;
        if (System.currentTimeMillis() - rec[1] > FAIL_WINDOW_MS) {
            failures.remove(ip);
            return false;
        }
        return rec[0] >= MAX_FAILS;
    }

    private void noteFailure(String ip) {
        long now = System.currentTimeMillis();
        long[] rec = failures.get(ip);
        if (rec == null || now - rec[1] > FAIL_WINDOW_MS) {
            failures.put(ip, new long[]{1L, now});
        } else {
            rec[0] = rec[0] + 1;
            rec[1] = now;
        }
    }

    /* ── info ────────────────────────────────────────────────────────────── */

    public String url() {
        return "http://" + lanAddress() + ":" + port;
    }


    /* ── the console manager dashboard's data ────────────────────────────── */

    /** 1-32 letters, numbers, _ or -, starting with a letter or number. */
    private static String consoleName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.length() == 0 || name.length() > 32) return null;
        if (!Character.isLetterOrDigit(name.charAt(0))) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') return null;
        }
        return name;
    }

    private String stateJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"consoles\":[");
        List<ShellSession> list = consoles.list();
        for (int i = 0; i < list.size(); i++) {
            ShellSession s = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(json(s.name()))
              .append("\",\"session\":\"ac_").append(json(s.name()))
              .append("\",\"pid\":").append(s.commands() > 0 ? 1 : 0)
              .append(",\"attached\":").append(false)
              .append(",\"dead\":").append(!s.isAlive() && s.commands() > 0)
              .append(",\"commands\":").append(s.commands()).append('}');
        }
        sb.append("],\"stats\":").append(statsJson())
          .append(",\"preferences\":{\"theme\":\"").append(json(theme)).append("\"}")
          .append(",\"themes\":[");
        for (int i = 0; i < Dashboard.THEMES.length; i++) {
            String[] t = Dashboard.THEMES[i];
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(t[0]).append("\",\"name\":\"").append(json(t[1]))
              .append("\",\"description\":\"").append(json(t[2])).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** load average, memory and disk - what the top of the dashboard shows. */
    private String statsJson() {
        String load = "N/A";
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) load = parts[0] + " " + parts[1] + " " + parts[2];
            }
        } catch (Exception ignored) {
        }
        long memTotal = 0;
        long memFree = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) memTotal = kb(line);
                else if (line.startsWith("MemAvailable:")) memFree = kb(line);
            }
            reader.close();
        } catch (Exception ignored) {
        }
        long used = memTotal > memFree ? memTotal - memFree : 0;
        int memPercent = memTotal > 0 ? (int) Math.round(used * 100.0 / memTotal) : 0;
        long diskTotal = 0;
        long diskUsed = 0;
        try {
            java.io.File home = consoles.root();
            java.nio.file.FileStore store = java.nio.file.Files.getFileStore(home.toPath());
            diskTotal = store.getTotalSpace();
            diskUsed = diskTotal - store.getUsableSpace();
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder("{\"load\":\"").append(json(load)).append("\",");
        sb.append("\"memory\":{\"used\":").append(used)
          .append(",\"total\":").append(memTotal)
          .append(",\"percent\":").append(memPercent).append("},");
        sb.append("\"disk\":{\"used\":\"").append(json(bytes(diskUsed)))
          .append("\",\"total\":\"").append(json(bytes(diskTotal)))
          .append("\",\"percent\":\"").append(diskTotal > 0
                  ? Math.round(diskUsed * 100.0 / diskTotal) + "%" : "0%")
          .append("\"}}");
        return sb.toString();
    }

    private static long kb(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return 0;
        try {
            return Long.parseLong(parts[1]) / 1024;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String bytes(long value) {
        if (value <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = value;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.US, unit == 0 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }

    /** Commands the dashboard offers while typing: this console's history first. */
    private List<String> suggestions(String name, String query) {
        List<String> out = new ArrayList<String>();
        if (query == null || query.length() == 0 || query.length() > 80) return out;
        String lowered = query.toLowerCase();
        ShellSession session = consoleName(name) == null ? null : consoles.byName(name);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        if (session != null) {
            String[] past = session.tail(400).split("\\n");
            for (int i = past.length - 1; i >= 0 && out.size() < 12; i--) {
                String line = past[i].trim();
                if (!line.startsWith("$ ")) continue;
                String command = line.substring(2).trim();
                if (command.length() == 0) continue;
                if (command.toLowerCase().startsWith(lowered) && seen.add(command)) out.add(command);
            }
        }
        for (String command : consoleCommands()) {
            if (out.size() >= 12) break;
            if (command.toLowerCase().startsWith(lowered) && seen.add(command)) out.add(command);
        }
        return out;
    }

    /** Everything on the shell's PATH, cached for a minute. */
    private volatile List<String> commandsCache;
    private volatile long commandsCacheAt;

    private List<String> consoleCommands() {
        long now = System.currentTimeMillis();
        List<String> cached = commandsCache;
        if (cached != null && now - commandsCacheAt < 60000L) return cached;
        java.util.TreeSet<String> found = new java.util.TreeSet<String>();
        try {
            ConsoleManager manager = consoles;
            String path = manager.runtime().environment().get("PATH");
            if (path != null) {
                for (String folder : path.split(":")) {
                    if (folder.length() == 0) continue;
                    java.io.File[] entries = new java.io.File(folder).listFiles();
                    if (entries == null) continue;
                    for (java.io.File entry : entries) {
                        if (entry.isFile() && entry.canExecute()) found.add(entry.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<String> list = new ArrayList<String>(found);
        commandsCache = list;
        commandsCacheAt = now;
        return list;
    }

    /** Execute without blocking the HTTP reply: the dashboard polls the output. */
    private void runAsync(final ShellSession session, final String command) {
        session.echo(command);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    session.exec(command);
                } catch (Throwable ignored) {
                }
            }
        }, "console-" + session.id());
        worker.setDaemon(true);
        worker.start();
    }

    private String updateJson() {
        String current = version;
        android.content.Context context = appContext;
        if (context != null) current = Prefs.versionName(context);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"").append(json(UpdateState.state()))
          .append("\",\"current\":\"").append(json(current))
          .append("\",\"latest\":\"").append(json(UpdateState.latest()))
          .append("\",\"percent\":").append(UpdateState.percent())
          .append(",\"error\":\"").append(json(UpdateState.error()))
          .append("\",\"checked_at\":").append(UpdateState.checkedAt()).append('}');
        return sb.toString();
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"status\":\"").append(running ? "running" : "stopped")
          .append("\",\"port\":").append(port)
          .append(",\"url\":\"").append(json(url()))
          .append("\",\"uptime_ms\":").append(uptimeMs())
          .append(",\"devices\":").append(devices.size())
          .append(",\"sessions\":").append(sessionCount())
          .append(",\"consoles\":").append(consoles.size())
          .append(",\"shell\":\"").append(json(shellVersion)).append("\"")
          .append(",\"password_set\":").append(auth.isConfigured())
          .append(",\"host\":\"").append(json(deviceModel))
          .append("\",\"android\":\"").append(json(androidVersion))
          .append("\",\"version\":\"").append(json(version)).append("\"}");
        return sb.toString();
    }

    /** First non-loopback IPv4 address: the one other devices should use. */
    public static String lanAddress() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = Collections.list(ifaces);
            // wlan0 first: on a TV that is the Wi-Fi the phone will use
            Collections.sort(list, new java.util.Comparator<NetworkInterface>() {
                public int compare(NetworkInterface a, NetworkInterface b) {
                    return rank(a) - rank(b);
                }

                private int rank(NetworkInterface i) {
                    String n = i.getName() == null ? "" : i.getName();
                    if (n.startsWith("wlan")) return 0;
                    if (n.startsWith("eth")) return 1;
                    if (n.startsWith("ap")) return 2;
                    return 3;
                }
            });
            for (int i = 0; i < list.size(); i++) {
                NetworkInterface nif = list.get(i);
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String host = a.getHostAddress();
                    if (host == null || host.indexOf(':') >= 0) continue; // IPv6 for now
                    if (host.startsWith("169.254.")) {
                        if (fallback.length() == 0) fallback = host;
                        continue;
                    }
                    return host;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback.length() > 0 ? fallback : "127.0.0.1";
    }

    /* ── HTTP plumbing ───────────────────────────────────────────────────── */

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(96);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                String s = buf.toString("ISO-8859-1");
                if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
                return s;
            }
            if (buf.size() > 8192) return null;
            buf.write(c);
        }
        return buf.size() == 0 ? null : buf.toString("ISO-8859-1");
    }

    private static String readBody(InputStream in, int length) throws IOException {
        if (length <= 0) return "";
        byte[] raw = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(raw, read, length - read);
            if (n <= 0) break;
            read += n;
        }
        return new String(raw, 0, read, "UTF-8");
    }

    private static void writeHtml(OutputStream out, int code, String html) throws IOException {
        write(out, code, "text/html; charset=utf-8", html);
    }

    private static void writeJson(OutputStream out, int code, String json) throws IOException {
        write(out, code, "application/json; charset=utf-8", json);
    }

    private static void writeSimple(OutputStream out, int code, String type, String body)
            throws IOException {
        write(out, code, type, body);
    }

    private static void write(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        out.write(bytes);
        out.flush();
    }

    private static void writeRedirect(OutputStream out, String location, String cookie)
            throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 303 See Other\r\n");
        h.append("Location: ").append(location).append("\r\n");
        if (cookie != null) h.append("Set-Cookie: ").append(cookie).append("\r\n");
        h.append("Content-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    public static String cookie(String header, String name) {
        if (header == null) return null;
        String[] parts = header.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equals(name)) {
                return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<String, String>();
        if (body == null || body.length() == 0) return map;
        String[] pairs = body.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0) {
                map.put(urlDecode(pairs[i]), "");
            } else {
                map.put(urlDecode(pairs[i].substring(0, eq)), urlDecode(pairs[i].substring(eq + 1)));
            }
        }
        return map;
    }

    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String json(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case '&': sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}

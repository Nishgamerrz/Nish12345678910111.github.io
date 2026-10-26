package com.nishgamerrz.nishgamerr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A real Termux userland, inside this app.
 *
 * The official Termux bootstrap is a zip of bash, coreutils, dash, tar, xz,
 * git, openssl, apt and the rest of the prefix. It is downloaded once, unpacked
 * into this app's own data directory, and *relocated*: every path that says
 * /data/data/com.termux/files/usr is rewritten to our prefix, because Android
 * will never let a second app own Termux's directory.
 *
 * Shared libraries are found through LD_LIBRARY_PATH (Termux binaries carry a
 * DT_RUNPATH that ld.so searches after it), so no ELF patching is needed.
 *
 * If any step fails the app keeps the bundled BusyBox userland it already has,
 * so a TV with a broken connection still gets a working shell.
 */
public final class TermuxRuntime {

    /**
     * Where the bootstrap comes from. The newest release that actually carries
     * an asset is asked for first (releases/latest can point at a release
     * without bootstraps at all, which is what silently failed on the TV), then
     * the stable redirect, then the exact tag we know ships every architecture.
     */
    private static final String RELEASES_API =
            "https://api.github.com/repos/termux/termux-packages/releases?per_page=10";
    private static final String LATEST =
            "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-";
    private static final String[] PINNED_TAGS = {
            "bootstrap-2026.10.25-r1%2Bapt.android-7",
            "bootstrap-2026.10.18-r1%2Bapt.android-7",
    };

    /** Where Termux itself lives inside every one of its own binaries. */
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    /** Written only after bash has actually been executed successfully. */
    public static final String READY_MARKER = "termux-ready";
    private static final String STAGE_MARKER = "termux-stage";

    private volatile String lastError = "";

    private final File root;        // <filesDir>/termux
    private final File prefix;      // <filesDir>/termux/usr
    private final File home;        // <filesDir>/termux/home
    private final String abi;
    private final ShellRuntime.Source assets;
    private final File busybox;

    public interface Progress {
        void onStep(String step, int percent);
    }

    public TermuxRuntime(File filesDir, String abi) {
        this(filesDir, abi, null, null);
    }

    public TermuxRuntime(File filesDir, String abi, ShellRuntime.Source assets, File busybox) {
        this.root = new File(filesDir, "termux");
        this.prefix = new File(root, "usr");
        this.home = new File(root, "home");
        this.abi = abi == null ? "" : abi;
        this.assets = assets;
        this.busybox = busybox;
    }

    public File getRoot() {
        return root;
    }

    /** Why the last attempt failed, in one line, for the TV and the dashboard. */
    public String lastError() {
        return lastError;
    }

    public File getPrefix() {
        return prefix;
    }

    public File getHome() {
        return home;
    }

    public File getBash() {
        return new File(prefix, "bin/bash");
    }

    /** True once bash has run at least once from this prefix. */
    public boolean isReady() {
        return getBash().isFile() && new File(root, READY_MARKER).isFile();
    }

    public String state() {
        if (isReady()) return "Termux " + bashVersion();
        if (new File(root, STAGE_MARKER).isFile()) return "Termux " + readStage();
        return "not installed";
    }

    private String readStage() {
        try {
            FileInputStream in = new FileInputStream(new File(root, STAGE_MARKER));
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            return n <= 0 ? "installing" : new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return "installing";
        }
    }

    private void stage(String text) {
        try {
            FileOutputStream out = new FileOutputStream(new File(root, STAGE_MARKER));
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** "aarch64" / "arm" / "x86_64" / "i686", the names Termux uses. */
    public static String archFor(String abi) {
        String value = abi == null ? "" : abi.toLowerCase();
        if (value.startsWith("arm64")) return "aarch64";
        if (value.startsWith("armeabi")) return "arm";
        if (value.startsWith("x86_64")) return "x86_64";
        if (value.startsWith("x86")) return "i686";
        return "aarch64";
    }

    /**
     * Download, unpack, relocate and prove it works.
     *
     * Returns true when bash is usable. Never throws: the caller falls back to
     * the bundled BusyBox on false.
     */
    public boolean install(Progress progress) {
        try {
            mkdirs(root);
            mkdirs(prefix);
            mkdirs(home);

            String arch = archFor(abi);
            File zip = new File(root, "bootstrap-" + arch + ".zip");

            if (!zip.isFile() || !looksLikeBootstrap(zip)) {
                progress.onStep("Downloading the Termux userland (" + arch + ")", 2);
                stage("downloading");
                downloadAny(arch, zip, progress);
            }
            if (!looksLikeBootstrap(zip)) {
                throw new IOException("the downloaded file is not a Termux bootstrap");
            }

            progress.onStep("Unpacking Termux", 55);
            stage("unpacking");
            wipePrefix();
            unpack(zip, prefix);
            File nested = new File(prefix, "usr");
            if (!new File(prefix, "bin/bash").isFile() && new File(nested, "bin/bash").isFile()) {
                // the zip carried the whole prefix inside usr/
                File[] children = nested.listFiles();
                if (children != null) {
                    for (File child : children) {
                        child.renameTo(new File(prefix, child.getName()));
                    }
                }
            }

            progress.onStep("Rewriting Termux paths for this app", 75);
            stage("relocating");
            relocate();

            progress.onStep("Checking bash", 90);
            stage("checking");
            writePkgShim();
            if (!probe()) {
                progress.onStep("Termux could not start here – keeping the bundled shell", 0);
                stage("failed");
                return false;
            }
            writeMarker();
            zip.delete();
            progress.onStep("Termux ready", 100);
            return true;
        } catch (Throwable e) {
            String why = describe(e);
            lastError = why;
            log("FAILED: " + why);
            progress.onStep("Termux install failed (" + why + ") – bundled shell stays", 0);
            stage("failed");
            return false;
        }
    }

    /* ── download ────────────────────────────────────────────────────────── */

    /** Tries every known source until one produces a real bootstrap zip. */
    private void downloadAny(String arch, File target, Progress progress) throws IOException {
        List<String> urls = candidateUrls(arch);
        String lastProblem = "no source answered";
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                log("downloading " + url);
                if (target.isFile()) target.delete();
                download(url, target, progress);
                if (looksLikeBootstrap(target)) {
                    log("downloaded " + target.length() + " bytes from " + host(url));
                    return;
                }
                lastProblem = "not a bootstrap zip";
                log("rejected " + host(url) + ": " + lastProblem);
            } catch (Throwable e) {
                lastProblem = describe(e);
                log("failed " + host(url) + ": " + lastProblem);
            }
        }
        throw new IOException(lastProblem);
    }

    /** Newest release asset first, then the two stable fallbacks. */
    private List<String> candidateUrls(String arch) {
        List<String> urls = new ArrayList<String>();
        String asset = "bootstrap-" + arch + ".zip";
        try {
            String json = get(RELEASES_API, 400000);
            int at = 0;
            while (at >= 0 && urls.size() < 4) {
                int name = json.indexOf("\"name\":\"" + asset + "\"", at);
                if (name < 0) break;
                int url = json.indexOf("\"browser_download_url\":\"", name);
                if (url < 0) break;
                int start = url + "\"browser_download_url\":\"".length();
                int end = json.indexOf('"', start);
                if (end < 0) break;
                urls.add(json.substring(start, end));
                at = end;
            }
        } catch (Throwable e) {
            log("release list unavailable: " + describe(e));
        }
        urls.add(LATEST + asset);
        for (String tag : PINNED_TAGS) {
            urls.add("https://github.com/termux/termux-packages/releases/download/" + tag + "/" + asset);
        }
        return urls;
    }

    private static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** A real bootstrap is a zip with a bash in it, not an error page. */
    public static boolean looksLikeBootstrap(File file) {
        if (file == null || !file.isFile() || file.length() < 512 * 1024) return false;
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(file);
            java.util.zip.ZipEntry bash = zip.getEntry("bin/bash");
            if (bash != null) return true;
            // some builds nest the prefix under usr/
            return zip.getEntry("usr/bin/bash") != null;
        } catch (Throwable e) {
            return false;
        } finally {
            if (zip != null) try { zip.close(); } catch (Exception ignored) { }
        }
    }

    private static String get(String url, int limit) throws IOException {
        java.net.HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Nishgamerr-Termux");
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            in = connection.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                sb.append(new String(buffer, 0, n, "UTF-8"));
                if (sb.length() > limit) break;
            }
            return sb.toString();
        } finally {
            close(in);
            if (connection != null) connection.disconnect();
        }
    }

    /** One line per attempt, so a failure on the TV can be read back later. */
    public void log(String message) {
        try {
            File file = new File(root, "install.log");
            List<String> lines = new ArrayList<String>();
            if (file.isFile()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
                reader.close();
            }
            while (lines.size() > 300) lines.remove(0);
            lines.add(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + message);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append(line).append('\n');
            FileOutputStream out = new FileOutputStream(file);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** The tail of install.log, for /api/termux and the TV card. */
    public String log(int lines) {
        File file = new File(root, "install.log");
        if (!file.isFile()) return "";
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            List<String> all = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) all.add(line);
            reader.close();
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, all.size() - lines);
            for (int i = from; i < all.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(all.get(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (message == null || message.length() == 0) return name;
        if (message.length() > 120) message = message.substring(0, 120) + "…";
        return name + ": " + message;
    }

    private void download(String url, File target, Progress progress) throws IOException {
        java.net.HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Nishgamerr-Termux");
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            int total = connection.getContentLength();
            in = connection.getInputStream();
            out = new FileOutputStream(target);
            byte[] buffer = new byte[32768];
            int read;
            long done = 0;
            int last = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int p = 2 + (int) (done * 50 / total);
                    if (p != last) {
                        last = p;
                        progress.onStep("Downloading the Termux userland " + p + "%", p);
                    }
                }
            }
            out.flush();
        } finally {
            close(in);
            close(out);
            if (connection != null) connection.disconnect();
        }
    }

    /* ── unpack ──────────────────────────────────────────────────────────── */

    private void wipePrefix() {
        // A half-finished earlier attempt must not be layered on top of a new one.
        deleteTree(prefix);
        mkdirs(prefix);
    }

    private void unpack(File zip, File into) throws IOException {
        ZipInputStream in = new ZipInputStream(new java.io.BufferedInputStream(
                new FileInputStream(zip), 65536));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.length() == 0) continue;
                if (name.startsWith("/") || name.contains("..")) continue;   // never escape the prefix
                File target = new File(into, name);
                if (entry.isDirectory()) {
                    mkdirs(target);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) mkdirs(parent);
                OutputStream out = new FileOutputStream(target);
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                out.close();
                in.closeEntry();
            }
        } finally {
            close(in);
        }
        // Termux records its symlinks in SYMLINKS.txt instead of zip entries.
        File links = new File(into, "SYMLINKS.txt");
        if (links.isFile()) {
            recreateSymlinks(links, into);
            links.delete();
        }
        makeExecutable(new File(into, "bin"));
        makeExecutable(new File(into, "libexec"));
    }

    private static void recreateSymlinks(File list, File prefix) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(list), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                String target;
                String link;
                int arrow = line.indexOf('\u2190');       // the character Termux writes
                if (arrow > 0) {
                    target = line.substring(0, arrow).trim();
                    link = line.substring(arrow + 1).trim();
                } else if (line.contains("->")) {
                    String[] parts = line.split("->", 2);
                    link = parts[0].trim();
                    target = parts[1].trim();
                } else {
                    continue;
                }
                if (target.length() == 0 || link.length() == 0) continue;
                if (link.startsWith("/") || link.contains("..")) continue;
                File linkFile = new File(prefix, link);
                File parent = linkFile.getParentFile();
                if (parent != null) mkdirs(parent);
                // Termux writes these relative to the prefix, but a bare name
                // (bash) is meant relative to the link's own directory. Resolve
                // whichever one actually exists on disk.
                File resolved = new File(target);
                if (!resolved.isAbsolute()) {
                    File sibling = new File(parent, target);
                    File fromPrefix = new File(prefix, target);
                    if (sibling.exists()) resolved = sibling;
                    else if (fromPrefix.exists()) resolved = fromPrefix;
                }
                linkFile.delete();
                try {
                    java.nio.file.Files.createSymbolicLink(linkFile.toPath(), resolved.toPath());
                } catch (Throwable ignored) {
                    // Android's internal storage allows symlinks; if not, copy would
                    // be wrong, so leave it missing and let the probe decide.
                }
            }
        } catch (Exception ignored) {
        } finally {
            close(reader);
        }
    }

    private static void makeExecutable(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                makeExecutable(entry);
                continue;
            }
            entry.setExecutable(true, false);
            entry.setReadable(true, false);
        }
    }

    /* ── relocate ────────────────────────────────────────────────────────── */

    /** Rewrite Termux's hardcoded paths in every text file of the prefix. */
    private void relocate() {
        byte[] from = TERMUX_PREFIX.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] to = prefix.getAbsolutePath().getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] fromHome = TERMUX_HOME.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] toHome = home.getAbsolutePath().getBytes(java.nio.charset.Charset.forName("UTF-8"));
        relocateTree(prefix, from, to, fromHome, toHome, 0);
    }

    private static void relocateTree(File dir, byte[] from, byte[] to,
                                     byte[] fromHome, byte[] toHome, int depth) {
        if (depth > 8) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                relocateTree(entry, from, to, fromHome, toHome, depth + 1);
                continue;
            }
            if (entry.length() > 4L * 1024L * 1024L) continue;
            rewriteTextFile(entry, from, to, fromHome, toHome);
        }
    }

    /**
     * Rewrites a file only when it is text and actually contains one of the
     * paths. Binaries keep their bytes: their library search path is handled by
     * LD_LIBRARY_PATH instead, and mangling them would break them.
     */
    static void rewriteTextFile(File file, byte[] from, byte[] to, byte[] fromHome, byte[] toHome) {
        InputStream in = null;
        try {
            long length = file.length();
            if (length == 0 || length > 4L * 1024L * 1024L) return;
            in = new FileInputStream(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) length);
            byte[] chunk = new byte[32768];
            int read;
            boolean binary = false;
            long seen = 0;
            while ((read = in.read(chunk)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (chunk[i] == 0) {
                        binary = true;
                        break;
                    }
                }
                seen += read;
                buffer.write(chunk, 0, read);
                if (binary || seen > 4L * 1024L * 1024L) break;
            }
            in.close();
            in = null;
            if (binary) return;
            byte[] bytes = buffer.toByteArray();
            if (!contains(bytes, from) && !contains(bytes, fromHome)) return;
            bytes = replace(bytes, from, to);
            bytes = replace(bytes, fromHome, toHome);
            FileOutputStream out = new FileOutputStream(file);
            out.write(bytes);
            out.close();
            file.setExecutable(true, false);
        } catch (Exception ignored) {
        } finally {
            close(in);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] replace(byte[] haystack, byte[] needle, byte[] replacement) {
        if (needle.length == 0) return haystack;
        ByteArrayOutputStream out = new ByteArrayOutputStream(haystack.length + 64);
        int i = 0;
        while (i < haystack.length) {
            int at = indexOf(java.util.Arrays.copyOfRange(haystack, i, haystack.length), needle);
            if (at < 0) {
                out.write(haystack, i, haystack.length - i);
                break;
            }
            out.write(haystack, i, at);
            out.write(replacement, 0, replacement.length);
            i += at + needle.length;
        }
        return out.toByteArray();
    }

    /**
     * Termux's own pkg/apt cannot work here: apt was compiled with Termux's
     * directory baked in. This shim speaks the same commands and installs the
     * same .deb files straight out of the official Termux repository.
     */
    /**
     * Installs the pkg/apt front end from assets/pkg-termux.sh.
     *
     * The script itself is a normal shell file in the repository, so it can be
     * syntax checked and run by the tests instead of being an untested string
     * inside the app.
     */
    private void writePkgShim() {
        String template = readAsset("pkg-termux.sh");
        if (template == null) {
            log("pkg shim missing from the apk assets");
            return;
        }
        String script = template
                .replace("@PREFIX@", prefix.getAbsolutePath())
                .replace("@ARCH@", archFor(abi))
                .replace("@BASH@", getBash().getAbsolutePath())
                .replace("@BUSYBOX@", busybox == null ? "" : busybox.getAbsolutePath());
        // pkg, apt and apt-get all do the same thing here
        String[] names = {"pkg", "apt", "apt-get"};
        for (String name : names) {
            File file = new File(prefix, "bin/" + name);
            try {
                FileOutputStream out = new FileOutputStream(file);
                out.write(script.getBytes("UTF-8"));
                out.close();
                file.setExecutable(true, false);
                file.setReadable(true, false);
            } catch (Exception e) {
                log("could not write " + name + ": " + e);
            }
        }
        log("pkg/apt installed");
    }

    /** Re-installs pkg/apt into an existing prefix (app updates carry new ones). */
    public void refreshShims() {
        try {
            if (!new File(prefix, "bin").isDirectory()) return;
            if (!new File(prefix, "bin/bash").exists()) return;
            writePkgShim();
        } catch (Throwable ignored) {
        }
    }

    private String readAsset(String name) {
        if (assets == null) return null;
        try {
            byte[] bytes = assets.read(name);
            return bytes == null ? null : new String(bytes, "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    /* ── environment and the proof ───────────────────────────────────────── */

    public java.util.Map<String, String> environment() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<String, String>();
        String p = prefix.getAbsolutePath();
        env.put("PREFIX", p);
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", new File(p, "tmp").getAbsolutePath());
        env.put("PATH", p + "/bin:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", p + "/lib");
        env.put("TERMINFO", p + "/share/terminfo");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");
        env.put("SHELL", p + "/bin/bash");
        env.put("NISHGAMERR", "1");
        ShellRuntime.forwardPackageOverrides(env);
        return env;
    }

    private String bashVersion() {
        String version = run("echo $BASH_VERSION", 6000);
        if (version == null) return "bash";
        version = version.trim();
        int nl = version.indexOf('\n');
        if (nl >= 0) version = version.substring(0, nl).trim();
        return version.length() == 0 ? "bash" : "bash " + version;
    }

    /** The moment of truth: can this prefix actually execute bash? */
    private boolean probe() {
        String out = run("echo NISH_TERMUX_OK; echo $BASH_VERSION", 25000);
        if (out != null && out.contains("NISH_TERMUX_OK")) {
            log("bash runs: " + out.replace('\n', ' ').trim());
            return true;
        }
        if (out == null) {
            lastError = "bash did not start (no answer from " + getBash().getAbsolutePath() + ")";
        } else {
            String first = out.replace('\n', ' ').trim();
            if (first.length() > 90) first = first.substring(0, 90) + "…";
            lastError = first.length() == 0 ? "bash started but printed nothing" : first;
        }
        log("probe failed: " + lastError);
        return false;
    }

    /** Runs a command in the relocated prefix, returns stdout+stderr or null. */
    public String run(String command, long timeoutMs) {
        ProcessBuilder builder = new ProcessBuilder(getBash().getAbsolutePath(), "--noprofile",
                "--norc", "-c", command);
        builder.directory(home);
        builder.redirectErrorStream(true);
        java.util.Map<String, String> env = builder.environment();
        env.clear();
        env.putAll(environment());
        Process process = null;
        try {
            process = builder.start();
            final Process running = process;
            final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    InputStream in = null;
                    try {
                        in = running.getInputStream();
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) sink.write(buffer, 0, read);
                    } catch (IOException ignored) {
                    } finally {
                        close(in);
                    }
                }
            }, "termux-probe");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException still) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            try {
                process.exitValue();
            } catch (IllegalThreadStateException running2) {
                process.destroy();
                return null;
            }
            reader.join(1500);
            return new String(sink.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            if (process != null) process.destroy();
            return null;
        }
    }

    private void writeMarker() {
        try {
            FileOutputStream out = new FileOutputStream(new File(root, READY_MARKER));
            out.write(bashVersionBytes());
            out.close();
            stage("ready");
        } catch (Exception ignored) {
        }
    }

    private byte[] bashVersionBytes() {
        String version = run("echo $BASH_VERSION", 6000);
        return (version == null ? "bash" : version.trim()).getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    /* ── plumbing ────────────────────────────────────────────────────────── */

    private static void mkdirs(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // the caller will fail on the first write, with a clear message
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Files under the prefix, for the UI and the tests. */
    public List<String> listBins() {
        List<String> names = new ArrayList<String>();
        File[] entries = new File(prefix, "bin").listFiles();
        if (entries == null) return names;
        for (File entry : entries) names.add(entry.getName());
        java.util.Collections.sort(names);
        return names;
    }
}

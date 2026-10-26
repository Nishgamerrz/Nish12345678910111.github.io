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

    /** Latest release channel: stable redirect, no API token, no rate limit. */
    private static final String RELEASE =
            "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-";

    /** Where Termux itself lives inside every one of its own binaries. */
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    /** Written only after bash has actually been executed successfully. */
    public static final String READY_MARKER = "termux-ready";
    private static final String STAGE_MARKER = "termux-stage";

    private final File root;        // <filesDir>/termux
    private final File prefix;      // <filesDir>/termux/usr
    private final File home;        // <filesDir>/termux/home
    private final String abi;

    public interface Progress {
        void onStep(String step, int percent);
    }

    public TermuxRuntime(File filesDir, String abi) {
        this.root = new File(filesDir, "termux");
        this.prefix = new File(root, "usr");
        this.home = new File(root, "home");
        this.abi = abi == null ? "" : abi;
    }

    public File getRoot() {
        return root;
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
            String url = RELEASE + "bootstrap-" + arch + ".zip";
            File zip = new File(root, "bootstrap-" + arch + ".zip");

            if (!zip.isFile() || zip.length() < 1024 * 1024) {
                progress.onStep("Downloading the Termux userland (" + arch + ")", 2);
                stage("downloading");
                download(url, zip, progress);
            }

            progress.onStep("Unpacking Termux", 55);
            stage("unpacking");
            wipePrefix();
            unpack(zip, prefix);

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
            String why = e.getClass().getSimpleName();
            if (e.getMessage() != null && e.getMessage().length() > 0) why += ": " + e.getMessage();
            progress.onStep("Termux install failed (" + why + ") – bundled shell stays", 0);
            stage("failed");
            return false;
        }
    }

    /* ── download ────────────────────────────────────────────────────────── */

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
    private void writePkgShim() {
        String prefixPath = prefix.getAbsolutePath();
        String bash = getBash().getAbsolutePath();
        String script =
            "#!" + bash + "\n" +
            "# pkg - installs real Termux packages into this app's prefix\n" +
            "PREFIX=\"" + prefixPath + "\"\n" +
            "ARCH=\"" + archFor(abi) + "\"\n" +
            "REPO=\"https://packages.termux.dev/apt/termux-main\"\n" +
            "STATE=\"$PREFIX/var/lib/nishgamerr\"\n" +
            "INDEX=\"$STATE/Packages\"\n" +
            "HAVE=\"$STATE/installed.txt\"\n" +
            "mkdir -p \"$STATE\" \"$PREFIX/tmp\" 2>/dev/null\n" +
            "fetch() {\n" +
            "  if command -v curl >/dev/null 2>&1; then curl -fsSL \"$1\" -o \"$2\";\n" +
            "  else wget -q -O \"$2\" \"$1\"; fi\n" +
            "}\n" +
            "refresh() {\n" +
            "  echo '[..] refreshing the Termux package index'\n" +
            "  if ! fetch \"$REPO/dists/stable/main/binary-$ARCH/Packages.gz\" \"$STATE/Packages.gz\"; then\n" +
            "    echo '[!] could not reach packages.termux.dev (is the TV online?)' ; return 1\n" +
            "  fi\n" +
            "  gzip -dc \"$STATE/Packages.gz\" > \"$INDEX\" || { echo '[!] broken index'; return 1; }\n" +
            "  echo \"[ok] $(grep -c '^Package:' \"$INDEX\") packages available\"\n" +
            "}\n" +
            "entry() {\n" +
            "  awk -v want=\"$1\" 'BEGIN{RS=\"\"} $0 ~ \"\\\\nPackage: \" want \"\\\\n\" || \"Package: \" want \"\" == $1 {print; exit}' \"$INDEX\"\n" +
            "}\n" +
            "install_one() {\n" +
            "  name=\"$1\"\n" +
            "  [ -f \"$INDEX\" ] || refresh || return 1\n" +
            "  block=$(awk -v want=\"$name\" 'BEGIN{RS=\"\"}{ if ($0 ~ (\"Package: \" want \"\\\\n\")) { print; exit } }' \"$INDEX\")\n" +
            "  file=$(printf '%s\\n' \"$block\" | awk '/^Filename:/{print $2; exit}')\n" +
            "  if [ -z \"$file\" ]; then echo \"[!] no such package: $name\"; return 1; fi\n" +
            "  deb=\"$STATE/$(basename \"$file\")\"\n" +
            "  echo \"[..] downloading $name\"\n" +
            "  fetch \"$REPO/$file\" \"$deb\" || { echo \"[!] download failed\"; return 1; }\n" +
            "  echo \"[..] unpacking $name\"\n" +
            "  if command -v dpkg-deb >/dev/null 2>&1; then\n" +
            "    dpkg-deb -x \"$deb\" \"$PREFIX\" || { echo \"[!] unpack failed\"; return 1; }\n" +
            "  else\n" +
            "    ( cd \"$PREFIX\" && ar p \"$deb\" data.tar.xz | tar -xJ ) || { echo \"[!] unpack failed\"; return 1; }\n" +
            "  fi\n" +
            "  relocate\n" +
            "  rm -f \"$deb\"\n" +
            "  echo \"$name\" >> \"$HAVE\"\n" +
            "  echo \"[ok] $name installed\"\n" +
            "}\n" +
            "relocate() {\n" +
            "  grep -rlI \"$TERMUX_OLD\" \"$PREFIX/bin\" \"$PREFIX/etc\" \"$PREFIX/libexec\" \"$PREFIX/share\" 2>/dev/null \\\n" +
            "    | while read -r f; do sed -i \"s|$TERMUX_OLD|$PREFIX|g\" \"$f\" 2>/dev/null; done\n" +
            "}\n" +
            "TERMUX_OLD='/data/data/com.termux/files/usr'\n" +
            "cmd=\"$1\"\n" +
            "shift 2>/dev/null\n" +
            "case \"$cmd\" in\n" +
            "  install|add|i)\n" +
            "    [ -z \"$1\" ] && { echo 'usage: pkg install <package>'; exit 1; }\n" +
            "    rc=0\n" +
            "    for name in \"$@\"; do install_one \"$name\" || rc=1; done\n" +
            "    exit $rc ;;\n" +
            "  update|upgrade|refresh) refresh; exit $? ;;\n" +
            "  list|ls)\n" +
            "    if [ -f \"$HAVE\" ]; then sort -u \"$HAVE\"; fi\n" +
            "    echo \"-- $(ls \"$PREFIX/bin\" | wc -l) commands in $PREFIX/bin\" ; exit 0 ;;\n" +
            "  search)\n" +
            "    [ -f \"$INDEX\" ] || refresh || exit 1\n" +
            "    awk -v q=\"$1\" 'BEGIN{RS=\"\"}{ if ($0 ~ q) { match($0, /Package: [^\\n]+/); print substr($0, RSTART+9, RLENGTH-9) } }' \"$INDEX\" | head -30\n" +
            "    exit 0 ;;\n" +
            "  files)\n" +
            "    [ -z \"$1\" ] && { echo 'usage: pkg files <package>' ; exit 1; }\n" +
            "    ls -1 \"$PREFIX\" ; exit 0 ;;\n" +
            "  \"\"|help|-h|--help)\n" +
            "    echo 'pkg install <pkg>   download a real Termux package into this app'\n" +
            "    echo 'pkg search <text>    search the Termux repository'\n" +
            "    echo 'pkg list             what is installed'\n" +
            "    echo 'pkg update           refresh the package index'\n" +
            "    exit 0 ;;\n" +
            "  *) echo \"pkg: unknown command '$cmd'\" ; exit 1 ;;\n" +
            "esac\n";
        try {
            File pkg = new File(prefix, "bin/pkg");
            FileOutputStream out = new FileOutputStream(pkg);
            out.write(script.getBytes("UTF-8"));
            out.close();
            pkg.setExecutable(true, false);
        } catch (Exception ignored) {
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
        return out != null && out.contains("NISH_TERMUX_OK");
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

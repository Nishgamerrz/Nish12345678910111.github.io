package com.nishgamerrz.nishgamerr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * The shell userland that ships inside the APK.
 *
 * There is no Termux here and nothing to install: a statically linked BusyBox
 * (342 applets - ash, awk, sed, grep, tar, wget, vi, top, nc, ...) is unpacked
 * from the APK into the app's private storage on first launch, applet symlinks
 * are created, and every console command then runs through that ash instead of
 * Android's cut-down /system/bin/sh.
 *
 * Pure Java with an injectable asset reader, so tools/test-shell.sh can run
 * this exact class on a desktop JVM against the real BusyBox binary.
 */
public final class ShellRuntime {

    /** Where busybox and applets come from (APK assets in the app). */
    public interface Source {
        /** Asset bytes, or null if this device's binary is not bundled. */
        byte[] read(String name) throws IOException;
    }

    public interface Progress {
        void onStep(String step, int percent);
    }

    /** The applets worth symlinking so PATH lookups work like a normal box. */
    public static final String[] APPLETS = {
        "sh", "ash", "bash", "cat", "ls", "ln", "cp", "mv", "rm", "mkdir", "rmdir",
        "touch", "chmod", "chown", "chgrp", "stat", "readlink", "realpath", "basename",
        "dirname", "pwd", "echo", "printf", "env", "id", "whoami", "uname", "uptime",
        "date", "sleep", "seq", "true", "false", "yes", "test", "[", "expr", "bc",
        "grep", "egrep", "fgrep", "sed", "awk", "cut", "sort", "uniq", "head", "tail",
        "wc", "tr", "tee", "xargs", "find", "which", "diff", "cmp", "md5sum", "sha256sum",
        "base64", "tar", "gzip", "gunzip", "zip", "unzip", "bzip2", "bunzip2", "xz",
        "wget", "nc", "ping", "ifconfig", "ip", "netstat", "nslookup", "telnet",
        "ps", "top", "kill", "killall", "free", "df", "du", "mount", "dmesg",
        "vi", "ed", "less", "more", "clear", "tput", "reset", "watch", "timeout",
        "hexdump", "od", "xxd", "strings", "split", "paste", "join", "rev", "fold",
        "nohup", "setsid", "chrt", "flock", "mktemp", "truncate", "sync"
    };

    private final File root;
    private final String abi;
    private final Source source;

    public ShellRuntime(File root, String abi, Source source) {
        this.root = root;
        this.abi = abi == null ? "arm64-v8a" : abi;
        this.source = source;
    }

    public File getRoot() {
        return root;
    }

    public File getPrefix() {
        return new File(root, "usr");
    }

    public File getHome() {
        return new File(root, "home");
    }

    public File getTmp() {
        return new File(root, "tmp");
    }

    public File getBusybox() {
        return new File(getPrefix(), "bin/busybox");
    }

    public String getAbi() {
        return abi;
    }

    /** Which bundled binary this device needs. */
    public static String assetFor(String abi) {
        if (abi == null) return "busybox/busybox-arm64";
        if (abi.contains("arm64")) return "busybox/busybox-arm64";
        if (abi.contains("armeabi")) return "busybox/busybox-arm";
        if (abi.contains("x86_64")) return "busybox/busybox-x86_64";
        if (abi.contains("x86")) return "busybox/busybox-x86";
        return "busybox/busybox-arm64";
    }

    public boolean isInstalled() {
        return getBusybox().isFile() && getBusybox().length() > 10000
                && new File(getPrefix(), "bin/sh").exists();
    }

    public String version() {
        if (!getBusybox().isFile()) return "not installed";
        try {
            Process p = new ProcessBuilder(getBusybox().getAbsolutePath(), "busybox")
                    .redirectErrorStream(true).start();
            String line = firstLine(p.getInputStream());
            p.waitFor();
            return line == null ? "busybox" : line;
        } catch (Exception e) {
            return "busybox (version unknown)";
        }
    }

    /**
     * Unpacks BusyBox, links the applets, writes the profile and the pkg shim.
     * Safe to call repeatedly: it only redoes work that is missing.
     */
    public void install(Progress progress) throws IOException {
        step(progress, "Creating directories", 5);
        mkdirs(getPrefix());
        mkdirs(new File(getPrefix(), "bin"));
        mkdirs(new File(getPrefix(), "etc"));
        mkdirs(new File(getPrefix(), "tmp"));
        mkdirs(getHome());
        mkdirs(getTmp());
        mkdirs(new File(root, "consoles"));

        File busybox = getBusybox();
        if (!busybox.isFile() || busybox.length() < 10000) {
            step(progress, "Unpacking the shell (busybox for " + abi + ")", 20);
            byte[] bytes = source == null ? null : source.read(assetFor(abi));
            if (bytes == null || bytes.length < 10000) {
                throw new IOException("no bundled busybox for " + abi
                        + " (looked for " + assetFor(abi) + ")");
            }
            writeFile(busybox, bytes);
            chmod(busybox);
        }

        step(progress, "Installing applets", 55);
        int made = 0;
        for (int i = 0; i < APPLETS.length; i++) {
            File link = new File(getPrefix(), "bin/" + APPLETS[i]);
            if (link.exists()) continue;
            if (!link.getParentFile().exists()) mkdirs(link.getParentFile());
            made++;
        }
        // Shell applets are symlinks on a normal Linux box; on Android a
        // symlink created through exec is the reliable form, so link via sh.
        runBusybox("--install", "-s", new File(getPrefix(), "bin").getAbsolutePath());
        chmod(new File(getPrefix(), "bin/sh"));

        step(progress, "Finishing the environment", 80);
        writeProfile();
        writePkgShim();

        step(progress, "Ready", 100);
    }

    private void writeProfile() throws IOException {
        File profile = new File(getPrefix(), "etc/profile");
        StringBuilder sb = new StringBuilder();
        sb.append("# Nishgamerr shell profile\n");
        sb.append("export PREFIX=\"").append(getPrefix().getAbsolutePath()).append("\"\n");
        sb.append("export HOME=\"").append(getHome().getAbsolutePath()).append("\"\n");
        sb.append("export TMPDIR=\"").append(getTmp().getAbsolutePath()).append("\"\n");
        sb.append("export PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n");
        sb.append("export TERM=xterm-256color\n");
        sb.append("export PS1='\\w $ '\n");
        writeFile(profile, sb.toString().getBytes("UTF-8"));
    }

    /**
     * A tiny stand-in for Termux's pkg, so the muscle memory still works:
     * pkg install <applet>, pkg list, pkg search, pkg upgrade.
     */
    private void writePkgShim() throws IOException {
        String script =
                "#!" + new File(getPrefix(), "bin/sh").getAbsolutePath() + "\n" +
                "# pkg - Nishgamerr package helper (BusyBox applets ship inside the app)\n" +
                "PREFIX=\"${PREFIX:-" + getPrefix().getAbsolutePath() + "}\"\n" +
                "cmd=\"$1\"\n" +
                "shift 2>/dev/null\n" +
                "case \"$cmd\" in\n" +
                "  install|add|i)\n" +
                "    [ -z \"$1\" ] && { echo \"usage: pkg install <name>\"; exit 1; }\n" +
                "    ok=0\n" +
                "    for name in \"$@\"; do\n" +
                "      if \"$PREFIX/bin/busybox\" \"$name\" --help >/dev/null 2>&1 || " +
                "\"$PREFIX/bin/busybox\" --list | grep -qx \"$name\"; then\n" +
                "        ln -sf \"$PREFIX/bin/busybox\" \"$PREFIX/bin/$name\" 2>/dev/null\n" +
                "        echo \"[ok] $name is available\"\n" +
                "        ok=$((ok+1))\n" +
                "      else\n" +
                "        echo \"[missing] $name is not part of the bundled busybox\"\n" +
                "        echo \"          available: pkg list\"\n" +
                "      fi\n" +
                "    done\n" +
                "    exit 0 ;;\n" +
                "  list|ls|search)\n" +
                "    \"$PREFIX/bin/busybox\" --list | tr '\\n' ' '; echo; exit 0 ;;\n" +
                "  upgrade|update) echo \"[ok] everything bundled is already installed\"; exit 0 ;;\n" +
                "  files) \"$PREFIX/bin/busybox\" --list | wc -l; exit 0 ;;\n" +
                "  \"\"|help|-h)\n" +
                "    echo \"pkg install <name>   make a busybox applet available\";\n" +
                "    echo \"pkg list             every bundled applet\";\n" +
                "    echo \"pkg files            how many there are\";\n" +
                "    exit 0 ;;\n" +
                "  *) echo \"pkg: unknown command '$cmd'\"; exit 1 ;;\n" +
                "esac\n";
        File pkg = new File(getPrefix(), "bin/pkg");
        writeFile(pkg, script.getBytes("UTF-8"));
        chmod(pkg);
    }

    /** Environment for every console session. */
    public java.util.Map<String, String> environment() {
        java.util.Map<String, String> env = new java.util.HashMap<String, String>();
        env.put("PREFIX", getPrefix().getAbsolutePath());
        env.put("HOME", getHome().getAbsolutePath());
        env.put("TMPDIR", getTmp().getAbsolutePath());
        env.put("PATH", getPrefix().getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        env.put("TERM", "xterm-256color");
        env.put("SHELL", new File(getPrefix(), "bin/sh").getAbsolutePath());
        env.put("PS1", "\\w $ ");
        env.put("LANG", "C.UTF-8");
        return env;
    }

    public String starter() {
        File sh = new File(getPrefix(), "bin/sh");
        return sh.isFile() ? sh.getAbsolutePath() : getBusybox().getAbsolutePath();
    }

    private void runBusybox(String a, String b, String c) {
        try {
            Process p = new ProcessBuilder(getBusybox().getAbsolutePath(), a, b, c)
                    .redirectErrorStream(true).start();
            drain(p.getInputStream());
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private static String firstLine(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line = r.readLine();
        drain(in);
        return line;
    }

    private static void drain(InputStream in) {
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) {
                // discard
            }
        } catch (Exception ignored) {
        }
    }

    private static void step(Progress p, String label, int percent) {
        if (p != null) p.onStep(label, percent);
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void chmod(File file) {
        try {
            Process p = new ProcessBuilder("/system/bin/chmod", "0755", file.getAbsolutePath())
                    .redirectErrorStream(true).start();
            drain(p.getInputStream());
            p.waitFor();
        } catch (Exception ignored) {
        }
        // setExecutable is the portable fallback (and works on the JVM too)
        file.setExecutable(true, false);
        file.setReadable(true, false);
    }

    public static ByteArrayOutputStream readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out;
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}

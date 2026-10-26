import com.nishgamerrz.nishgamerr.TermuxRuntime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exercises the Termux installer against a synthetic bootstrap.
 *
 * The real bootstrap is a 33 MB download that cannot be fetched in this
 * sandbox, so this builds a zip shaped exactly like Termux's - a bash, a
 * symlink list, a text file carrying the hardcoded com.termux path and a binary
 * that must NOT be touched - and then runs the same install() the TV runs:
 * unpack, recreate symlinks, relocate the prefix and prove bash works.
 */
public final class TermuxCheck {

    public static void main(String[] args) throws Exception {
        File base = new File(args.length > 0 ? args[0] : "/tmp/termux-check");
        deleteTree(base);
        File files = new File(base, "files");
        File termuxRoot = new File(files, "termux");
        termuxRoot.mkdirs();

        File zip = new File(termuxRoot, "bootstrap-x86_64.zip");
        buildBootstrap(zip);

        final File assetsDir = new File(System.getProperty("nishgamerr.assets", "assets"));
        com.nishgamerrz.nishgamerr.ShellRuntime.Source assets =
                new com.nishgamerrz.nishgamerr.ShellRuntime.Source() {
            public byte[] read(String name) throws IOException {
                File file = new File(assetsDir, name);
                if (!file.isFile()) return null;
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    return out.toByteArray();
                } finally {
                    in.close();
                }
            }
        };
        TermuxRuntime runtime = new TermuxRuntime(files, "x86_64", assets, null);
        final StringBuilder steps = new StringBuilder();
        boolean ready = runtime.install(new TermuxRuntime.Progress() {
            public void onStep(String step, int percent) {
                steps.append(percent).append('%').append(' ').append(step).append('\n');
            }
        });

        File prefix = runtime.getPrefix();
        if (!ready) { report("install() returned false\n" + steps); return; }
        if (!new File(prefix, "bin/bash").canExecute()) { report("bash is not executable"); return; }
        if (!new File(runtime.getRoot(), TermuxRuntime.READY_MARKER).isFile()) {
            { report("ready marker was not written"); return; }
        }

        String motd = read(new File(prefix, "etc/motd"));
        if (motd.contains("/data/data/com.termux/files/usr")) {
            { report("text files were not relocated: " + motd.trim()); return; }
        }
        if (!motd.contains(prefix.getAbsolutePath())) {
            { report("relocated text does not point at this prefix: " + motd.trim()); return; }
        }

        File home = new File(prefix, "etc/home-path");
        if (read(home).contains("/data/data/com.termux/files/home")) {
            { report("the Termux home path was not relocated"); return; }
        }

        File sh = new File(prefix, "bin/sh");
        if (!sh.exists()) { report("SYMLINKS.txt was not honoured (no bin/sh)"); return; }

        File binary = new File(prefix, "lib/libfake.so");
        if (!read(binary).contains("/data/data/com.termux/files/usr")) {
            { report("a binary file was rewritten - that would corrupt it"); return; }
        }

        String env = runtime.environment().get("LD_LIBRARY_PATH");
        if (env == null || !env.startsWith(prefix.getAbsolutePath())) {
            { report("LD_LIBRARY_PATH does not point at the prefix: " + env); return; }
        }
        if (!"aarch64".equals(TermuxRuntime.archFor("arm64-v8a"))
                || !"arm".equals(TermuxRuntime.archFor("armeabi-v7a"))
                || !"x86_64".equals(TermuxRuntime.archFor("x86_64"))
                || !"i686".equals(TermuxRuntime.archFor("x86"))) {
            { report("ABI mapping is wrong"); return; }
        }
        if (TermuxRuntime.looksLikeBootstrap(new File(prefix, "etc/motd"))) {
            report("a text file was accepted as a Termux bootstrap");
            return;
        }
        String log = runtime.log(40);
        if (log.indexOf("bash runs") < 0) { report("the install log stayed empty"); return; }

        File pkg = new File(prefix, "bin/pkg");
        if (!pkg.canExecute()) { report("the pkg shim was not written"); return; }
        for (String alias : new String[]{"apt", "apt-get"}) {
            if (!new File(prefix, "bin/" + alias).canExecute()) {
                report(alias + " was not installed alongside pkg");
                return;
            }
        }
        String shim = read(pkg);
        if (shim.indexOf("/data/data/com.termux/files/usr/bin/bash") == 0) {
            report("the pkg shebang was not rewritten to this app's bash");
            return;
        }
        if (shim.indexOf(prefix.getAbsolutePath()) < 0) {
            report("the pkg shim does not know this app's prefix");
            return;
        }
        System.out.println("OK " + runtime.state() + " (" + runtime.listBins().size()
                + " bins) PKG " + pkg.getAbsolutePath());
    }

    /** A zip with the same shape as Termux's official bootstrap. */
    private static void buildBootstrap(File target) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target));
        try {
            entry(zip, "SYMLINKS.txt", "bash\u2190bin/sh\n");
            entry(zip, "bin/bash",
                    "#!/bin/sh\n"
                            + "# stand-in for Termux's bash: answers like the real one\n"
                            + "case \"$*\" in\n"
                            + "  *NISH_TERMUX_OK*) echo NISH_TERMUX_OK; echo 5.2.15-fake ;;\n"
                            + "  *) echo 5.2.15-fake ;;\n"
                            + "esac\n");
            entry(zip, "etc/motd", "prefix=/data/data/com.termux/files/usr/bin/echo\n");
            entry(zip, "etc/home-path", "home=/data/data/com.termux/files/home\n");
            entry(zip, "lib/libfake.so", "ELF\u0000\u0000/data/data/com.termux/files/usr/lib\u0000tail\n");
            // a megabyte of *incompressible* padding: install() only downloads
            // when the zip is missing or obviously truncated
            byte[] pad = new byte[1100 * 1024];
            new java.util.Random(42).nextBytes(pad);
            zip.putNextEntry(new ZipEntry("share/pad.bin"));
            zip.write(pad);
            zip.closeEntry();
        } finally {
            zip.close();
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String read(File file) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            return new String(buffer, 0, Math.max(read, 0), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void report(String problem) {
        System.out.println("FAIL " + problem.replace('\n', ' '));
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}

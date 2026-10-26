#!/usr/bin/env python3
"""Build a miniature Termux apt repository for tools/test-server.sh.

It contains two packages, the second depending on the first, so the test can
check that `pkg install hello` also pulls in what `hello` needs:

    hello     -> bin/hello (a script whose shebang is the hardcoded Termux path)
                 etc/hello.conf (a text file holding the same path)
    libgreet  -> share/doc/libgreet.txt (text, same path)
                 lib/libgreet.so (a "binary" that must NOT be rewritten)
    demobin   -> bin/demo (a real ELF whose library search path is the Termux
                 one, like every real package), lib/libgreet2.so and
                 share/demo/data.txt (text). Needs gcc; skipped without it.

    python3 tools/make-test-repo.py <output-dir>
"""

import gzip
import io
import lzma
import os
import sys
import tarfile

TERMUX = b"/data/data/com.termux/files/usr"


def tar_xz(entries):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as tar:
        for name, data, mode in entries:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = mode
            info.mtime = 1700000000
            tar.addfile(info, io.BytesIO(data))
    return lzma.compress(raw.getvalue())


def tar_gz(entries):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as tar:
        for name, data, mode in entries:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = mode
            info.mtime = 1700000000
            tar.addfile(info, io.BytesIO(data))
    return gzip.compress(raw.getvalue())


def ar_archive(members):
    """ar(5) with the GNU long-name extension is not needed: names are short."""
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for name, data in members:
        header = "{:<16}{:<12}{:<6}{:<6}{:<8}{:<10}`\n".format(
            name, 1700000000, 0, 0, "100644", len(data))
        out.write(header.encode("ascii"))
        out.write(data)
        if len(data) % 2:
            out.write(b"\n")
    return out.getvalue()


def build_deb(control_fields, data_entries):
    control = ""
    for key, value in control_fields:
        control += "%s: %s\n" % (key, value)
    control_tar = tar_gz([("./control", control.encode(), 0o644)])
    members = [
        ("debian-binary", b"2.0\n"),
        ("control.tar.gz", control_tar),
        ("data.tar.xz", tar_xz(data_entries)),
    ]
    return ar_archive(members)


def build_bash_package(arch):
    """A real, dynamically linked shell under the name bash - exactly what
    "pkg install bash" has to produce for the console to become Termux-like."""
    for candidate in ("/bin/bash", "/usr/bin/bash", "/bin/dash"):
        if not os.path.isfile(candidate):
            continue
        with open(candidate, "rb") as handle:
            data = handle.read()
        if data[:4] != b"\x7fELF":
            continue
        return build_deb(
            [("Package", "bash"), ("Version", "5.2.15"), ("Architecture", arch)],
            [
                ("bin/bash", data, 0o755),
                ("etc/bash.bashrc", b"# " + TERMUX + b"/bin/bash\n", 0o644),
            ],
        )
    return None


def build_elf_package(arch, workdir):
    """Compile a small ELF that looks like a Termux package: its RUNPATH is the
    hardcoded Termux library directory, which on the TV does not exist."""
    import shutil
    import subprocess
    if not shutil.which("gcc") or not shutil.which("ld"):
        return None
    os.makedirs(workdir, exist_ok=True)
    foo = os.path.join(workdir, "foo.c")
    main_c = os.path.join(workdir, "main.c")
    with open(foo, "w") as handle:
        handle.write("int greet(void) { return 42; }\n")
    source = ('#include <stdio.h>\n'
              'extern int greet(void);\n'
              'int main(void) {\n'
              '    printf("elf ok %d\\n", greet());\n'
              '    printf("baked %s\\n", "TERMUX_PATH/share/demo/data.txt");\n'
              '    return 0;\n'
              '}\n').replace("TERMUX_PATH", TERMUX.decode())
    with open(main_c, "w") as handle:
        handle.write(source)
    so = os.path.join(workdir, "libgreet2.so")
    exe = os.path.join(workdir, "demo")
    def run(args):
        return subprocess.run(args, cwd=workdir, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT).returncode
    if run(["gcc", "-shared", "-fPIC", "-o", so, foo]) != 0:
        return None
    if run(["gcc", "-o", exe, main_c, "-L" + workdir, "-lgreet2",
            "-Wl,-rpath," + TERMUX.decode() + "/lib"]) != 0:
        return None
    with open(exe, "rb") as handle:
        binary = handle.read()
    with open(so, "rb") as handle:
        library = handle.read()
    if TERMUX not in binary and TERMUX not in library:
        return None                      # RUNPATH always holds it, but be sure
    return build_deb(
        [("Package", "demobin"), ("Version", "0.1"), ("Architecture", arch),
         ("Depends", "libgreet")],
        [
            ("bin/demo", binary, 0o755),
            ("lib/libgreet2.so", library, 0o644),
            ("share/demo/data.txt",
             b"data in " + TERMUX + b"/share/demo\n", 0o644),
        ],
    )


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "test-repo"
    arch = "aarch64"
    binary = os.path.join(out, "dists", "stable", "main", "binary-" + arch)
    os.makedirs(binary, exist_ok=True)

    libgreet = build_deb(
        [("Package", "libgreet"), ("Version", "1.0"), ("Architecture", arch)],
        [
            ("share/doc/libgreet.txt", b"prefix=" + TERMUX + b"/bin\n", 0o644),
            ("lib/libgreet.so", b"\x7fELF\x00\x00" + TERMUX + b"/lib\x00", 0o644),
        ],
    )
    hello = build_deb(
        [("Package", "hello"), ("Version", "1.2.3"), ("Architecture", arch),
         ("Depends", "libgreet (>= 1.0), libc++")],
        [
            ("bin/hello", b"#!" + TERMUX + b"/bin/bash\necho hello from termux\n", 0o755),
            ("etc/hello.conf", b"shell=" + TERMUX + b"/bin/bash\n", 0o644),
        ],
    )

    for name, payload in (
            ("hello", ("pool/main/h/hello/hello_1.2.3_%s.deb" % arch, hello)),
            ("libgreet", ("pool/main/l/libgreet/libgreet_1.0_%s.deb" % arch, libgreet)),
    ):
        path, data = payload
        target = os.path.join(out, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(data)

    bash = build_bash_package(arch)
    if bash is not None:
        target = os.path.join(out, "pool/main/b/bash/bash_5.2.15_%s.deb" % arch)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(bash)

    demo = build_elf_package(arch, os.path.join(out, ".build"))

    if demo is not None:
        target = os.path.join(out, "pool/main/d/demobin/demobin_0.1_%s.deb" % arch)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(demo)

    index = []
    index.append("Package: hello\nVersion: 1.2.3\nArchitecture: %s\n"
                 "Depends: libgreet (>= 1.0), libc++\n"
                 "Filename: pool/main/h/hello/hello_1.2.3_%s.deb\n"
                 "Size: %d\nDescription: says hello\n"
                 % (arch, arch, len(hello)))
    index.append("Package: libgreet\nVersion: 1.0\nArchitecture: %s\n"
                 "Filename: pool/main/l/libgreet/libgreet_1.0_%s.deb\n"
                 "Size: %d\nDescription: a greeting library\n"
                 % (arch, arch, len(libgreet)))
    if bash is not None:
        index.append("Package: bash\nVersion: 5.2.15\nArchitecture: %s\n"
                     "Filename: pool/main/b/bash/bash_5.2.15_%s.deb\n"
                     "Size: %d\nDescription: the real shell\n"
                     % (arch, arch, len(bash)))

    if demo is not None:
        index.append("Package: demobin\nVersion: 0.1\nArchitecture: %s\n"
                     "Depends: libgreet\n"
                     "Filename: pool/main/d/demobin/demobin_0.1_%s.deb\n"
                     "Size: %d\nDescription: a real ELF, like a real package\n"
                     % (arch, arch, len(demo)))

    packages = "\n".join(index).encode()
    with open(os.path.join(binary, "Packages"), "wb") as handle:
        handle.write(packages)
    with open(os.path.join(binary, "Packages.gz"), "wb") as handle:
        handle.write(gzip.compress(packages))

    print(os.path.join(binary, "Packages"))


if __name__ == "__main__":
    main()

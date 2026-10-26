#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test-pkg.sh — end to end test of assets/pkg-termux.sh, the script the app
# installs as pkg/apt. It runs against a local Termux-style repository, in the
# same conditions as the TV when the real userland is not ready yet:
#
#   * the only things on PATH are BusyBox applets (no dpkg-deb, no curl)
#   * the packages are .debs with Termux's hardcoded paths baked in
#   * one of them is a real ELF with the Termux library directory as RUNPATH
#
#   ./tools/test-pkg.sh          # prints ✓/✗ and "N passed, M failed"
# ---------------------------------------------------------------------------
set -uo pipefail

ANDROID="$(cd "$(dirname "$0")/.." && pwd)"
ASSET="$ANDROID/assets/pkg-termux.sh"
BB_ARM64="$ANDROID/assets/busybox/busybox-arm64"
BB_X86="$ANDROID/assets/busybox/busybox-x86_64"
OUT="$(mktemp -d)"
PORT="${PKG_TEST_PORT:-18123}"

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
no()  { fail=$((fail + 1)); printf '  \033[1;31m✗\033[0m %s%s\n' "$1" "${2:+  → $2}"; }
check(){ if eval "$2"; then ok "$1"; else no "$1" "${3:-}"; fi; }

cleanup() {
    [ -n "${HTTP_PID:-}" ] && kill "$HTTP_PID" 2>/dev/null
    rm -rf "$OUT"
}
trap cleanup EXIT

[ -f "$ASSET" ] || { echo "missing $ASSET"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is needed to build the test repository"; exit 1; }

case "$(uname -m)" in
    x86_64|amd64)  BB="$BB_X86" ;;
    aarch64|arm64) BB="$BB_ARM64" ;;
    *)             echo "no bundled busybox for $(uname -m)"; exit 1 ;;
esac
chmod +x "$BB" 2>/dev/null

mkdir -p "$OUT/busybox" "$OUT/prefix/bin"
cp "$BB" "$OUT/busybox/busybox"
"$OUT/busybox/busybox" --install -s "$OUT/busybox" 2>/dev/null \
    || { echo "the bundled busybox cannot install its applets here"; exit 1; }

echo ""
echo "Building a Termux-style repository"
python3 "$ANDROID/tools/make-test-repo.py" "$OUT/repo" > "$OUT/repo.log" 2>&1 || {
    cat "$OUT/repo.log"; echo "could not build the test repository"; exit 1; }
ok "repository with $(grep -c '^Package:' "$OUT/repo/dists/stable/main/binary-aarch64/Packages") packages"

python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$OUT/repo" \
    > "$OUT/http.log" 2>&1 &
HTTP_PID=$!
for _ in $(seq 1 50); do
    "$OUT/busybox/wget" -q -O /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null && break
    sleep 0.1
done

# the script exactly as the app writes it, but pointing at this prefix and at
# the bundled busybox shell (which is what the TV has before Termux is ready)
sed "s|@PREFIX@|$OUT/prefix|g; s|@ARCH@|aarch64|g; s|@BASH@|$OUT/busybox/sh|g; \
     s|@BUSYBOX@|$OUT/busybox/busybox|g" "$ASSET" > "$OUT/pkg"
chmod +x "$OUT/pkg"

# every run uses nothing but busybox, like the app's own console
runpkg() {
    env -i PATH="$OUT/busybox" HOME="$OUT" \
        NISHGAMERR_PKG_REPO="http://127.0.0.1:$PORT" \
        NISHGAMERR_PKG_ARCH=aarch64 \
        "$OUT/busybox/sh" "$OUT/pkg" "$@" 2>&1
}

echo ""
echo "pkg against the repository (busybox only, no dpkg-deb, no curl)"
out="$(runpkg update)"; rc=$?
check "pkg update fetches the index" '[ $rc -eq 0 ]' "$out"
check "  and reports how many packages there are" 'printf "%s" "$out" | grep -q "packages available"' "$out"

out="$(runpkg search hello)"; rc=$?
check "pkg search finds a package" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -qx hello' "$out"

out="$(runpkg install hello)"; rc=$?
check "pkg install hello succeeds" '[ $rc -eq 0 ]' "$out"
check "  and installs its Depends: libgreet first" 'printf "%s" "$out" | grep -q "libgreet 1.0 installed"' "$out"
check "  then hello itself" 'printf "%s" "$out" | grep -q "hello 1.2.3 installed"' "$out"
check "  the unpacked script runs" '[ "$("$OUT/prefix/bin/hello")" = "hello from termux" ]'
check "  its shebang was rewritten to this prefix" 'head -1 "$OUT/prefix/bin/hello" | grep -q "$OUT/prefix/bin/bash"'
check "  and \$PREFIX/bin/bash exists for it" '[ -x "$OUT/prefix/bin/bash" ]'
check "  text files were rewritten too" 'grep -q "shell=$OUT/prefix/bin/bash" "$OUT/prefix/etc/hello.conf"'
check "  binaries were left untouched" 'grep -qa "/data/data/com.termux/files/usr" "$OUT/prefix/lib/libgreet.so"'

out="$(runpkg list)"; rc=$?
check "pkg list shows what is installed" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -qx hello && printf "%s" "$out" | grep -qx libgreet' "$out"

# a second run must not forget the first (the installed list used to be wiped)
out="$(runpkg install hello)"; rc=$?
check "installing again is a no-op that says so" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -q "already installed"' "$out"
out="$(runpkg list)"
check "  the installed list survived that run" 'printf "%s" "$out" | grep -qx hello' "$out"

if grep -q "Package: demobin" "$OUT/repo/dists/stable/main/binary-aarch64/Packages"; then
    out="$(runpkg install demobin)"; rc=$?
    check "pkg install demobin (a real ELF) succeeds" '[ $rc -eq 0 ]' "$out"
    demo="$(LD_LIBRARY_PATH="$OUT/prefix/lib" "$OUT/prefix/bin/demo" 2>&1)"
    check "  the ELF runs out of this prefix" 'printf "%s" "$demo" | grep -q "elf ok 42"' "$demo"
    check "  it finds its library through LD_LIBRARY_PATH" \
        'printf "%s" "$demo" | grep -qv "cannot open shared object"' "$demo"
    check "  its data file was relocated" 'grep -q "$OUT/prefix/share/demo" "$OUT/prefix/share/demo/data.txt"'
    check "  the ELF itself still carries the Termux path" 'grep -qa "/data/data/com.termux/files/usr" "$OUT/prefix/bin/demo"'
else
    no "a real ELF package could be built (needs gcc)" "skipped"
fi

if grep -q "^Package: bash" "$OUT/repo/dists/stable/main/binary-aarch64/Packages"; then
    out="$(runpkg install bash)"; rc=$?
    check "pkg install bash installs the real shell" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -q "bash 5.2.15 installed"' "$out"
    shellout="$("$OUT/prefix/bin/bash" -c 'echo real-bash-ok' 2>&1)"
    check "  and that shell runs out of the prefix" 'printf "%s" "$shellout" | grep -q "real-bash-ok"' "$shellout"
    check "  its config file was relocated" 'grep -q "$OUT/prefix" "$OUT/prefix/etc/bash.bashrc"'
else
    no "pkg install bash (no shell to package here)" "skipped"
fi

out="$(runpkg install nosuchpackage)"; rc=$?
check "an unknown package fails loudly" '[ $rc -ne 0 ] && printf "%s" "$out" | grep -q "no such package: nosuchpackage"' "$out"
check "  and suggests what to do" 'printf "%s" "$out" | grep -q "pkg update"' "$out"

out="$(runpkg uninstall libgreet)"; rc=$?
check "pkg uninstall works" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -q "removed from the list"' "$out"
check "  and does not delete files behind the user's back" '[ -f "$OUT/prefix/lib/libgreet.so" ]'

out="$(runpkg clean)"; rc=$?
check "pkg clean works" '[ $rc -eq 0 ]' "$out"
out="$(runpkg help)"; rc=$?
check "pkg help works" '[ $rc -eq 0 ] && printf "%s" "$out" | grep -q "pkg install"' "$out"
out="$(runpkg)" ; rc=$?
check "pkg with no arguments prints its help" '[ $rc -eq 0 ]' "$out"
out="$(runpkg boguscommand)"; rc=$?
check "an unknown subcommand fails" '[ $rc -ne 0 ] && printf "%s" "$out" | grep -qi "unknown"' "$out"

out="$(runpkg install hello libgreet)"; rc=$?
check "several packages in one command" '[ $rc -eq 0 ]' "$out"

echo ""
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

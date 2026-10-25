#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# fetch-toolchain.sh — gets everything needed to build the APK without a full
# Android SDK install: a JDK, apktool, aapt2 and apksigner.
#
# Everything lands in android/.tools/ (git-ignored). ~60 MB of downloads.
# You only need to run this once per machine.
#
#   ./tools/fetch-toolchain.sh
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."                     # -> android/
TOOLS="$PWD/.tools"
TMP="$TOOLS/tmp"
mkdir -p "$TOOLS/jar" "$TOOLS/bin" "$TMP"

say() { printf '\n\033[1;35m▸ %s\033[0m\n' "$1"; }

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: '$1' is required but not installed." >&2
        exit 1
    }
}
need python3
need tar

# ── 1. Java runtime + keytool (jdk4py ships both; no system JDK required) ───
if [ -x "$TOOLS/py/jdk4py/java-runtime/bin/java" ]; then
    say "Java already present"
else
    say "Downloading JDK (jdk4py, ~35 MB)"
    python3 -m pip install --quiet --disable-pip-version-check \
        --target "$TOOLS/py" jdk4py
fi

# ── 2. apktool + apksigner ─────────────────────────────────────────────────
if [ -f "$TOOLS/jar/apktool.jar" ] && [ -f "$TOOLS/jar/apksigner.jar" ]; then
    say "apktool + apksigner already present"
else
    say "Downloading apktool + apksigner (~23 MB)"
    URL="https://registry.npmjs.org/@postar/apktool-node/-/apktool-node-0.3.4.tgz"
    curl -fsSL -o "$TMP/apktool-node.tgz" "$URL"
    rm -rf "$TMP/apktool-node" && mkdir -p "$TMP/apktool-node"
    tar -xzf "$TMP/apktool-node.tgz" -C "$TMP/apktool-node"
    cp "$TMP/apktool-node/package/lib/apktool.jar"   "$TOOLS/jar/apktool.jar"
    cp "$TMP/apktool-node/package/lib/apksigner.jar" "$TOOLS/jar/apksigner.jar"
fi

# ── 3. aapt2 ───────────────────────────────────────────────────────────────
if [ -x "$TOOLS/bin/aapt2" ]; then
    say "aapt2 already present"
else
    say "Downloading aapt2 (~9 MB)"
    URL="https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz"
    curl -fsSL -o "$TMP/aaptjs3.tgz" "$URL"
    rm -rf "$TMP/aaptjs3" && mkdir -p "$TMP/aaptjs3"
    tar -xzf "$TMP/aaptjs3.tgz" -C "$TMP/aaptjs3"
    case "$(uname -s)" in
        Linux)  SRC="$TMP/aaptjs3/package/bin/x64/linux/aapt2"  ;;
        Darwin) SRC="$TMP/aaptjs3/package/bin/x64/darwin/aapt2" ;;
        *) echo "Unsupported OS for the bundled aapt2; install Android build-tools." >&2; exit 1 ;;
    esac
    cp "$SRC" "$TOOLS/bin/aapt2"
    chmod +x "$TOOLS/bin/aapt2"
fi

say "Toolchain ready"
"$TOOLS/py/jdk4py/java-runtime/bin/java" -version 2>&1 | head -1
"$TOOLS/bin/aapt2" version
"$TOOLS/py/jdk4py/java-runtime/bin/java" -jar "$TOOLS/jar/apktool.jar" --version \
    | sed 's/^/apktool /'

#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# fetch-toolchain.sh — everything needed to build the APK without Android
# Studio, Gradle, or a system JDK:
#
#   .tools/py/jdk4py/...      Java runtime (pip: jdk4py)
#   .tools/jar/tools.jar      javac 8 (npm: dataslope-tools-jar)
#   .tools/android.jar        Android API 26 stubs (github: Sable/android-platforms)
#   .tools/jar/dx.jar         Dalvik dx, built from AOSP source (github codeload)
#   .tools/jar/apktool.jar    resources + packaging (npm: @postar/apktool-node)
#   .tools/jar/apksigner.jar  signing (same package)
#   .tools/bin/aapt2          resource compiler (npm: aaptjs3)
#
# ~90 MB of downloads, once per machine.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."                     # -> android/
TOOLS="$PWD/.tools"
TMP="$TOOLS/tmp"
JAVA_RUNTIME="$TOOLS/py/jdk4py/java-runtime"
mkdir -p "$TOOLS/jar" "$TOOLS/bin" "$TMP"

say() { printf '\n\033[1;35m▸ %s\033[0m\n' "$1"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required." >&2; exit 1; }; }
need python3
need curl
need tar

# ── 1. Java runtime + keytool ──────────────────────────────────────────────
if [ -x "$JAVA_RUNTIME/bin/java" ]; then
    say "Java runtime already present"
else
    say "Downloading JDK runtime (jdk4py, ~35 MB)"
    python3 -m pip install --quiet --disable-pip-version-check --target "$TOOLS/py" jdk4py
fi
JAVA="$JAVA_RUNTIME/bin/java"
KEYTOOL="$JAVA_RUNTIME/bin/keytool"

# ── 2. javac 8 (tools.jar) ─────────────────────────────────────────────────
if [ -f "$TOOLS/jar/tools.jar" ]; then
    say "javac already present"
else
    say "Downloading javac 8 (~12 MB)"
    URL="$(curl -fsSL https://registry.npmjs.org/dataslope-tools-jar | \
        python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["versions"][d["dist-tags"]["latest"]]["dist"]["tarball"])')"
    curl -fsSL -o "$TMP/tools.tgz" "$URL"
    rm -rf "$TMP/tools" && mkdir -p "$TMP/tools"
    tar -xzf "$TMP/tools.tgz" -C "$TMP/tools"
    cp "$TMP/tools/package/tools.jar" "$TOOLS/jar/tools.jar"
fi
JAVAC_CP="$TOOLS/jar/tools.jar"

# ── 3. android.jar (API 26: Android 8.0, still fine for minSdk 24) ─────────
if [ -f "$TOOLS/android.jar" ]; then
    say "android.jar already present"
else
    say "Downloading android.jar for API 26 (~26 MB)"
    rm -rf "$TMP/platforms"
    git clone --depth 1 --filter=blob:none --no-checkout --quiet \
        https://github.com/Sable/android-platforms.git "$TMP/platforms"
    git -C "$TMP/platforms" checkout HEAD -- android-26/android.jar
    cp "$TMP/platforms/android-26/android.jar" "$TOOLS/android.jar"
    rm -rf "$TMP/platforms"
fi
ANDROID_JAR="$TOOLS/android.jar"

# ── 4. dx (dalvik dexer), compiled from AOSP source ────────────────────────
if [ -f "$TOOLS/jar/dx.jar" ]; then
    say "dx already present"
else
    say "Building dx from AOSP source (~10 s)"
    curl -fsSL -o "$TMP/dalvik.tgz" \
        "https://codeload.github.com/aosp-mirror/platform_dalvik/tar.gz/refs/heads/master"
    rm -rf "$TMP/dalvik" && mkdir -p "$TMP/dalvik"
    tar -xzf "$TMP/dalvik.tgz" -C "$TMP/dalvik" --strip-components=1
    find "$TMP/dalvik/dx/src" -name "*.java" > "$TMP/dx-srcs.txt"
    mkdir -p "$TMP/dx-classes"
    "$JAVA" -Xmx1g -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn \
        -bootclasspath "$ANDROID_JAR" -source 7 -target 7 \
        -d "$TMP/dx-classes" @"$TMP/dx-srcs.txt" 2>&1 \
        | grep -vE "^warning|bootstrap|Xlint|source value|target value" || true
    python3 - "$TMP/dx-classes" "$TOOLS/jar/dx.jar" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for name in files:
            path = os.path.join(root, name)
            z.write(path, os.path.relpath(path, src))
print("dx.jar:", os.path.getsize(out) // 1024, "KB")
PY
    rm -rf "$TMP/dalvik" "$TMP/dx-classes"
fi

# ── 5. apktool + apksigner ─────────────────────────────────────────────────
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

# ── 6. aapt2 ───────────────────────────────────────────────────────────────
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
        *) echo "Unsupported OS for the bundled aapt2" >&2; exit 1 ;;
    esac
    cp "$SRC" "$TOOLS/bin/aapt2"
    chmod +x "$TOOLS/bin/aapt2"
fi

rm -rf "$TMP"
say "Toolchain ready"
"$JAVA" -version 2>&1 | head -1
"$JAVA" -cp "$JAVAC_CP" com.sun.tools.javac.Main -version
"$TOOLS/bin/aapt2" version
"$JAVA" -jar "$TOOLS/jar/apktool.jar" --version | sed 's/^/apktool /'
echo "dx + android.jar: ok"

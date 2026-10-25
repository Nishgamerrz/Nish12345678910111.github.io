#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build.sh — compiles, packages, checks, signs and verifies RelayDashboard.apk
#
#   ./build.sh              build + sign + verify
#   ./build.sh --clean      remove build/ and dist/ first
#   ./build.sh --test       also run the two test suites first
#
# Pipeline: javac (against android.jar) -> dx -> classes.dex -> apktool
# (resources/manifest) -> add dex -> apksigner -> verify.
#
# Any tool can be overridden from the environment, e.g. to use a real SDK:
#   JAVA_BIN=java AAPT2_BIN=aapt2 APKTOOL_JAR=apktool.jar \
#   APKSIGNER_JAR=apksigner.jar ./build.sh
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"

unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS 2>/dev/null || true

TOOLS="$PWD/.tools"
JAVA_BIN="${JAVA_BIN:-$TOOLS/py/jdk4py/java-runtime/bin/java}"
JAVAC_CP="${JAVAC_CP:-$TOOLS/jar/tools.jar}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android.jar}"
DX_JAR="${DX_JAR:-$TOOLS/jar/dx.jar}"
APKTOOL_JAR="${APKTOOL_JAR:-$TOOLS/jar/apktool.jar}"
AAPT2_BIN="${AAPT2_BIN:-$TOOLS/bin/aapt2}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$TOOLS/jar/apksigner.jar}"
KEYTOOL_BIN="${KEYTOOL_BIN:-$(dirname "$JAVA_BIN")/keytool}"

KEYSTORE="${KEYSTORE:-$PWD/keystore/relay-release.keystore}"
KS_ALIAS="${KS_ALIAS:-relaydash}"
KS_PASS="${KS_PASS:-relaydash}"
KS_DNAME="${KS_DNAME:-CN=Relay Dashboard, O=Nishgamerrz, C=IN}"
MIN_SDK=24
EXPECTED_DEX=035          # Android 7.0+ format; dx emits this by default

BUILD="$PWD/build"
OUT_DIR="$PWD/dist"
APK_NAME="RelayDashboard.apk"

say() { printf '\n\033[1;35m▸ %s\033[0m\n' "$1"; }
ok()  { printf '\033[1;32m  ✓ %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

TEST=0
for arg in "$@"; do
    case "$arg" in
        --clean) rm -rf "$BUILD" "$OUT_DIR"; rm -rf "$PWD/build/apk" ;;
        --test)  TEST=1 ;;
    esac
done

for f in "$JAVA_BIN" "$JAVAC_CP" "$ANDROID_JAR" "$DX_JAR" "$APKTOOL_JAR" "$AAPT2_BIN" "$APKSIGNER_JAR"; do
    [ -e "$f" ] || die "missing $f — run tools/fetch-toolchain.sh first"
done

SIGNER=("$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$APKSIGNER_JAR")

if [ "$TEST" = "1" ]; then
    say "Running the test suites"
    node tools/../tools/test-server.sh 2>/dev/null || bash tools/test-server.sh
fi

# ── 1. compile ────────────────────────────────────────────────────────────
say "Compiling Java (Java 7 bytecode, API $MIN_SDK minimum)"
rm -rf "$BUILD/classes" && mkdir -p "$BUILD/classes" "$OUT_DIR"
find src -name "*.java" > "$BUILD/sources.txt"
[ -s "$BUILD/sources.txt" ] || die "no Java sources found in src/"
set +e
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main \
    -nowarn -source 7 -target 7 -bootclasspath "$ANDROID_JAR" \
    -d "$BUILD/classes" @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1
JAVAC_STATUS=$?
set -e
if [ "$JAVAC_STATUS" -ne 0 ]; then
    grep -vE "^warning|bootstrap|Xlint|source value|target value|unchecked|deprecat" "$BUILD/javac.log" | head -40
    die "javac failed (exit $JAVAC_STATUS)"
fi
# Every .java must produce a .class: catches a source that silently vanished
# from the dex because its compile step was skipped.
CLASS_COUNT="$(find "$BUILD/classes" -name '*.class' | wc -l)"
[ "$CLASS_COUNT" -ge 8 ] || die "compilation produced only $CLASS_COUNT classes"
for java in $(cat "$BUILD/sources.txt"); do
    base="$(basename "$java" .java)"
    find "$BUILD/classes" -name "$base.class" | grep -q . \
        || die "$base.java produced no class file"
done
ok "$CLASS_COUNT classes compiled (one per source)"

# ── 2. dex ────────────────────────────────────────────────────────────────
say "Dexing with AOSP dx"
"$JAVA_BIN" -cp "$DX_JAR" com.android.dx.command.Main \
    --dex --output="$BUILD/classes.dex" "$BUILD/classes" 2>&1 | grep -v "^$" || true
[ -f "$BUILD/classes.dex" ] || die "dx produced no classes.dex"
DEX_VERSION="$(head -c 8 "$BUILD/classes.dex" | od -An -c | tr -d ' \n' \
    | sed 's/.*035.*/035/;s/.*037.*/037/;s/.*038.*/038/;s/.*039.*/039/')"
[ "$DEX_VERSION" = "$EXPECTED_DEX" ] \
    || die "classes.dex is format ${DEX_VERSION:-unknown}, expected $EXPECTED_DEX"
ok "classes.dex, format $DEX_VERSION ($(du -h "$BUILD/classes.dex" | cut -f1))"

# ── 3. resources + manifest ───────────────────────────────────────────────
say "Packaging resources and manifest"
rm -f "$OUT_DIR/unsigned.apk"
"$JAVA_BIN" -jar "$APKTOOL_JAR" b . -o "$OUT_DIR/unsigned.apk" --aapt "$AAPT2_BIN"

# ── 4. put the dex inside ─────────────────────────────────────────────────
say "Adding classes.dex"
( cd "$BUILD" && zip -q -X "$OUT_DIR/unsigned.apk" classes.dex ) \
    || die "could not add classes.dex to the apk"
unzip -l "$OUT_DIR/unsigned.apk" | grep -q "classes.dex" || die "classes.dex missing from the apk"
ok "classes.dex packaged"

# ── 5. checks on what was actually built ──────────────────────────────────
say "Checking the package"
BADGING="$("$AAPT2_BIN" dump badging "$OUT_DIR/unsigned.apk")"
echo "$BADGING" | grep -q "minSdkVersion:'$MIN_SDK'" || die "minSdkVersion is not $MIN_SDK"
ok "minSdkVersion $MIN_SDK (Android 7.0+; tested target: Android 8 TV)"
echo "$BADGING" | grep -qi "leanback" || die "no Android TV category"
ok "declares Android TV support"
unzip -l "$OUT_DIR/unsigned.apk" | grep -q "res/drawable-xhdpi-v4/banner.png" \
    || die "TV banner missing"
ok "TV banner bundled"

TREE="$("$AAPT2_BIN" dump xmltree --file AndroidManifest.xml "$OUT_DIR/unsigned.apk")"
echo "$TREE" | grep -q "BOOT_COMPLETED" || die "boot receiver missing"
ok "boot receiver registered"
echo "$TREE" | grep -q "HostService" || die "HostService missing from the manifest"
ok "foreground host service declared"
echo "$TREE" | grep -qi "termux" && die "a Termux permission is still declared"
ok "no Termux dependency"

for cls in WebServer HostService BootReceiver MainActivity Prefs Auth Pages DeviceLog CommandRunner HostState; do
    unzip -p "$OUT_DIR/unsigned.apk" classes.dex | grep -qa "Lcom/nishgamerrz/relaydash/$cls;" \
        || die "class $cls missing from classes.dex"
done

# and the entry point must really be there, not just referenced
unzip -p "$OUT_DIR/unsigned.apk" classes.dex | grep -qa "Lcom/nishgamerrz/relaydash/MainActivity;.[^ ]*onCreate" \
    || unzip -p "$OUT_DIR/unsigned.apk" classes.dex | grep -qa "onCreate" \
    || die "MainActivity.onCreate missing from the dex"
ok "all engine classes present in classes.dex"
for route in "/api/status" "/api/run" "/login"; do
    unzip -p "$OUT_DIR/unsigned.apk" classes.dex | grep -qa "$route" \
        || die "route $route missing"
done
ok "login, status and console routes compiled in"

# ── 6. sign ───────────────────────────────────────────────────────────────
if [ ! -f "$KEYSTORE" ]; then
    say "Creating release keystore (keep this file — updates need the same key)"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL_BIN" -genkeypair -v -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "$KS_DNAME" 2>&1 | tail -1
fi

say "Signing"
"${SIGNER[@]}" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT_DIR/$APK_NAME" "$OUT_DIR/unsigned.apk" 2>&1 \
    | grep -Ev "^WARNING|^$" || true

say "Verifying signature"
VERIFY="$("${SIGNER[@]}" verify --verbose "$OUT_DIR/$APK_NAME" 2>&1)"
echo "$VERIFY" | grep -q "^Verifies" || die "signature verification failed"
echo "$VERIFY" | grep -q "v2 scheme (APK Signature Scheme v2): true" || die "v2 signature missing"
ok "v2 signature valid"
echo "$VERIFY" | grep -q "v1 scheme (JAR signing): true" && ok "v1 signature valid"

rm -f "$OUT_DIR/unsigned.apk"
cp "$OUT_DIR/$APK_NAME" "$PWD/../$APK_NAME"

say "Done"
printf '  android/dist/%s  (%s)\n  %s  (%s, copied to repo root)\n' \
    "$APK_NAME" "$(du -h "$OUT_DIR/$APK_NAME" | cut -f1)" \
    "$APK_NAME" "$(du -h "$PWD/../$APK_NAME" | cut -f1)"

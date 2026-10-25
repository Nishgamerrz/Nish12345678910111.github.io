#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build.sh — builds, checks, signs and verifies RelayDashboard.apk
#
#   ./build.sh              build + sign + verify
#   ./build.sh --clean      remove dist/ first
#
# Uses android/.tools/ fetched by tools/fetch-toolchain.sh. Any tool can be
# overridden with an environment variable, e.g. to use a real Android SDK:
#
#   JAVA_BIN=java AAPT2_BIN=aapt2 APKTOOL_JAR=apktool.jar \
#   APKSIGNER_JAR=apksigner.jar ./build.sh
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"                        # -> android/

unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS 2>/dev/null || true

TOOLS="$PWD/.tools"
JAVA_BIN="${JAVA_BIN:-$TOOLS/py/jdk4py/java-runtime/bin/java}"
APKTOOL_JAR="${APKTOOL_JAR:-$TOOLS/jar/apktool.jar}"
AAPT2_BIN="${AAPT2_BIN:-$TOOLS/bin/aapt2}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$TOOLS/jar/apksigner.jar}"
KEYTOOL_BIN="${KEYTOOL_BIN:-$(dirname "$JAVA_BIN")/keytool}"

KEYSTORE="${KEYSTORE:-$PWD/keystore/relay-release.keystore}"
KS_ALIAS="${KS_ALIAS:-relaydash}"
KS_PASS="${KS_PASS:-relaydash}"
KS_DNAME="${KS_DNAME:-CN=Relay Dashboard, O=Nishgamerrz, C=IN}"
MIN_SDK=24

OUT_DIR="$PWD/dist"
APK_NAME="RelayDashboard.apk"

say()  { printf '\n\033[1;35m▸ %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

if [ "${1:-}" = "--clean" ]; then rm -rf "$OUT_DIR"; shift; fi

for f in "$JAVA_BIN" "$APKTOOL_JAR" "$AAPT2_BIN" "$APKSIGNER_JAR"; do
    [ -e "$f" ] || die "missing $f — run tools/fetch-toolchain.sh first"
done
mkdir -p "$OUT_DIR"

# Java 24+ prints a warning about apksigner's bundled conscrypt; it is harmless.
SIGNER=("$JAVA_BIN" --enable-native-access=ALL-UNNAMED -jar "$APKSIGNER_JAR")

# ── signing key (created once, then reused so updates install over the top) ─
if [ ! -f "$KEYSTORE" ]; then
    say "Creating release keystore (keep this file — updates need the same key)"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL_BIN" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "$KS_DNAME" 2>&1 | tail -1
fi

# ── build resources + smali into an unsigned APK ───────────────────────────
say "Compiling resources and smali"
"$JAVA_BIN" -jar "$APKTOOL_JAR" b . -o "$OUT_DIR/unsigned.apk" --aapt "$AAPT2_BIN"

# ── sanity checks on what actually got built ──────────────────────────────
say "Checking the package"

DEX_VERSION="$(unzip -p "$OUT_DIR/unsigned.apk" classes.dex | head -c 8 | od -An -c \
    | tr -d ' \n' | sed 's/.*035.*/035/;s/.*037.*/037/;s/.*038.*/038/;s/.*039.*/039/')"
[ "$DEX_VERSION" = "035" ] \
    || die "classes.dex is format ${DEX_VERSION:-unknown}, expected 035 for Android 7"
ok "dex format 035 (loads on Android 7.0 and newer)"

BADGING="$("$AAPT2_BIN" dump badging "$OUT_DIR/unsigned.apk")"
echo "$BADGING" | grep -q "minSdkVersion:'$MIN_SDK'" \
    || die "minSdkVersion is not $MIN_SDK"
ok "minSdkVersion $MIN_SDK (Android 7.0)"
echo "$BADGING" | grep -q "launchable-activity" \
    || die "no launchable activity in the manifest"
ok "launcher activity present"
echo "$BADGING" | grep -q "application-icon-" \
    || die "launcher icon missing"
ok "launcher icon bundled"
unzip -l "$OUT_DIR/unsigned.apk" | grep -q "assets/index.html" \
    || die "assets/index.html is missing"
ok "offline UI (assets/index.html) bundled"
for c in MainActivity JsBridge MainActivity\$1 JsBridge\$5; do
    unzip -p "$OUT_DIR/unsigned.apk" classes.dex | grep -qa "$c" \
        || die "class $c missing from classes.dex"
done
ok "smali classes present in classes.dex"

# ── sign (v1 + v2 + v3, so Android 7 and newer all accept it) ──────────────
say "Signing"
"${SIGNER[@]}" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$OUT_DIR/$APK_NAME" \
    "$OUT_DIR/unsigned.apk" 2>&1 | grep -Ev "^WARNING|^$" || true

# ── verify ────────────────────────────────────────────────────────────────
say "Verifying signature"
VERIFY="$("${SIGNER[@]}" verify --verbose "$OUT_DIR/$APK_NAME" 2>&1)"
echo "$VERIFY" | grep -q "^Verifies" || die "signature verification failed"
echo "$VERIFY" | grep -q "v2 scheme (APK Signature Scheme v2): true" \
    || die "v2 signature missing"
ok "v2 signature valid (required from Android 7.0)"
if echo "$VERIFY" | grep -q "v1 scheme (JAR signing): true"; then
    ok "v1 signature valid (helps some odd installers)"
fi

rm -f "$OUT_DIR/unsigned.apk"
cp "$OUT_DIR/$APK_NAME" "$PWD/../$APK_NAME"     # repo root, so GitHub Pages serves it

say "Done"
printf '  android/dist/%s  (%s)\n  %s  (%s, copied to repo root)\n' \
    "$APK_NAME" "$(du -h "$OUT_DIR/$APK_NAME" | cut -f1)" \
    "$APK_NAME" "$(du -h "$PWD/../$APK_NAME" | cut -f1)"

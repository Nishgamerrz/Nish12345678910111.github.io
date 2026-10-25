#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test-server.sh — runs the app's REAL embedded HTTP server on the desktop JVM
# and drives it with curl.
#
# This is not a mock: WebServer, Auth, Pages, DeviceLog and CommandRunner are
# the exact classes that ship in the APK (compiled here with a plain JDK, since
# none of them import android.*).
#
#   ./tools/test-server.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ANDROID="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ANDROID/.tools"
JAVA_BIN="${JAVA_BIN:-$TOOLS/py/jdk4py/java-runtime/bin/java}"
JAVAC_CP="${JAVAC_CP:-$TOOLS/jar/tools.jar}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android.jar}"
PORT="${PORT:-18099}"
OUT="$(mktemp -d)"
LOG="$OUT/server.log"

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
no()  { fail=$((fail + 1)); printf '  \033[1;31m✗\033[0m %s%s\n' "$1" "${2:+  → $2}"; }
check(){ if eval "$2"; then ok "$1"; else no "$1" "${3:-}"; fi; }

cleanup() {
    [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null
    rm -rf "$OUT"
}
trap cleanup EXIT

[ -x "$JAVA_BIN" ] || { echo "missing $JAVA_BIN — run tools/fetch-toolchain.sh"; exit 1; }

echo ""
echo "Compiling the shipping classes for the JVM"
mkdir -p "$OUT/classes"
find "$ANDROID/src" -name "*.java" > "$OUT/srcs.txt"
echo "$ANDROID/test/com/nishgamerrz/relaydash/ServerHarness.java" >> "$OUT/srcs.txt"
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn \
    -bootclasspath "$ANDROID_JAR" -source 7 -target 7 \
    -d "$OUT/classes" @"$OUT/srcs.txt" 2>&1 | grep -vE "^warning|bootstrap|Xlint|source value|target value|unchecked" || true
check "classes compile" '[ "$(find "$OUT/classes" -name "*.class" | wc -l)" -gt 5 ]' \
      "$(find "$OUT/classes" -name '*.class' | wc -l) class files"

echo ""
echo "Starting the embedded server (port $PORT)"
"$JAVA_BIN" -cp "$OUT/classes" com.nishgamerrz.relaydash.ServerHarness "$PORT" "test1234" >"$LOG" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 100); do
    grep -q LISTENING "$LOG" 2>/dev/null && break
    sleep 0.1
done
check "server binds and reports LISTENING" 'grep -q LISTENING "$LOG"' "$(head -5 "$LOG")"

B="http://127.0.0.1:$PORT"
JAR="$OUT/cookies.txt"

req() { # method path [data] [extracurl...]
    local m="$1" p="$2" d="${3:-}"
    shift 3 2>/dev/null || shift $#
    if [ -n "$d" ]; then
        curl -s -o "$OUT/body" -D "$OUT/head" -w '%{http_code}' -X "$m" -b "$JAR" -c "$JAR" \
             --data "$d" "$@" "$B$p"
    else
        curl -s -o "$OUT/body" -D "$OUT/head" -w '%{http_code}' -X "$m" -b "$JAR" -c "$JAR" "$@" "$B$p"
    fi
}
hdr() { tr -d '\r' < "$OUT/head" | grep -i "^$1:" | head -1 | cut -d' ' -f2- ; }
body() { cat "$OUT/body"; }

echo ""
echo "Locked door"
code=$(req GET /); check "GET / without a session returns the login page (200)" '[ "$code" = 200 ]' "code=$code"
check "login page asks for a password" 'grep -qi "type=password" "$OUT/body"'
check "no dashboard content leaks before sign-in" '! grep -q "Devices that opened" "$OUT/body"'
code=$(req GET /api/status); check "API is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req GET /api/devices); check "/api/devices is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req POST /api/run "cmd=id"); check "/api/run is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req GET /somewhere); check "unknown path redirects to login" '[ "$code" = 303 ]' "code=$code"

echo ""
echo "Wrong password"
code=$(req POST /login "password=nope")
check "wrong password is rejected (401)" '[ "$code" = 401 ]' "code=$code"
check "rejection says so" 'grep -qi "wrong password" "$OUT/body"'
check "no session cookie was handed out" '! grep -qi "set-cookie: rd_session=" "$OUT/head"'
code=$(req GET /); check "still locked after a wrong password" 'grep -qi "type=password" "$OUT/body"'

echo ""
echo "Right password"
rm -f "$JAR"
code=$(req POST /login "password=test1234")
check "correct password redirects (303)" '[ "$code" = 303 ]' "code=$code"
check "a session cookie is set" 'grep -qi "set-cookie: rd_session=" "$OUT/head"' "$(tr -d '\r' < "$OUT/head" | grep -i set-cookie)"
check "the cookie is HttpOnly" 'grep -qi "HttpOnly" "$OUT/head"'
code=$(req GET /); check "dashboard loads with the session (200)" '[ "$code" = 200 ]' "code=$code"
check "dashboard says Running" 'grep -q ">Running<" "$OUT/body"'
check "dashboard shows the console" 'grep -qi "Console" "$OUT/body"'
check "dashboard shows the device list" 'grep -qi "Devices that opened" "$OUT/body"'
check "dashboard shows this host" 'grep -q "Test TV" "$OUT/body" || grep -q "Harness" "$OUT/body"'

echo ""
echo "Console"
code=$(req POST /api/run "cmd=echo%20relay-was-here")
check "console returns 200" '[ "$code" = 200 ]' "code=$code"
check "command output is returned as JSON" 'grep -q "relay-was-here" "$OUT/body"' "$(body)"
check "JSON is well formed" 'python3 -c "import json,sys;json.load(open(\"$OUT/body\"))"' "$(body)"
code=$(req POST /api/run "cmd=pwd")
check "working directory command runs" 'grep -q "/" "$OUT/body"'
code=$(req POST /api/run "cmd=this_command_does_not_exist_xyz")
check "a bad command still answers 200 with the error text" '[ "$code" = 200 ] && grep -q "not found" "$OUT/body"' "$(body)"
code=$(req POST /api/run "cmd=")
check "an empty command is refused (400)" '[ "$code" = 400 ]' "code=$code"
code=$(req POST /api/run "cmd=cat%20%2Fdev%2Fzero")
check "a hanging command is stopped by the timeout" 'grep -q "stopped after" "$OUT/body"' "$(body)"
HOSTILE='printf "%s" "<script>alert(1)</script>"'
code=$(req POST /api/run "cmd=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$HOSTILE")")
check "HTML in command output is JSON-escaped, not injected" '! grep -q "<script>alert" "$OUT/body" && grep -q "script" "$OUT/body"' "$(body)"

echo ""
echo "Status, devices, escaping"
code=$(req GET /api/status)
check "/api/status answers" '[ "$code" = 200 ]' "code=$code"
check "status says running" 'grep -q "\"status\":\"running\"" "$OUT/body"' "$(body)"
check "status carries the url and port" 'grep -q "\"url\":\"http://" "$OUT/body" && grep -q "\"port\":'$PORT'" "$OUT/body"'
check "status reports the password is set" 'grep -q "\"password_set\":true" "$OUT/body"'
code=$(req GET /api/devices)
check "/api/devices answers" '[ "$code" = 200 ]' "code=$code"
check "the test client is listed as a device" 'grep -q "127.0.0.1" "$OUT/body"' "$(body)"
check "devices JSON is well formed" 'python3 -c "import json;d=json.load(open(\"$OUT/body\"));assert d[\"devices\"]"'
# a device with a hostile user agent must not break the page
code=$(req GET / "" -H 'User-Agent: <img src=x onerror=alert(1)> Phone')
check "hostile user agent is accepted" '[ "$code" = 200 ]' "code=$code"
check "hostile user agent is escaped on the page" '! grep -q "<img src=x" "$OUT/body"'

echo ""
echo "Sign out"
code=$(req POST /logout)
check "logout redirects" '[ "$code" = 303 ]' "code=$code"
code=$(req GET /)
check "session is dead after logout" 'grep -qi "type=password" "$OUT/body"' "code=$code"
code=$(req GET /api/status)
check "APIs are closed again" '[ "$code" = 401 ]' "code=$code"

echo ""
echo "Second boot / restart"
kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null
"$JAVA_BIN" -cp "$OUT/classes" com.nishgamerrz.relaydash.ServerHarness "$PORT" "test1234" >>"$LOG" 2>&1 &
SRV_PID=$!
sleep 1.2
rm -f "$JAR"
code=$(req POST /login "password=test1234")
check "server restarts and accepts the password again" '[ "$code" = 303 ]' "code=$code"

echo ""
echo "Port already in use"
"$JAVA_BIN" -cp "$OUT/classes" com.nishgamerrz.relaydash.ServerHarness "$PORT" "x" >"$OUT/dup.log" 2>&1 &
DUP=$!
sleep 2
check "a second instance fails instead of silently doing nothing" \
      'grep -qiE "BindException|Address already in use|Exception" "$OUT/dup.log"' "$(head -4 "$OUT/dup.log")"
kill "$DUP" 2>/dev/null

echo ""
echo "Password handling"
cat > "$OUT/AuthCheck.java" <<'EOF'
public class AuthCheck {
    public static void main(String[] a) {
        com.nishgamerrz.relaydash.Auth auth = new com.nishgamerrz.relaydash.Auth(null, null);
        if (auth.isConfigured()) { System.out.println("FAIL unconfigured"); return; }
        auth.setPassword("hunter2");
        if (!auth.isConfigured()) { System.out.println("FAIL not configured after set"); return; }
        if (!auth.check("hunter2")) { System.out.println("FAIL correct password rejected"); return; }
        if (auth.check("hunter3")) { System.out.println("FAIL wrong password accepted"); return; }
        if (auth.check("")) { System.out.println("FAIL empty password accepted"); return; }
        String saltBefore = auth.getSaltHex();
        String hashBefore = auth.getHashHex();
        com.nishgamerrz.relaydash.Auth reloaded =
            new com.nishgamerrz.relaydash.Auth(saltBefore, hashBefore);
        if (!reloaded.check("hunter2")) { System.out.println("FAIL reload lost the password"); return; }
        if (hashBefore.contains("hunter2")) { System.out.println("FAIL password stored in clear"); return; }
        auth.setPassword("hunter2");
        if (saltBefore.equals(auth.getSaltHex())) { System.out.println("FAIL salt not rotated"); return; }
        String pw = com.nishgamerrz.relaydash.Auth.randomPassword(6);
        if (pw.length() != 6 || pw.matches(".*[l1io0].*")) { System.out.println("FAIL weak random pw " + pw); return; }
        System.out.println("OK");
    }
}
EOF
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn -bootclasspath "$ANDROID_JAR" \
    -source 7 -target 7 -cp "$OUT/classes" -d "$OUT/classes" "$OUT/AuthCheck.java" 2>&1 \
    | grep -vE "^warning|bootstrap|Xlint|source value|target value" || true
authout="$("$JAVA_BIN" -cp "$OUT/classes" AuthCheck 2>&1 | tail -1)"
check "password hashing, salting and reload" '[ "$authout" = "OK" ]' "$authout"

echo ""
printf '%s passed, %s failed\n\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

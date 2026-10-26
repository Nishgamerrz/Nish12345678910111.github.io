#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test-server.sh — runs the app's REAL engine on the desktop JVM and drives it
# with curl: the shipping WebServer, Auth, Pages, DeviceLog, ConsoleManager,
# ShellRuntime and ShellSession, using the same BusyBox binary that is bundled
# in the APK (the x86_64 build, so it runs here).
#
#   ./tools/test-server.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ANDROID="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ANDROID/.tools"
JAVA_BIN="${JAVA_BIN:-$TOOLS/py/jdk4py/java-runtime/bin/java}"
JAVAC_CP="${JAVAC_CP:-$TOOLS/jar/tools.jar}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android.jar}"
BUSYBOX="${BUSYBOX:-$ANDROID/assets/busybox/busybox-x86_64}"
PORT="${PORT:-18111}"
OUT="$(mktemp -d)"
LOG="$OUT/server.log"

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
no()  { fail=$((fail + 1)); printf '  \033[1;31m✗\033[0m %s%s\n' "$1" "${2:+  → $2}"; }
check(){ if eval "$2"; then ok "$1"; else no "$1" "${3:-}"; fi; }

cleanup() {
    [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null
    [ -n "${REPO_PID:-}" ] && kill "$REPO_PID" 2>/dev/null
    rm -rf "$OUT"
}
trap cleanup EXIT

[ -x "$JAVA_BIN" ] || { echo "missing $JAVA_BIN — run tools/fetch-toolchain.sh"; exit 1; }
[ -f "$BUSYBOX" ] || { echo "missing bundled busybox at $BUSYBOX"; exit 1; }

echo ""
echo "Compiling the shipping classes for the JVM"
mkdir -p "$OUT/classes"
find "$ANDROID/src" -name "*.java" > "$OUT/srcs.txt"
find "$ANDROID/test" -name "*.java" >> "$OUT/srcs.txt"
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn \
    -bootclasspath "$ANDROID_JAR" -source 7 -target 7 \
    -d "$OUT/classes" @"$OUT/srcs.txt" > "$OUT/javac.log" 2>&1
JC=$?
if [ "$JC" -ne 0 ]; then
    grep -E "error" "$OUT/javac.log" | head -20
    echo "compile failed"; exit 1
fi
check "shipping classes compile" '[ "$(find "$OUT/classes" -name "*.class" | wc -l)" -gt 20 ]' \
      "$(find "$OUT/classes" -name '*.class' | wc -l) class files"

# a Termux-style package repository, so "pkg install" is exercised for real
PKG_PORT="${PKG_PORT:-18124}"
python3 "$ANDROID/tools/make-test-repo.py" "$OUT/repo" > "$OUT/repo.log" 2>&1 \
    || { echo "could not build the test repository"; exit 1; }
python3 -m http.server "$PKG_PORT" --bind 127.0.0.1 --directory "$OUT/repo" \
    > /dev/null 2>&1 &
REPO_PID=$!
export NISHGAMERR_PKG_REPO="http://127.0.0.1:$PKG_PORT"
export NISHGAMERR_PKG_ARCH=aarch64

echo ""
echo "Provisioning the shell from the bundled busybox"
"$JAVA_BIN" -cp "$OUT/classes" com.nishgamerrz.nishgamerr.ServerHarness \
    "$PORT" "test1234" "$BUSYBOX" "$OUT/shellroot" >"$LOG" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 150); do
    grep -q LISTENING "$LOG" 2>/dev/null && break
    sleep 0.1
done
check "shell installs itself (busybox unpacked + applets)" 'grep -q "SHELL BusyBox" "$LOG"' "$(tail -4 "$LOG")"
check "the server binds and reports LISTENING" 'grep -q LISTENING "$LOG"' "$(head -3 "$LOG")"
check "applet symlinks exist (ls, awk, sed, pkg)" \
      '[ -x "$OUT/shellroot/usr/bin/ls" ] && [ -x "$OUT/shellroot/usr/bin/awk" ] && [ -e "$OUT/shellroot/usr/bin/pkg" ]'
check "pkg, apt and apt-get are all installed" \
      '[ -x "$OUT/shellroot/usr/bin/pkg" ] && [ -x "$OUT/shellroot/usr/bin/apt" ] && [ -x "$OUT/shellroot/usr/bin/apt-get" ]'
check "the pkg script is valid busybox shell" 'busybox sh -n "$OUT/shellroot/usr/bin/pkg" 2>/dev/null || sh -n "$OUT/shellroot/usr/bin/pkg"'
check "no placeholder was left in the pkg script" '! grep -q "@PREFIX@\|@BASH@\|@ARCH@\|@BUSYBOX@" "$OUT/shellroot/usr/bin/pkg"'
check "pkg points at the official Termux repository by default" \
      'grep -q "packages.termux.dev/apt/termux-main" "$OUT/shellroot/usr/bin/pkg"'
check "pkg knows this app\'s own prefix" 'grep -q "$OUT/shellroot/usr" "$OUT/shellroot/usr/bin/pkg"'

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
body() { cat "$OUT/body"; }
jsonget() { python3 -c "import json,sys;d=json.load(open('$OUT/body'));print(d.get('$1',''))" 2>/dev/null; }
# evaluates a python expression against the last JSON body: jsonstat 'len(d["themes"])'
jsonstat() { python3 -c "import json,sys;d=json.load(open('$OUT/body'));print($1)" 2>/dev/null; }

echo ""
echo "Locked door"
code=$(req GET /); check "login page before sign-in (200)" '[ "$code" = 200 ]' "code=$code"
check "login page has a password field" 'grep -qi "type=password" "$OUT/body"'
check "no console content before sign-in" '! grep -q "Consoles" "$OUT/body"'
code=$(req GET /api/status); check "API is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req POST /api/run "cmd=id"); check "/api/run is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req GET /api/output?id=main); check "/api/output is 401 without a session" '[ "$code" = 401 ]' "code=$code"
code=$(req POST /api/console/new "name=x"); check "creating a console is 401 without a session" '[ "$code" = 401 ]' "code=$code"

echo ""
echo "Password"
code=$(req POST /login "password=nope"); check "wrong password rejected (401)" '[ "$code" = 401 ]' "code=$code"
rm -f "$JAR"
code=$(req POST /login "password=test1234"); check "correct password redirects (303)" '[ "$code" = 303 ]' "code=$code"
check "HttpOnly session cookie set" 'grep -qi "set-cookie: rd_session=" "$OUT/head" && grep -qi HttpOnly "$OUT/head"'
code=$(req GET /); check "dashboard loads (200)" '[ "$code" = 200 ]' "code=$code"
code=$(req GET /status); check "the status page loads (200)" '[ "$code" = 200 ]' "code=$code"
check "status says Running" 'grep -q ">Running<" "$OUT/body"'
check "console tabs are rendered" 'grep -qi "class=tabs" "$OUT/body"'
check "the bundled shell version is shown" 'grep -q "BusyBox v1" "$OUT/body"'

echo ""
echo "Termux-style console (real busybox, real ash)"
code=$(req POST /api/run "console=main&cmd=echo%20hello-nish")
check "command runs (200)" '[ "$code" = 200 ]' "code=$code"
tail_out="$(jsonget tail)"
check "output reaches the console tail" 'echo "$tail_out" | grep -q "hello-nish"' "$tail_out"
check "response JSON is valid" 'python3 -c "import json;json.load(open(\"$OUT/body\"))"'

code=$(req POST /api/run "console=main&cmd=cd%20%2Ftmp%20%26%26%20pwd")
pwd_out="$(jsonget output)"
check "cd works (ash, not the TV shell)" 'echo "$pwd_out" | grep -q "/tmp"' "$pwd_out"
code=$(req POST /api/run "console=main&cmd=pwd")
pwd2="$(jsonget output)"
check "the working directory persists between commands" 'echo "$pwd2" | grep -q "/tmp"' "$pwd2"

req POST /api/run "console=main&cmd=FOO%3Dbar" >/dev/null
code=$(req POST /api/run "console=main&cmd=echo%20%24FOO")
check "shell variables persist between commands" 'echo "$(jsonget output)" | grep -qx "bar"' "$(jsonget output | cat -A | head -2)"

code=$(req POST /api/run "console=main&cmd=echo%20abc%20%7C%20awk%20%27%7Bprint%20toupper(%240)%7D%27")
check "busybox awk is available (not in /system/bin)" 'echo "$(jsonget output)" | grep -qi "ABC"' "$(jsonget output)"

code=$(req POST /api/run "console=main&cmd=ls%20%2F%20%7C%20head%20-2")
check "busybox ls/pipes work" '[ -n "$(jsonget output)" ]' "$(jsonget output)"

code=$(req POST /api/run "console=main&cmd=pkg%20list")
check "pkg list reports the installed packages" 'echo "$(jsonget output)" | grep -q "commands in"' "$(jsonget output | head -c 120)"

code=$(req POST /api/run "console=main&cmd=pkg%20search%20hello")
check "pkg search reads the package index" 'echo "$(jsonget output)" | grep -qx hello' "$(jsonget output | head -c 160)"

code=$(req POST /api/run "console=main&cmd=pkg%20install%20hello")
for _ in $(seq 1 120); do
    req GET "/api/output?name=main" >/dev/null
    jsonget output | grep -q "hello 1.2.3 installed" && break
    sleep 0.25
done
out="$(jsonget output)"
check "pkg install downloads and unpacks a real package" 'echo "$out" | grep -q "hello 1.2.3 installed"' "$out"
check "  and its dependency first" 'echo "$out" | grep -q "libgreet 1.0 installed"' "$out"
code=$(req POST /api/run "console=main&cmd=hello")
check "the installed package runs in the console" 'echo "$(jsonget output)" | grep -q "hello from termux"' "$(jsonget output)"
code=$(req POST /api/run "console=main&cmd=pkg%20install%20nosuchthing")
check "pkg says clearly when a package does not exist" 'echo "$(jsonget output)" | grep -q "no such package"' "$(jsonget output)"
code=$(req POST /api/run "console=main&cmd=pkg%20help")
check "pkg help works in the console" 'echo "$(jsonget output)" | grep -q "pkg install"' "$(jsonget output)"

code=$(req POST /api/run "console=main&cmd=definitely-not-a-command-xyz")
check "an unknown command reports the shell error" 'echo "$(jsonget output)" | grep -qi "not found"' "$(jsonget output)"

code=$(req POST /api/run "console=main&cmd=")
check "an empty command is refused (400)" '[ "$code" = 400 ]' "code=$code"

HOSTILE='printf "%s" "<script>alert(1)</script>"'
code=$(req POST /api/run "console=main&cmd=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$HOSTILE")")
check "HTML from the shell is JSON-escaped" '! grep -q "<script>alert" "$OUT/body"' "$(body | head -c 120)"

echo ""
echo "Console management (create / switch / delete)"
code=$(req POST /api/console/new "name=work")
newid="$(jsonget id)"
check "a second console can be created" '[ "$code" = 200 ] && [ -n "$newid" ]' "code=$code id=$newid"
code=$(req POST /api/run "console=$newid&cmd=pwd")
check "the new console has its own shell" 'echo "$(jsonget output)" | grep -q "/"' "$(jsonget output)"
check "the new console starts in its own home, not the old cwd" 'echo "$(jsonget output)" | grep -q "shellroot/home"' "$(jsonget output)"
code=$(req GET "/status?c=$newid")
check "switching consoles renders that console" '[ "$code" = 200 ] && grep -qi "Run in work" "$OUT/body"' "code=$code"
code=$(req POST /api/console/clear "id=$newid")
check "clearing a console works" '[ "$code" = 200 ]' "code=$code"
code=$(req POST /api/console/delete "id=$newid")
check "deleting a console works" '[ "$code" = 200 ]' "code=$code"
code=$(req POST /api/console/delete "id=main")
check "the last console cannot be deleted" '[ "$code" = 400 ]' "code=$code"

echo ""
echo "Status, devices, escaping"
code=$(req GET /api/status)
check "/api/status answers" '[ "$code" = 200 ]' "code=$code"
check "status says running" 'grep -q "\"status\":\"running\"" "$OUT/body"' "$(body)"
check "status carries the console count" 'grep -q "\"consoles\":1" "$OUT/body"' "$(body)"
check "status carries the shell version" 'grep -q "BusyBox" "$OUT/body"' "$(body)"
code=$(req GET /api/devices)
check "/api/devices lists this client" 'grep -q "127.0.0.1" "$OUT/body"' "$(body)"
code=$(req GET / "" -H 'User-Agent: <img src=x onerror=alert(1)> Chrome/120 Phone')
check "hostile user agent accepted" '[ "$code" = 200 ]' "code=$code"
check "hostile user agent escaped" '! grep -q "<img src=x" "$OUT/body"'

echo ""
echo ""
echo "The reference dashboard (console manager.txt, copied verbatim)"
code=$(req GET /)
check "the dashboard is served at /" '[ "$code" = "200" ]' "$code"
check "it is the reference page (title + its own stylesheet)" \
      'body | grep -q "<title>Console Manager</title>" && body | grep -q -- "--terminal-bg"' \
      "$(body | grep -o "<title>[^<]*</title>")"
check "the reference script is intact" 'body | grep -q "refreshDashboard" && body | grep -q "themeSelect"' ""
check "the saved style is baked into <body>" '! body | grep -q "__INITIAL_THEME__"' \
      "$(body | grep -o "data-theme=\"[^\"]*\"" | head -1)"
check "it still asks the API for state (not a server-side render)" 'body | grep -q "/api/state"' ""
check "devices and updates are added in their own marked section" \
      'body | grep -q "Nishgamerr addition" && body | grep -q "/api/update"' ""

code=$(req GET /api/state)
check "GET /api/state answers" '[ "$code" = "200" ]' "$code"
console_names="$(jsonstat '",".join(c["name"] for c in d["consoles"])')"
check "state lists the consoles" '[ -n "$console_names" ]' "$console_names"
theme_count="$(jsonstat 'len(d["themes"])')"
check "state carries all eight styles" '[ "$theme_count" = "8" ]' "$theme_count"
stat_keys="$(jsonstat '",".join(sorted(d["stats"]))')"
check "state carries stats (load/memory/disk)" '[ "$stat_keys" = "disk,load,memory" ]' "$stat_keys"
saved_theme="$(jsonstat 'd["preferences"]["theme"]')"
check "state reports the saved style" '[ "$saved_theme" = "purple" ]' "$saved_theme"

code=$(req POST /api/preferences "theme=ocean")
check "a style can be saved" '[ "$code" = "200" ] && body | grep -q "\"ocean\""' "$(body | head -c 80)"
check "the style is written to preferences.json" \
      'grep -q "ocean" "$OUT/shellroot/preferences.json"' "$(cat "$OUT/shellroot/preferences.json" 2>/dev/null)"
code=$(req POST /api/preferences "theme=gothic")
check "an unknown style is refused" '[ "$code" = "400" ]' "$code"
req POST /api/preferences "theme=purple" >/dev/null

code=$(req POST /api/create "name=build")
check "a console can be created by name" '[ "$code" = "200" ] && body | grep -q "\"build\""' "$(body | head -c 80)"
code=$(req POST /api/create "name=build")
check "a duplicate name is refused" '[ "$code" = "400" ]' "$code"
code=$(req POST /api/create "name=not%20allowed!")
check "a name with spaces or symbols is refused" '[ "$code" = "400" ]' "$code"
code=$(req POST /api/create "name=-leading")
check "a name may not start with a dash" '[ "$code" = "400" ]' "$code"

code=$(req POST /api/execute "name=build&command=echo%20hello-from-build")
check "a command can be executed" '[ "$code" = "200" ]' "$code"
sleep 1
code=$(req GET "/api/output?name=build")
check "its output comes back through /api/output" \
      '[ "$code" = "200" ] && echo "$(jsonget output)" | grep -q "hello-from-build"' \
      "$(jsonget output | head -c 120)"
check "the command is echoed like a terminal would" \
      'echo "$(jsonget output)" | grep -q "\$ echo hello-from-build"' "$(jsonget output | head -2)"

code=$(req GET "/api/output?name=ghost")
check "output for an unknown console is 404" '[ "$code" = "404" ]' "$code"

code=$(req POST /api/execute "name=build&command=")
check "an empty command is refused" '[ "$code" = "400" ]' "$code"

code=$(req GET "/api/suggest?name=build&q=echo")
check "completion suggests the command just run" \
      '[ "$code" = "200" ] && body | grep -q "hello-from-build"' "$(body | head -c 120)"
code=$(req GET "/api/suggest?name=build&q=ec")
check "completion also knows PATH commands" '[ "$code" = "200" ] && body | grep -q "echo"' "$(body | head -c 120)"

code=$(req POST /api/delete "name=build")
check "the console can be deleted by name" '[ "$code" = "200" ]' "$code"
code=$(req POST /api/delete "name=main")
check "the last console is protected" '[ "$code" = "400" ]' "$code"

echo ""
echo "Background updates"
code=$(req GET /api/update)
check "the updater reports its state" '[ "$code" = "200" ] && body | grep -q "\"state\""' "$(body | head -c 120)"
check "the current version is reported" 'body | grep -q "\"current\":\"[0-9]"' "$(body | head -c 120)"
code=$(req POST /api/update/check)
check "a check can be requested from the dashboard" '[ "$code" = "200" ]' "$code"


echo ""
echo "The dashboard posts JSON (this is what broke creating consoles on the TV)"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"fromjson"}' "$B/api/create")
check "a console can be created with a JSON body" '[ "$code" = "200" ] && grep -q fromjson "$OUT/body"' "$(head -c 100 "$OUT/body")"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"main"}' "$B/api/create")
check "a duplicate is still refused over JSON" '[ "$code" = "400" ]' "$code"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"bad name"}' "$B/api/create")
check "a bad name is still refused over JSON" '[ "$code" = "400" ]' "$code"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"fromjson","command":"echo json-works"}' "$B/api/execute")
check "a command runs from a JSON body" '[ "$code" = "200" ]' "$code"
sleep 1
code=$(req GET "/api/output?name=fromjson")
check "its output lands in the console" 'echo "$(jsonget output)" | grep -q "json-works"' "$(jsonget output | head -c 100)"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"theme":"cyber"}' "$B/api/preferences")
check "the style can be saved from a JSON body" '[ "$code" = "200" ] && grep -q cyber "$OUT/body"' "$(head -c 100 "$OUT/body")"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"fromjson"}' "$B/api/delete")
check "a console can be deleted from a JSON body" '[ "$code" = "200" ]' "$code"
code=$(curl -s -o "$OUT/body" -w '%{http_code}' -X POST -b "$JAR" \
     -H 'Content-Type: application/json' --data '{"name":"main"}' "$B/api/delete")
check "the last console is still protected" '[ "$code" = "400" ]' "$code"
code=$(req GET /api/state)
console_names="$(jsonstat '",".join(c["name"] for c in d["consoles"])')"
check "the console list is never empty" '[ -n "$console_names" ]' "$console_names"
req POST /api/preferences "theme=purple" >/dev/null

echo ""
echo "Termux status is readable from the dashboard"
code=$(req GET /api/termux)
check "GET /api/termux answers" '[ "$code" = "200" ]' "$code"
check "it says whether the real userland is running" 'grep -q "\"ready\"" "$OUT/body"' "$(head -c 120 "$OUT/body")"
check "it carries the step and the log" 'grep -q "\"step\"" "$OUT/body" && grep -q "\"log\"" "$OUT/body"' "$(head -c 120 "$OUT/body")"
code=$(req GET /)
check "the dashboard shows the Termux panel" 'grep -q "Termux userland" "$OUT/body" && grep -q "/api/termux" "$OUT/body"' ""
check "the dashboard still has the reference page untouched" 'grep -q "<title>Console Manager</title>" "$OUT/body" && grep -q "themeSelect" "$OUT/body"' ""

echo "Sign out"
code=$(req POST /logout); check "logout redirects" '[ "$code" = 303 ]' "code=$code"
code=$(req GET /); check "locked again" 'grep -qi "type=password" "$OUT/body"' "code=$code"
code=$(req GET /api/status); check "APIs closed again" '[ "$code" = 401 ]' "code=$code"

echo ""
echo "Restart"
kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null
"$JAVA_BIN" -cp "$OUT/classes" com.nishgamerrz.nishgamerr.ServerHarness \
    "$PORT" "test1234" "$BUSYBOX" "$OUT/shellroot" >>"$LOG" 2>&1 &
SRV_PID=$!
sleep 2
rm -f "$JAR"
code=$(req POST /login "password=test1234")
check "after a restart the same password still works" '[ "$code" = 303 ]' "code=$code"
code=$(req GET /api/status)
check "console list survived the restart" 'grep -q "\"consoles\":1" "$OUT/body"' "$(body)"


echo ""
echo "The real Termux userland (installer exercised on a synthetic bootstrap)"
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn -bootclasspath "$ANDROID_JAR" \
    -source 7 -target 7 -cp "$OUT/classes" -d "$OUT/classes" \
    "$ANDROID/test/com/nishgamerrz/nishgamerr/TermuxCheck.java" 2>&1 | grep -E "error" || true
termuxout="$("$JAVA_BIN" -cp "$OUT/classes" TermuxCheck "$OUT/termuxcheck" 2>&1 | tail -1)"
check "a Termux-shaped bootstrap installs, relocates and runs" \
      'case "$termuxout" in OK*) true ;; *) false ;; esac' "$termuxout"
check "it reports the Termux shell it is running" 'echo "$termuxout" | grep -q "Termux bash 5.2.15-fake"' "$termuxout"
pkgscript="$(echo "$termuxout" | sed -n 's/.*PKG //p')"
check "the pkg shim is written and executable" '[ -n "$pkgscript" ] && [ -x "$pkgscript" ]' "$pkgscript"
check "the pkg shim is valid shell" 'bash -n "$pkgscript"' "$(bash -n "$pkgscript" 2>&1 | head -2)"
check "pkg points at the official Termux repository" 'grep -q "packages.termux.dev" "$pkgscript"' ""

echo ""
echo "Auth unit checks"
cat > "$OUT/AuthCheck.java" <<'EOF'
public class AuthCheck {
    public static void main(String[] a) {
        com.nishgamerrz.nishgamerr.Auth auth = new com.nishgamerrz.nishgamerr.Auth(null, null);
        if (auth.isConfigured()) { System.out.println("FAIL unconfigured"); return; }
        auth.setPassword("hunter2");
        if (!auth.check("hunter2")) { System.out.println("FAIL correct rejected"); return; }
        if (auth.check("hunter3")) { System.out.println("FAIL wrong accepted"); return; }
        if (auth.getHashHex().contains("hunter2")) { System.out.println("FAIL stored in clear"); return; }
        String salt = auth.getSaltHex();
        com.nishgamerrz.nishgamerr.Auth again = new com.nishgamerrz.nishgamerr.Auth(salt, auth.getHashHex());
        if (!again.check("hunter2")) { System.out.println("FAIL reload lost password"); return; }
        auth.setPassword("hunter2");
        if (salt.equals(auth.getSaltHex())) { System.out.println("FAIL salt not rotated"); return; }
        String pw = com.nishgamerrz.nishgamerr.Auth.randomPassword(6);
        if (pw.length() != 6 || pw.matches(".*[l1io0].*")) { System.out.println("FAIL weak pw " + pw); return; }
        System.out.println("OK");
    }
}
EOF
"$JAVA_BIN" -cp "$JAVAC_CP" com.sun.tools.javac.Main -nowarn -bootclasspath "$ANDROID_JAR" \
    -source 7 -target 7 -cp "$OUT/classes" -d "$OUT/classes" "$OUT/AuthCheck.java" 2>&1 | grep -E "error" || true
authout="$("$JAVA_BIN" -cp "$OUT/classes" AuthCheck 2>&1 | tail -1)"
check "password hashing, salting, reload, random" '[ "$authout" = "OK" ]' "$authout"

echo ""
echo "Package installs without dpkg-deb or curl (busybox only)"
pkgout="$(bash "$ANDROID/tools/test-pkg.sh" 2>&1)"
printf '%s\n' "$pkgout" | grep -E "✓|✗" || true
pkgpass=$(printf '%s\n' "$pkgout" | sed -n 's/^\([0-9][0-9]*\) passed,.*/\1/p' | tail -1)
pkgfail=$(printf '%s\n' "$pkgout" | sed -n 's/.*, \([0-9][0-9]*\) failed$/\1/p' | tail -1)
pass=$((pass + ${pkgpass:-0}))
fail=$((fail + ${pkgfail:-1}))

echo ""
printf '%s passed, %s failed\n\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

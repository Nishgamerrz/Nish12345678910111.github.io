#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test-tv-host.sh — exercises tv-autostart.txt without a TV or Termux.
#
# It builds a fake Termux ($PREFIX/$HOME) with stub pkg/curl/python3/screen and
# a stub Console Manager, then drives install/start/stop/boot-on/boot-off and
# asserts what the script did.
#
#   ./tools/test-tv-host.sh
# ---------------------------------------------------------------------------
set -uo pipefail

SCRIPT="$(cd "$(dirname "$0")/../.." && pwd)/tv-autostart.txt"
[ -f "$SCRIPT" ] || { echo "cannot find tv-autostart.txt"; exit 1; }

pass=0
fail=0
ok()   { pass=$((pass + 1)); printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
no()   { fail=$((fail + 1)); printf '  \033[1;31m✗\033[0m %s%s\n' "$1" "${2:+  → $2}"; }
check(){ if eval "$2"; then ok "$1"; else no "$1" "${3:-}"; fi; }

# ── fake Termux ────────────────────────────────────────────────────────────
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT
export HOME="$ROOT/home"
export PREFIX="$ROOT/usr"
mkdir -p "$HOME" "$PREFIX/bin"

# python3: the script only uses it to discover the LAN address
cat > "$PREFIX/bin/python3" <<'EOF'
#!/bin/sh
# "python3 -" is the LAN-address probe; a script argument means "be the daemon"
if [ "${1:-}" = "-" ]; then echo 192.168.1.99; exit 0; fi
exec sleep 600
EOF

cat > "$PREFIX/bin/pkg" <<'EOF'
#!/bin/sh
echo "pkg $*" >> "$HOME/pkg.log"
EOF

cat > "$PREFIX/bin/screen" <<'EOF'
#!/bin/sh
echo "There is a screen on:"; echo "    1234.ac_one	(Detached)"
EOF

# A miniature "console manager" installer. Written once, at top level, so the
# nested heredocs stay literal; the curl stub just copies it into place.
export FAKE_INSTALLER="$ROOT/fake-installer.sh"
cat > "$FAKE_INSTALLER" <<'INSTALLER'
#!/usr/bin/env bash
DIR="$HOME/.ac-consoles"
mkdir -p "$DIR"
cat > "$DIR/webui.py" <<'PY'
import time
while True: time.sleep(5)
PY
cat > "$PREFIX/bin/ac" <<'AC'
#!/usr/bin/env bash
DIR="$HOME/.ac-consoles"
case "${1:-}" in
    webui-start)
        if [ -f "$DIR/webui.pid" ] && kill -0 "$(cat "$DIR/webui.pid")" 2>/dev/null; then
            echo "Web UI is already running."; exit 0
        fi
        nohup python3 "$DIR/webui.py" </dev/null >/dev/null 2>&1 &
        echo "$!" > "$DIR/webui.pid"
        echo "Web UI started."
        ;;
    webui-stop)
        [ -f "$DIR/webui.pid" ] && kill "$(cat "$DIR/webui.pid")" 2>/dev/null
        rm -f "$DIR/webui.pid"
        echo "Web UI stopped."
        ;;
    webui-urls) echo "  This phone: http://localhost:5000" ;;
    *) echo "dashboard" ;;
esac
AC
chmod +x "$PREFIX/bin/ac"
INSTALLER
chmod +x "$FAKE_INSTALLER"

cat > "$PREFIX/bin/curl" <<'EOF'
#!/bin/sh
out=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        *) shift ;;
    esac
done
cp "$FAKE_INSTALLER" "$out"
EOF
chmod +x "$PREFIX/bin/python3" "$PREFIX/bin/pkg" "$PREFIX/bin/screen" "$PREFIX/bin/curl"
export PATH="$PREFIX/bin:$PATH"

run() { bash "$SCRIPT" "$@" </dev/null; }

echo ""
echo "Fresh install"
run install >"$ROOT/install.out" 2>&1; rc=$?
check "install exits 0" '[ "$rc" = 0 ]' "$(tail -3 "$ROOT/install.out")"
check "Console Manager got installed" '[ -x "$PREFIX/bin/ac" ]'
check "webui.py exists" '[ -f "$HOME/.ac-consoles/webui.py" ]'
check "the host was started" 'grep -q "Console host started" "$ROOT/install.out"' "$(grep -c "started" "$ROOT/install.out")"
check "lan address is printed" 'grep -q "http://192.168.1.99:5000" "$ROOT/install.out"' "$(grep "Other devices" "$ROOT/install.out")"
check "script copied to ~/tv-autostart.txt" '[ -f "$HOME/tv-autostart.txt" ]'
check "boot hook written" '[ -x "$HOME/.termux/boot/tv-console-host" ]'
check "boot hook takes a wake lock first" 'grep -q "termux-wake-lock" "$HOME/.termux/boot/tv-console-host"'
check "allow-external-apps enabled" 'grep -q "^allow-external-apps=true" "$HOME/.termux/termux.properties"'

echo ""
echo "Re-running is idempotent"
run install >"$ROOT/install2.out" 2>&1; rc2=$?
check "second run still exits 0" '[ "$rc2" = 0 ]' "$(tail -3 "$ROOT/install2.out")"
check "allow-external-apps is not duplicated" \
      '[ "$(grep -c "^allow-external-apps=true" "$HOME/.termux/termux.properties")" = "1" ]' \
      "$(grep -c "allow-external-apps" "$HOME/.termux/termux.properties")"
check "Console Manager is not reinstalled" 'grep -q "already installed" "$ROOT/install2.out"'

echo ""
echo "status / url"
run status >"$ROOT/status.out" 2>&1
check "status reports it running" 'grep -q "RUNNING" "$ROOT/status.out"' "$(cat "$ROOT/status.out")"
check "status reports the boot hook" 'grep -q "Boot hook:      installed" "$ROOT/status.out"' "$(cat "$ROOT/status.out")"
run url >"$ROOT/url.out" 2>&1
check "url prints the LAN address" 'grep -q "http://192.168.1.99:5000" "$ROOT/url.out"' "$(cat "$ROOT/url.out")"

echo ""
echo "stop / start / restart"
run stop >"$ROOT/stop.out" 2>&1
pid="$(cat "$HOME/.ac-consoles/webui.pid" 2>/dev/null || echo gone)"
check "stop kills the host" '! kill -0 '"${pid:-999999}"' 2>/dev/null' "pid=$pid"
run status >"$ROOT/status2.out" 2>&1
check "status now says not running" 'grep -q "not running" "$ROOT/status2.out"' "$(cat "$ROOT/status2.out")"
run start >"$ROOT/start.out" 2>&1
check "start brings it back" 'grep -q "Console host started" "$ROOT/start.out"' "$(tail -2 "$ROOT/start.out")"
run restart >"$ROOT/restart.out" 2>&1
check "restart works" 'grep -q "Console host started" "$ROOT/restart.out"' "$(tail -2 "$ROOT/restart.out")"

echo ""
echo "boot hook off / on"
run boot-off >"$ROOT/bootoff.out" 2>&1
check "boot-off removes the hook" '[ ! -f "$HOME/.termux/boot/tv-console-host" ]'
run boot-on >"$ROOT/booton.out" 2>&1
check "boot-on puts it back" '[ -x "$HOME/.termux/boot/tv-console-host" ]'

echo ""
echo "the boot hook really starts the host"
run stop >/dev/null 2>&1
# emulate Termux:Boot executing the hook (path is absolute to Termux in real life)
sed -e "s#/data/data/com.termux/files/usr/bin/sh#$(command -v sh)#" \
    -e "s#termux-wake-lock#true#" \
    -e "s#sleep 5#sleep 0#" \
    "$HOME/.termux/boot/tv-console-host" > "$ROOT/hook.sh"
chmod +x "$ROOT/hook.sh"
HOME="$HOME" PREFIX="$PREFIX" PATH="$PATH" bash "$ROOT/hook.sh" </dev/null >/dev/null 2>&1
check "hook starts the host by itself" 'grep -q "started" "$HOME/.ac-consoles/boot.log" 2>/dev/null' "$(cat "$HOME/.ac-consoles/boot.log" 2>/dev/null)"

echo ""
echo "unknown command"
run frobnicate >"$ROOT/bad.out" 2>&1
check "unknown commands fail with a hint" 'grep -q "Unknown command" "$ROOT/bad.out"' "$(cat "$ROOT/bad.out")"

echo ""
printf '%s passed, %s failed\n\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

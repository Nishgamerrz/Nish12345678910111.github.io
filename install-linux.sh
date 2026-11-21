#!/bin/bash
# Console Manager — PC edition installer for Ubuntu / Linux.
# Install with ONE command (see README-PC.md):
#   curl -fsSL https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-linux.sh | bash
set -euo pipefail

RAW_URL="${CM_PC_URL:-https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/console-manager-pc.py}"
INSTALL_DIR="${CM_PC_HOME:-$HOME/.local/share/console-manager}"
BIN_DIR="$HOME/.local/bin"

echo "Console Manager (PC edition) — installing for $(whoami)…"

# 1) Make sure Python 3 exists (Ubuntu ships it; install only if missing).
if ! command -v python3 >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
        echo "Python 3 not found — installing it with apt…"
        sudo apt-get update -qq
        sudo apt-get install -y -qq python3
    else
        echo "ERROR: Python 3 is required. Install python3 and run this again." >&2
        exit 1
    fi
fi

# 2) Download the single-file app (curl, wget, or python3 — whichever exists).
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
TMP_FILE="$INSTALL_DIR/console-manager-pc.py.tmp"
download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$RAW_URL" -o "$TMP_FILE"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$RAW_URL" -O "$TMP_FILE"
    else
        python3 -c 'import sys,urllib.request; urllib.request.urlretrieve(sys.argv[1], sys.argv[2])' "$RAW_URL" "$TMP_FILE"
    fi
}
download
grep -q "CONSOLE_MANAGER_PC_VERSION" "$TMP_FILE" || { echo "ERROR: download failed validation." >&2; rm -f "$TMP_FILE"; exit 1; }
mv "$TMP_FILE" "$INSTALL_DIR/console-manager-pc.py"
chmod +x "$INSTALL_DIR/console-manager-pc.py"

# 3) The `ac` launcher.
cat > "$BIN_DIR/ac" <<'ACEOF'
#!/bin/sh
exec python3 "$HOME/.local/share/console-manager/console-manager-pc.py" "$@"
ACEOF
chmod +x "$BIN_DIR/ac"

# 4) Put ~/.local/bin on PATH for future terminals (once).
if ! printf '%s' ":$PATH:" | grep -q ":$HOME/.local/bin:"; then
    if [ -f "$HOME/.bashrc" ] && ! grep -q 'console-manager PATH' "$HOME/.bashrc"; then
        printf '\n# console-manager PATH\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.bashrc"
    fi
    export PATH="$HOME/.local/bin:$PATH"
fi

# 5) Start it now and show the URLs.
export CM_PC_HOME="$INSTALL_DIR"
echo
echo "Installed Console Manager."
"$BIN_DIR/ac" start || true
echo
echo "Any time later, just run:  ac"
echo "Update controls: ac update · ac update-on · ac update-off · ac update-status"

# Console Manager — PC Edition (Ubuntu/Linux + Windows)

The same Console Manager dashboard you use on Termux, now for your PC:
persistent named consoles (bash on Linux, PowerShell/cmd on Windows), retained
command + output history, 8 themes (Purple by default), swipe-friendly
scrolling that never snaps you away from old output, access from other devices
on the same Wi-Fi/LAN, and quiet background auto-updates from this repo's
`main` branch. **No password — only use it on a trusted network.**

---

## ⭐ THE one command — same everywhere

Copy this **exact same command** into **any** of these and press Enter — it
detects your system by itself:

- 🐧 Ubuntu / Linux terminal
- 🪟 Windows **PowerShell**
- 🪟 Windows **CMD** (Command Prompt)
- 📱 Termux (installs the phone edition instead)

```text
python3 -c "import sys,os,tempfile as t,urllib.request as u,subprocess as s;p=os.path.join(t.gettempdir(),'cm.py');u.urlretrieve('https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/cm.py',p);s.run([sys.executable,p],check=True)"
```

Setup is quiet: no download logs, just a small rotating
`| / - \` bar saying **“Setting up everything…”**, then your URLs.

That's it — the same paste works on every command bar. On Windows, if
`python3` opens the Microsoft Store, just install Python from there once
(or from python.org, ticking **“Add python.exe to PATH”**), then paste the
same command again. Re-running the command later also updates you.

Prefer a shell-native installer instead? The per-platform commands below do
the same install — the universal command above is the recommended one.

---

## 🐧 Ubuntu / Linux — Terminal (alternative)

```bash
curl -fsSL https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-linux.sh | bash
```

No `curl`? The same job with Python (preinstalled on Ubuntu):

```bash
python3 -c 'import urllib.request as u,subprocess as s;u.urlretrieve("https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-linux.sh","cm-pc.sh");s.run(["bash","cm-pc.sh"],check=True)'
```

## 🪟 Windows — PowerShell (alternative)

```powershell
irm https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-windows.ps1 -UseBasicParsing | iex
```

## 🪟 Windows — Command Prompt (CMD) (alternative)

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-windows.ps1 -UseBasicParsing | iex"
```

The Windows installer finds Python 3 for you — if it's missing and `winget`
is available, it installs Python automatically. No admin rights needed.
After installing, open a **new** terminal window so `ac` is on your PATH.

---

## Using it

The installer starts the Web UI and prints two URLs:

- **This PC:** `http://localhost:5000`
- **Other devices on the same Wi-Fi/LAN:** `http://<your-PC-IP>:5000` (printed for you)

After that, everything is the `ac` command:

```text
ac                Start the Web UI and print its URLs
ac stop           Stop the Web UI (saved consoles/history are kept)
ac status         Web UI + auto-update status
ac urls           Show the localhost + LAN URLs again
ac list           List consoles
ac theme          Show/change the theme (purple, midnight, ocean, emerald, sunset, cyber, mono, contrast)
ac update         Check GitHub main and update right now
ac update-on      Enable quiet background updates (default)
ac update-off     Disable background updates
ac update-status  Auto-update state + last check
```

Create, rename-free manage, clear and delete consoles from the web dashboard.
Every console is one persistent shell, so `cd`, environment variables and
jobs survive between commands — on Windows and Linux alike.

## 😂 Bonus: prank screen

```text
ac prank
```

Shows the “Setting up everything…” spinner for 20 seconds, then a fake
Hollywood-style `HACKING DEVICE...` sequence — and ends with a big
**“PRANK! Nothing was hacked”** reveal. It's pure terminal theatre: it only
prints text, never touches files, the network, or any real command.

## Auto-updates

Just like the Termux edition: when you use `ac`, it quietly checks the
`main` branch at most once every 6 hours, validates the download, swaps the
app file atomically, and restarts the Web UI only if it was already running.
It never pops up windows and never touches your consoles, history, or theme.
Turn it off any time with `ac update-off`.

## Uninstall

```text
ac stop
```

then delete the app folder — Linux: `~/.local/share/console-manager`
(and `~/.local/bin/ac`); Windows: `%LOCALAPPDATA%\ConsoleManager`
(and remove that folder from your user PATH if you like).

## Files

| File | What it is |
| --- | --- |
| `cm.py` | Universal bootstrapper the ONE command downloads — detects Termux / Windows / Linux |
| `console-manager-pc.py` | The whole PC app — one Python file, standard library only |
| `install-linux.sh` | One-command installer for Ubuntu/Linux |
| `install-windows.ps1` | One-command installer for Windows (PowerShell + CMD) |
| `console manager.txt` | The original Termux edition (unchanged) |
| `Nishgamerr.apk` | The Android app (unchanged) |

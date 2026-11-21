#!/usr/bin/env python3
"""Console Manager — UNIVERSAL installer bootstrapper.

This one small file is what the single copy-paste command downloads and runs.
It detects where it is running and does the right install automatically:

  * Termux (Android)  -> downloads and runs the Termux edition (console manager.txt)
  * Windows           -> installs the PC edition to %LOCALAPPDATA%\\ConsoleManager,
                         writes ac.cmd / ac.ps1 launchers, adds them to your user PATH
  * Ubuntu / Linux    -> installs the PC edition to ~/.local/share/console-manager,
                         writes the `ac` launcher into ~/.local/bin

Python 3 standard library only, on every platform.
"""

import os
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

RAW_BASE = "https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main"
PC_URL = os.environ.get("CM_PC_URL", RAW_BASE + "/console-manager-pc.py")
TERMUX_URL = os.environ.get("CM_TERMUX_URL", RAW_BASE + "/console%20manager.txt")
IS_WINDOWS = os.name == "nt"


class QuietSpinner:
    """A little rotating-bar animation so setup never dumps download logs."""

    FRAMES = "|/-\\"

    def __init__(self, label):
        self.label = label
        self._stop = threading.Event()
        self._thread = None
        self._tty = False

    def __enter__(self):
        try:
            self._tty = sys.stdout.isatty()
        except Exception:
            self._tty = False
        if self._tty:
            self._thread = threading.Thread(target=self._spin, daemon=True)
            self._thread.start()
        else:
            print(self.label, flush=True)
        return self

    def _spin(self):
        index = 0
        while not self._stop.is_set():
            sys.stdout.write("\r%s %s" % (self.FRAMES[index % len(self.FRAMES)], self.label))
            sys.stdout.flush()
            index += 1
            self._stop.wait(0.12)

    def __exit__(self, exc_type, exc, tb):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1)
        if self._tty:
            sys.stdout.write("\r" + " " * (len(self.label) + 4) + "\r")
            sys.stdout.flush()
        return False


def is_termux():
    prefix = os.environ.get("PREFIX", "")
    return "com.termux" in prefix or Path("/data/data/com.termux/files/usr").is_dir()


def download(url, destination):
    request = urllib.request.Request(url, headers={"User-Agent": "ConsoleManager-Universal-Installer"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    destination.parent.mkdir(parents=True, exist_ok=True)
    tmp = destination.with_suffix(destination.suffix + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, destination)
    return data


def install_termux():
    print("Console Manager: Termux detected — installing the Termux edition…")
    target = Path.home() / "cm"
    download(TERMUX_URL, target)
    result = subprocess.run(["bash", str(target)], cwd=str(Path.home()))
    return result.returncode


def pc_install_dir():
    if IS_WINDOWS:
        base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "ConsoleManager"
    base = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    return Path(base) / "console-manager"


def add_windows_user_path(folder):
    """Add the install folder to the current user's PATH (no admin needed)."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment", 0,
                            winreg.KEY_READ | winreg.KEY_WRITE) as key:
            try:
                current, kind = winreg.QueryValueEx(key, "Path")
            except FileNotFoundError:
                current, kind = "", winreg.REG_EXPAND_SZ
            parts = [p for p in current.split(";") if p]
            if str(folder) not in parts:
                parts.append(str(folder))
                winreg.SetValueEx(key, "Path", 0, kind if kind else winreg.REG_EXPAND_SZ, ";".join(parts))
        try:  # tell already-open apps the environment changed
            import ctypes
            ctypes.windll.user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0, "Environment", 0x0002, 5000, None)
        except Exception:
            pass
    except Exception as error:
        print("Note: could not update PATH automatically (%s). ac lives in: %s" % (error, folder))


def write_windows_launchers(install_dir, app_path):
    python = sys.executable
    (install_dir / "ac.cmd").write_text(
        '@echo off\r\n"%s" "%s" %%*\r\n' % (python, app_path), encoding="ascii")
    (install_dir / "ac.ps1").write_text(
        '& "%s" "%s" @args\r\n' % (python, app_path), encoding="ascii")


def write_linux_launcher(install_dir, app_path):
    bin_dir = Path.home() / ".local" / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    launcher = bin_dir / "ac"
    launcher.write_text('#!/bin/sh\nexec "%s" "%s" "$@"\n' % (sys.executable, app_path), encoding="utf-8")
    launcher.chmod(0o755)
    bashrc = Path.home() / ".bashrc"
    try:
        if bin_dir.is_dir() and str(bin_dir) not in os.environ.get("PATH", ""):
            existing = bashrc.read_text(encoding="utf-8") if bashrc.exists() else ""
            if "console-manager PATH" not in existing:
                with open(bashrc, "a", encoding="utf-8") as handle:
                    handle.write('\n# console-manager PATH\nexport PATH="$HOME/.local/bin:$PATH"\n')
    except OSError:
        pass


def install_pc():
    install_dir = pc_install_dir()
    app_path = install_dir / "console-manager-pc.py"
    with QuietSpinner("Setting up everything..."):
        data = download(PC_URL, app_path)
        if b"CONSOLE_MANAGER_PC_VERSION" not in data:
            raise RuntimeError("downloaded PC app failed validation")
        if IS_WINDOWS:
            write_windows_launchers(install_dir, app_path)
            add_windows_user_path(install_dir)
        else:
            write_linux_launcher(install_dir, app_path)
        # If it is already running, restart so a re-run of this command also
        # updates; otherwise start it now. Keep the app output captured until
        # the spinner is done, so no intermediate setup noise reaches the bar.
        action = "restart" if (install_dir / "webui.pid").exists() else "start"
        env = os.environ.copy()
        env["CM_PC_HOME"] = str(install_dir)
        env["CM_PC_NO_OPEN"] = "1"  # installing must never pop a browser/window open
        result = subprocess.run(
            [sys.executable, str(app_path), action],
            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
    if result.returncode != 0:
        if result.stdout:
            print(result.stdout, end="")
        return result.returncode
    print("Installed Console Manager.")
    if result.stdout:
        print(result.stdout, end="")
    return 0


def play_prank_same_terminal():
    """Run the joke screen in THIS terminal right after setup (no extra windows).

    Stdout/stderr are inherited, so everything happens in the same console the
    install command was pasted into — nothing spawns a new window.
    """
    app_path = pc_install_dir() / "console-manager-pc.py"
    if not app_path.exists():
        return 0
    env = os.environ.copy()
    env["CM_PC_HOME"] = str(pc_install_dir())
    env.setdefault("CM_PRANK_SETUP_SECONDS", "0")  # setup spinner already played
    print()
    result = subprocess.run([sys.executable, str(app_path), "prank"], env=env)
    return result.returncode


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    try:
        if is_termux():
            return install_termux()
        code = install_pc()
        if code == 0 and ("--prank" in argv or os.environ.get("CM_PRANK") == "1"):
            return play_prank_same_terminal()
        return code
    except Exception as error:  # noqa: BLE001 - one clear line, whatever went wrong
        print("ERROR: %s" % error)
        return 1


if __name__ == "__main__":
    sys.exit(main())

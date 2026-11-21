#!/usr/bin/env python3
"""Console Manager — PC edition (Ubuntu/Linux + Windows).

One cross-platform file, Python standard library only. It runs persistent
named shell consoles (bash on Linux, PowerShell/cmd on Windows) behind the
same style of web dashboard as the Termux edition: per-console command input,
retained command/output history, themes, swipe scrolling that never snaps you
away from old output, and quiet background updates from the GitHub `main`
branch.

Installed and driven by the small `ac` launcher:
    ac            Start the Web UI (background) and print its URLs
    ac stop       Stop the Web UI (your consoles' saved history is kept)
    ac status     Show Web UI + auto-update status
    ac urls       Show this-PC + same-Wi-Fi URLs again
    ac list       List consoles
    ac create N   Create a console      ac delete N   Delete a console
    ac update     Check GitHub main and update now
    ac update-on / update-off / update-status
    ac prank      Optional joke screen: 20s setup spinner, fake movie-hacker
                  code, then a big PRANK reveal. It touches nothing.
"""

import hashlib
import http.server
import ipaddress
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

CONSOLE_MANAGER_PC_VERSION = "1.0"
IS_WINDOWS = os.name == "nt"
RAW_BASE = "https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main"
APP_URL = os.environ.get("CM_PC_URL", RAW_BASE + "/console-manager-pc.py")
UPDATE_INTERVAL = 6 * 60 * 60  # 6 hours, same as the Termux edition
MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
MAX_BODY_BYTES = 64 * 1024
MAX_COMMAND_CHARS = 4000
MAX_OUTPUT_CHARS = 250000
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")
ANSI_RE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\))")


def default_data_dir():
    override = os.environ.get("CM_PC_HOME", "").strip()
    if override:
        return Path(override).expanduser()
    if IS_WINDOWS:
        base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "ConsoleManager"
    base = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    return Path(base) / "console-manager"


BASE_DIR = default_data_dir()
OUTPUT_DIR = BASE_DIR / "output"
HISTORY_DIR = BASE_DIR / "history"
PREFERENCES_FILE = BASE_DIR / "preferences.json"
CONSOLES_FILE = BASE_DIR / "consoles.json"
PID_FILE = BASE_DIR / "webui.pid"
LOG_FILE = BASE_DIR / "webui.log"
UPDATE_LOG = BASE_DIR / "update.log"
UPDATE_LAST_CHECK = BASE_DIR / "update-last-check"
UPDATE_DISABLED = BASE_DIR / "update-disabled"
APP_PATH = Path(__file__).resolve()
WEB_HOST = os.environ.get("AC_WEB_HOST", "0.0.0.0")
WEB_PORT = int(os.environ.get("AC_WEB_PORT", os.environ.get("CM_PC_PORT", "5000")))

for _directory in (BASE_DIR, OUTPUT_DIR, HISTORY_DIR):
    _directory.mkdir(parents=True, exist_ok=True)

THEMES = [
    {"id": "purple", "name": "Purple"},
    {"id": "midnight", "name": "Midnight"},
    {"id": "ocean", "name": "Ocean"},
    {"id": "emerald", "name": "Emerald"},
    {"id": "sunset", "name": "Sunset"},
    {"id": "cyber", "name": "Cyberpunk"},
    {"id": "mono", "name": "Monochrome"},
    {"id": "contrast", "name": "High Contrast"},
]
THEME_IDS = {t["id"] for t in THEMES}
DEFAULT_THEME = "purple"
_prefs_lock = threading.Lock()


def load_theme():
    try:
        theme = json.loads(PREFERENCES_FILE.read_text(encoding="utf-8")).get("theme", "")
    except (OSError, ValueError, AttributeError):
        theme = ""
    return theme if theme in THEME_IDS else DEFAULT_THEME


def save_theme(theme):
    if theme not in THEME_IDS:
        raise ValueError("unknown theme")
    with _prefs_lock:
        tmp = PREFERENCES_FILE.with_suffix(".json.tmp")
        tmp.write_text(json.dumps({"theme": theme}, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, PREFERENCES_FILE)


def lan_ipv4():
    """This machine's private LAN IPv4, chosen by route lookup (no traffic sent)."""
    candidates = []
    override = os.environ.get("AC_LAN_IP", "").strip()
    if override:
        candidates.append(override)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            candidates.append(sock.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), 0, socket.AF_INET, socket.SOCK_DGRAM):
            candidates.append(info[4][0])
    except OSError:
        pass
    seen = set()
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        try:
            address = ipaddress.IPv4Address(candidate)
        except ipaddress.AddressValueError:
            continue
        if (address.is_private or address.is_link_local) and not address.is_loopback and not address.is_unspecified:
            return str(address)
    return ""


def web_urls():
    urls = [("This PC", "http://localhost:%d" % WEB_PORT)]
    network_ip = lan_ipv4()
    if network_ip:
        urls.append(("Other devices on the same Wi-Fi/LAN", "http://%s:%d" % (network_ip, WEB_PORT)))
    return urls


def shell_command():
    if IS_WINDOWS:
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if powershell:
            return [powershell, "-NoLogo", "-NoProfile", "-NoExit", "-Command", "-"]
        return [os.environ.get("COMSPEC", "cmd.exe"), "/Q", "/D"]
    bash = shutil.which("bash")
    if bash:
        return [bash, "--norc"]
    return [shutil.which("sh") or "/bin/sh"]


class Console:
    """One persistent shell process with retained output + command history."""

    def __init__(self, name):
        self.name = name
        self.lock = threading.Lock()
        self.chunks = []
        self.size = 0
        self.history = self._load_json(HISTORY_DIR / (name + ".json"), [])
        self.output_file = OUTPUT_DIR / (name + ".log")
        saved = ""
        try:
            saved = self.output_file.read_text(encoding="utf-8", errors="replace")[-MAX_OUTPUT_CHARS:]
        except OSError:
            pass
        if saved:
            self.chunks.append(saved)
            self.size = len(saved)
        self.process = None
        self.reader = None
        self.start()

    @staticmethod
    def _load_json(path, fallback):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            return value if isinstance(value, type(fallback)) else fallback
        except (OSError, ValueError):
            return fallback

    def start(self):
        env = os.environ.copy()
        env["CM_CONSOLE"] = self.name
        env.setdefault("TERM", "xterm-256color")
        if not IS_WINDOWS:
            env["PS1"] = "$ "
        try:
            self.process = subprocess.Popen(
                shell_command(),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,
            )
        except OSError as error:
            self._append("\n[Console Manager] could not start a shell: %s\n" % error)
            self.process = None
            return
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def alive(self):
        return self.process is not None and self.process.poll() is None

    def _read_loop(self):
        stream = self.process.stdout
        while True:
            try:
                data = os.read(stream.fileno(), 4096)
            except (OSError, ValueError):
                break
            if not data:
                break
            text = data.decode("utf-8", errors="replace")
            text = ANSI_RE.sub("", text).replace("\r\n", "\n").replace("\r", "")
            if text:
                self._append(text)
        self._append("\n[Console Manager] shell exited — send any command to restart it.\n")

    def _append(self, text):
        with self.lock:
            self.chunks.append(text)
            self.size += len(text)
            while self.size > MAX_OUTPUT_CHARS and len(self.chunks) > 1:
                self.size -= len(self.chunks.pop(0))
            if self.size > MAX_OUTPUT_CHARS:
                joined = "".join(self.chunks)[-MAX_OUTPUT_CHARS:]
                self.chunks = [joined]
                self.size = len(joined)
        try:
            with open(self.output_file, "a", encoding="utf-8") as handle:
                handle.write(text)
        except OSError:
            pass

    def output(self):
        with self.lock:
            return "".join(self.chunks)

    def execute(self, command):
        if not self.alive():
            self._append("\n[Console Manager] restarting shell…\n")
            self.start()
        if not self.alive():
            raise RuntimeError("no shell is available for this console")
        with self.lock:
            self.history.append(command)
            self.history = self.history[-200:]
            history_copy = list(self.history)
        try:
            (HISTORY_DIR / (self.name + ".json")).write_text(
                json.dumps(history_copy, indent=1), encoding="utf-8")
        except OSError:
            pass
        self.process.stdin.write((command + "\n").encode("utf-8", errors="replace"))
        self.process.stdin.flush()

    def clear(self):
        with self.lock:
            self.chunks = []
            self.size = 0
        try:
            self.output_file.write_text("", encoding="utf-8")
        except OSError:
            pass

    def terminate(self):
        if self.alive():
            try:
                self.process.terminate()
                self.process.wait(timeout=3)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass


class ConsoleManager:
    def __init__(self):
        self.lock = threading.Lock()
        self.consoles = {}
        names = Console._load_json(CONSOLES_FILE, ["main"])
        names = [n for n in names if NAME_RE.match(n or "")]
        if not names:
            names = ["main"]
        for name in names:
            self.consoles[name] = Console(name)

    def _persist(self):
        try:
            CONSOLES_FILE.write_text(json.dumps(sorted(self.consoles), indent=1), encoding="utf-8")
        except OSError:
            pass

    def names(self):
        with self.lock:
            return sorted(self.consoles)

    def get(self, name):
        with self.lock:
            return self.consoles.get(name)

    def create(self, name):
        if not NAME_RE.match(name or ""):
            raise ValueError("names use letters, numbers, '-' or '_' (max 32 chars)")
        with self.lock:
            if name in self.consoles:
                raise ValueError("console already exists")
            console = Console(name)
            self.consoles[name] = console
            self._persist()
            return console

    def delete(self, name):
        with self.lock:
            console = self.consoles.pop(name, None)
            if console is None:
                raise ValueError("no such console")
            self._persist()
        console.terminate()
        for path in (OUTPUT_DIR / (name + ".log"), HISTORY_DIR / (name + ".json")):
            try:
                path.unlink()
            except OSError:
                pass

    def state(self):
        result = []
        for name in self.names():
            console = self.get(name)
            result.append({
                "name": name,
                "status": "running" if console.alive() else "stopped",
                "history": console.history[-100:],
            })
        return result


MANAGER = None  # created by serve()

HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Console Manager</title>
<style>
:root{color-scheme:dark;--bg:#120a1d;--panel:rgba(31,19,46,.95);--panel2:#29173d;--line:#563674;--text:#faf5ff;--muted:#b9a3ca;--accent:#c084fc;--accent2:#8b5cf6;--green:#54d69a;--red:#ff7381;--input:#1a1028;--btn:#37214e;--btnh:#493065;--term:#0d0715;--termtext:#f3e8ff}
body[data-theme=midnight]{--bg:#08111f;--panel:rgba(13,25,43,.95);--panel2:#122239;--line:#274a6b;--text:#eaf4ff;--muted:#8fb0cc;--accent:#6db9ff;--accent2:#2f7fc4;--term:#050b14;--termtext:#dceeff;--input:#0b1828;--btn:#17334f;--btnh:#1f4265}
body[data-theme=ocean]{--bg:#061218;--panel:rgba(10,29,38,.94);--panel2:#0c2530;--line:#1c4652;--text:#e8fbff;--muted:#84aeb7;--accent:#4dd6e8;--accent2:#168ca5;--term:#041015;--termtext:#d2f5f7;--input:#071a22;--btn:#12313d;--btnh:#194452}
body[data-theme=emerald]{--bg:#07130f;--panel:rgba(13,35,27,.94);--panel2:#102a20;--line:#28503e;--text:#edfff6;--muted:#8eb7a4;--accent:#62dfa5;--accent2:#238b61;--term:#040e0a;--termtext:#d9f8e8;--input:#091c15;--btn:#173a2b;--btnh:#20503b}
body[data-theme=sunset]{--bg:#1b0f16;--panel:rgba(42,24,32,.94);--panel2:#321b25;--line:#5b3541;--text:#fff3ed;--muted:#c0a0a6;--accent:#ffad66;--accent2:#c96645;--term:#130a10;--termtext:#ffe6da;--input:#22131b;--btn:#452733;--btnh:#5a3340}
body[data-theme=cyber]{--bg:#090714;--panel:rgba(23,17,37,.95);--panel2:#211531;--line:#4c2e67;--text:#f8edff;--muted:#b39ac5;--accent:#ff4fd8;--accent2:#a62c9d;--term:#06050d;--termtext:#d9faff;--input:#0e0a1c;--btn:#2b1940;--btnh:#3b2254}
body[data-theme=mono]{--bg:#101112;--panel:rgba(27,29,31,.95);--panel2:#222426;--line:#42464a;--text:#f4f4f4;--muted:#a6aaae;--accent:#e6e6e6;--accent2:#656b71;--term:#090a0b;--termtext:#e8e8e8;--input:#141618;--btn:#2b2e31;--btnh:#3a3e42}
body[data-theme=contrast]{--bg:#000;--panel:rgba(5,5,5,.98);--panel2:#111;--line:#e8e8e8;--text:#fff;--muted:#e0e0e0;--accent:#ffe600;--accent2:#665800;--term:#000;--termtext:#fff;--input:#000;--btn:#181818;--btnh:#333}
*{box-sizing:border-box}[hidden]{display:none!important}
body{margin:0;min-height:100vh;background:radial-gradient(circle at 12% -10%,rgba(192,132,252,.20),transparent 38rem),var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
.wrap{max-width:1060px;margin:0 auto;padding:18px 14px 40px}
header{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between}
h1{font-size:21px;margin:0}.sub{color:var(--muted);font-size:12.5px}
select,input[type=text]{background:var(--input);color:var(--text);border:1px solid var(--line);border-radius:9px;padding:8px 10px;font:inherit}
.btn{background:var(--btn);color:var(--text);border:1px solid var(--line);border-radius:9px;padding:8px 12px;cursor:pointer;font:inherit;min-height:38px}
.btn:hover{background:var(--btnh)}.btn.primary{background:var(--accent2);border-color:var(--accent)}
.btn.danger{color:var(--red)}.btn.small{min-height:34px;padding:5px 10px}
.tabs{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0 10px}
.tab{border:1px solid var(--line);background:var(--panel2);color:var(--text);border-radius:999px;padding:7px 14px;cursor:pointer;font:inherit}
.tab.active{background:var(--accent2);border-color:var(--accent)}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--green);margin-right:6px}
.dot.off{background:var(--red)}
.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:14px;box-shadow:0 18px 55px rgba(0,0,0,.35)}
.termbar{display:flex;gap:8px;align-items:center;justify-content:space-between;flex-wrap:wrap;margin-bottom:8px}
.termname{font-weight:700}
#output{height:52vh;min-height:260px;overflow-y:scroll;overflow-x:auto;overscroll-behavior:contain;touch-action:pan-y;background:var(--term);color:var(--termtext);border:1px solid var(--line);border-radius:12px;padding:12px;margin:0;white-space:pre-wrap;word-break:break-word;font:13px/1.55 ui-monospace,Consolas,Menlo,monospace;scrollbar-width:auto;scrollbar-color:var(--accent) var(--btn)}
#output::-webkit-scrollbar{width:12px}#output::-webkit-scrollbar-thumb{background:var(--accent);border-radius:8px}#output::-webkit-scrollbar-track{background:var(--btn)}
.scrollbtns{display:flex;gap:6px}.scrollbtns .btn{min-width:40px;min-height:38px;font-weight:700}
#latestBtn.badge{border-color:var(--accent);color:var(--accent)}
form.cmd{display:flex;gap:8px;margin-top:10px}form.cmd input{flex:1}
.hint{color:var(--muted);font-size:12px;margin-top:8px}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.err{color:var(--red);min-height:18px;font-size:12.5px}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div><h1>🖥️ Console Manager</h1><div class="sub" id="sub">PC edition</div></div>
    <label class="sub">Style
      <select id="theme"></select>
    </label>
  </header>
  <div class="tabs" id="tabs"></div>
  <div class="row" style="margin-bottom:12px">
    <input type="text" id="newName" placeholder="new console name" maxlength="32">
    <button class="btn" id="createBtn">+ New console</button>
  </div>
  <div class="card">
    <div class="termbar">
      <span class="termname" id="termName">—</span>
      <span class="scrollbtns">
        <button class="btn" id="upBtn" title="Scroll up">↑</button>
        <button class="btn" id="downBtn" title="Scroll down">↓</button>
        <button class="btn" id="latestBtn" title="Jump to latest output">Latest</button>
      </span>
    </div>
    <pre id="output">Loading…</pre>
    <form class="cmd" id="cmdForm">
      <input type="text" id="cmd" placeholder="Type a command and press Enter" autocomplete="off" spellcheck="false">
      <button class="btn primary" type="submit">Run</button>
      <button class="btn" type="button" id="clearBtn">Clear</button>
      <button class="btn danger" type="button" id="deleteBtn">Delete</button>
    </form>
    <div class="err" id="err"></div>
    <div class="hint">Consoles are persistent shells — <b>cd</b>, variables and jobs survive between commands. Output is retained; scroll up freely, refreshes won't pull you back down. Press <b>Latest</b> to follow new output again. No password — use only on a trusted network.</div>
  </div>
</div>
<script>
"use strict";
const $=id=>document.getElementById(id);
const out=$("output"), cmdInput=$("cmd");
let consoles=[], active=localStorage.getItem("cm-active")||"", theme="purple";
let follow=true, lastText="", histIdx=-1;
async function api(path, body){
  const opt = body ? {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)} : {};
  const res = await fetch(path, opt);
  const data = await res.json().catch(()=>({}));
  if(!res.ok) throw new Error(data.error||("HTTP "+res.status));
  return data;
}
function showErr(e){ $("err").textContent = e ? String(e.message||e) : ""; }
function atBottom(){ return out.scrollHeight - out.scrollTop - out.clientHeight < 40; }
function setFollow(v){ follow=v; $("latestBtn").classList.toggle("badge", !v); }
out.addEventListener("scroll", ()=>{ setFollow(atBottom()); }, {passive:true});
function scrollStep(dir){ setFollow(false); out.scrollBy({top: dir*out.clientHeight*0.75, behavior:"smooth"}); }
$("upBtn").onclick=()=>scrollStep(-1);
$("downBtn").onclick=()=>{ scrollStep(1); };
$("latestBtn").onclick=()=>{ setFollow(true); out.scrollTop=out.scrollHeight; };
function renderOutput(text){
  // Append-only rendering: adding a text node never moves scrollTop, so a
  // refresh while you are reading old output cannot snap you to the bottom.
  if(text===lastText) return;
  if(lastText && text.startsWith(lastText)){
    out.appendChild(document.createTextNode(text.slice(lastText.length)));
  } else {
    out.textContent=text;
  }
  lastText=text;
  if(follow) out.scrollTop=out.scrollHeight;
}
async function pollOutput(){
  if(!active) return;
  try{
    const data=await api("/api/output?name="+encodeURIComponent(active));
    if(data.name===active) renderOutput(data.output||"");
  }catch(e){ /* transient; next poll retries */ }
}
function renderTabs(){
  const tabs=$("tabs"); tabs.innerHTML="";
  consoles.forEach(c=>{
    const b=document.createElement("button");
    b.className="tab"+(c.name===active?" active":"");
    b.innerHTML="";
    const d=document.createElement("span"); d.className="dot"+(c.status==="running"?"":" off");
    b.appendChild(d); b.appendChild(document.createTextNode(c.name));
    b.onclick=()=>{ active=c.name; localStorage.setItem("cm-active",active); lastText=""; out.textContent=""; setFollow(true); renderTabs(); pollOutput(); };
    tabs.appendChild(b);
  });
  $("termName").textContent = active ? active : "No console";
}
async function pollState(){
  try{
    const data=await api("/api/state");
    consoles=data.consoles||[];
    if(!consoles.some(c=>c.name===active)) active=consoles.length?consoles[0].name:"";
    $("sub").textContent="PC edition " + data.version + " · " + data.platform + " · " + consoles.length + " console(s)";
    renderTabs();
  }catch(e){ showErr(e); }
}
async function init(){
  try{
    const data=await api("/api/state");
    const sel=$("theme");
    (data.themes||[]).forEach(t=>{ const o=document.createElement("option"); o.value=t.id; o.textContent=t.name; sel.appendChild(o); });
    theme=data.theme||"purple"; document.body.dataset.theme=theme; sel.value=theme;
    sel.onchange=async()=>{ try{ await api("/api/theme",{theme:sel.value}); document.body.dataset.theme=sel.value; }catch(e){ showErr(e); } };
    consoles=data.consoles||[];
    if(!consoles.some(c=>c.name===active)) active=consoles.length?consoles[0].name:"";
    renderTabs();
  }catch(e){ showErr(e); }
  pollOutput();
  setInterval(pollOutput, 900);
  setInterval(pollState, 4000);
}
$("cmdForm").addEventListener("submit", async e=>{
  e.preventDefault(); showErr("");
  const command=cmdInput.value; if(!command.trim()||!active) return;
  cmdInput.value=""; histIdx=-1; setFollow(true);
  try{ await api("/api/exec",{name:active,command}); setTimeout(pollOutput,150); }
  catch(e){ showErr(e); }
});
cmdInput.addEventListener("keydown", e=>{
  const c=consoles.find(x=>x.name===active); const hist=c?c.history:[];
  if(e.key==="ArrowUp"&&hist.length){ e.preventDefault(); histIdx=histIdx<0?hist.length-1:Math.max(0,histIdx-1); cmdInput.value=hist[histIdx]||""; }
  if(e.key==="ArrowDown"&&hist.length){ e.preventDefault(); histIdx=histIdx<0?-1:histIdx+1; if(histIdx>=hist.length){histIdx=-1;cmdInput.value="";} else cmdInput.value=hist[histIdx]||""; }
});
$("createBtn").onclick=async()=>{ const name=$("newName").value.trim(); if(!name) return;
  try{ await api("/api/create",{name}); $("newName").value=""; active=name; localStorage.setItem("cm-active",active); lastText=""; await pollState(); pollOutput(); }catch(e){ showErr(e); } };
$("deleteBtn").onclick=async()=>{ if(!active) return; if(!confirm("Delete console '"+active+"'? Its saved output/history will be removed.")) return;
  try{ const gone=active; await api("/api/delete",{name:gone}); active=""; lastText=""; out.textContent=""; await pollState(); pollOutput(); }catch(e){ showErr(e); } };
$("clearBtn").onclick=async()=>{ if(!active) return;
  try{ await api("/api/clear",{name:active}); lastText=""; out.textContent=""; }catch(e){ showErr(e); } };
init();
</script>
</body>
</html>
"""


def valid_host_header(value):
    host = (value or "").strip().lower()
    if host.startswith("["):
        hostname = host[1:].split("]", 1)[0]
    else:
        hostname = host.rsplit(":", 1)[0] if ":" in host else host
    if not hostname or hostname in ("localhost",):
        return True
    try:
        address = ipaddress.ip_address(hostname)
        return address.is_loopback or address.is_private or address.is_link_local or address.is_unspecified
    except ValueError:
        pass
    return hostname == socket.gethostname().lower() or hostname.endswith(".local")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _allowed(self):
        if not valid_host_header(self.headers.get("Host", "")):
            self._json({"error": "host not allowed"}, 403)
            return False
        return True

    def _json(self, data, code=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        if "application/json" not in self.headers.get("Content-Type", ""):
            raise ValueError("expected a JSON request")
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("bad request size")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def _fail(self, error):
        if isinstance(error, ValueError):
            self._json({"error": str(error)}, 400)
        else:
            traceback.print_exc()
            self._json({"error": "internal error"}, 500)

    def do_GET(self):
        if not self._allowed():
            return
        parsed = urllib.parse.urlparse(self.path)
        try:
            if parsed.path == "/":
                body = HTML.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif parsed.path == "/api/state":
                self._json({
                    "consoles": MANAGER.state(),
                    "theme": load_theme(),
                    "themes": THEMES,
                    "version": CONSOLE_MANAGER_PC_VERSION,
                    "platform": "Windows" if IS_WINDOWS else "Linux",
                })
            elif parsed.path == "/api/output":
                name = urllib.parse.parse_qs(parsed.query).get("name", [""])[0]
                console = MANAGER.get(name)
                if console is None:
                    self._json({"error": "no such console"}, 404)
                else:
                    self._json({"name": name, "output": console.output()})
            else:
                self._json({"error": "not found"}, 404)
        except Exception as error:  # noqa: BLE001 - report as JSON, keep server up
            self._fail(error)

    def do_POST(self):
        if not self._allowed():
            return
        parsed = urllib.parse.urlparse(self.path)
        try:
            data = self._body()
            name = data.get("name", "")
            if parsed.path == "/api/create":
                MANAGER.create(name)
                self._json({"ok": True})
            elif parsed.path == "/api/delete":
                MANAGER.delete(name)
                self._json({"ok": True})
            elif parsed.path == "/api/clear":
                console = MANAGER.get(name)
                if console is None:
                    self._json({"error": "no such console"}, 404)
                else:
                    console.clear()
                    self._json({"ok": True})
            elif parsed.path == "/api/exec":
                command = data.get("command", "")
                if not isinstance(command, str) or not command.strip() or len(command) > MAX_COMMAND_CHARS:
                    raise ValueError("command is empty or too long")
                console = MANAGER.get(name)
                if console is None:
                    self._json({"error": "no such console"}, 404)
                else:
                    console.execute(command)
                    self._json({"ok": True})
            elif parsed.path == "/api/theme":
                save_theme(data.get("theme", ""))
                self._json({"ok": True})
            else:
                self._json({"error": "not found"}, 404)
        except Exception as error:  # noqa: BLE001
            self._fail(error)


def serve():
    global MANAGER
    MANAGER = ConsoleManager()
    server = http.server.ThreadingHTTPServer((WEB_HOST, WEB_PORT), Handler)
    server.daemon_threads = True
    PID_FILE.write_text(str(os.getpid()), encoding="utf-8")
    print("Console Manager PC %s listening on %s:%d" % (CONSOLE_MANAGER_PC_VERSION, WEB_HOST, WEB_PORT), flush=True)
    for label, url in web_urls():
        print("  %s: %s" % (label, url), flush=True)
    try:
        server.serve_forever()
    finally:
        try:
            PID_FILE.unlink()
        except OSError:
            pass


# --------------------------------------------------------------------------
# Process management + auto-update (used by the `ac` launcher commands)
# --------------------------------------------------------------------------

def _pid_alive(pid):
    if IS_WINDOWS:
        # NOTE: os.kill(pid, 0) is NOT a safe liveness check on Windows (it can
        # generate a Ctrl+C event), so ask the kernel directly instead.
        try:
            import ctypes
            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
            if handle:
                ctypes.windll.kernel32.CloseHandle(handle)
                return True
        except Exception:
            pass
        return False
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def server_pid():
    try:
        pid = int(PID_FILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None
    return pid if _pid_alive(pid) else None


def cmd_start():
    pid = server_pid()
    if pid:
        print("Console Manager is already running (pid %d)." % pid)
    else:
        log = open(LOG_FILE, "a", encoding="utf-8")
        kwargs = dict(stdin=subprocess.DEVNULL, stdout=log, stderr=log, close_fds=True)
        if IS_WINDOWS:
            kwargs["creationflags"] = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        subprocess.Popen([sys.executable, str(APP_PATH), "_serve"], **kwargs)
        for _ in range(50):
            if server_pid():
                break
            time.sleep(0.1)
    print("Console Manager Web UI:")
    for label, url in web_urls():
        print("  %-38s %s" % (label + ":", url))
    print("No password — use only on a trusted network.")
    if os.environ.get("CM_PC_NO_OPEN") != "1":
        try:
            webbrowser.open("http://localhost:%d" % WEB_PORT)
        except Exception:
            pass
    return 0


PRANK_HACK_LINES = [
    "[*] Resolving target device ............ OK",
    "[*] Handshake accepted on port 31337 .... OK",
    "[*] Bypassing firewall [##########] 100%",
    "[*] Injecting payload 0xDEADBEEF ........ OK",
    "[*] Escalating privileges -> ROOT (simulated)",
    "[*] Dumping kernel memory map .......... OK",
    "[*] Spoofing MAC address ............... OK",
    "[*] Tracing packets: 10.13.37.1 -> 172.16.66.6 -> [REDACTED]",
    "[*] Decrypting secure enclave .......... 98%",
    "[*] Installing backdoor module ......... OK",
    "[*] Exfiltrating 4.2 TB of totally-real data ...",
    "[*] Wiping access logs ................. OK",
    "[*] ACCESS GRANTED",
]


def cmd_prank():
    """A clearly-finished-as-a-joke fake 'hacking' animation.

    Pure terminal theatre: it prints text and sleeps. It never reads files,
    never touches the network, and never runs a single system command — and
    it ends by telling the 'victim' it was a prank.
    """
    try:
        setup_seconds = float(os.environ.get("CM_PRANK_SETUP_SECONDS", "20"))
    except ValueError:
        setup_seconds = 20
    setup_seconds = min(60.0, max(0.0, setup_seconds))
    tty = sys.stdout.isatty()
    frames = "|/-\\"
    try:
        deadline = time.time() + setup_seconds
        index = 0
        while time.time() < deadline:
            if tty:
                sys.stdout.write("\r%s Setting up everything..." % frames[index % len(frames)])
                sys.stdout.flush()
            index += 1
            time.sleep(0.12)
        if tty:
            sys.stdout.write("\r" + " " * 30 + "\r")
        red = "\033[1;31m" if tty else ""
        green = "\033[1;32m" if tty else ""
        reset = "\033[0m" if tty else ""
        print("%sHACKING DEVICE...%s" % (red, reset))
        time.sleep(0.6)
        for line in PRANK_HACK_LINES:
            print("%s%s%s" % (green, line, reset))
            time.sleep(0.35)
        time.sleep(0.8)
        print()
        print("=" * 58)
        print("  PRANK! Nothing was hacked. 😂")
        print("  This was just Console Manager's fake joke screen —")
        print("  no files, accounts or devices were touched. Gotcha!")
        print("=" * 58)
        return 0
    except KeyboardInterrupt:
        print("\nPrank cancelled — nothing was hacked, it was only a joke screen.")
        return 130


def cmd_stop():
    pid = server_pid()
    if not pid:
        print("Console Manager Web UI is not running.")
        return 0
    if IS_WINDOWS:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        os.kill(pid, signal.SIGTERM)
        for _ in range(30):
            if not server_pid():
                break
            time.sleep(0.1)
    try:
        PID_FILE.unlink()
    except OSError:
        pass
    print("Console Manager Web UI stopped. Saved consoles/history were kept.")
    return 0


def do_update(force=False, quiet=False):
    """Download the PC app from GitHub main, validate it, swap it in atomically."""
    current = APP_PATH.read_bytes() if APP_PATH.exists() else b""
    request = urllib.request.Request(APP_URL, headers={"User-Agent": "ConsoleManagerPC-Updater"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(MAX_DOWNLOAD_BYTES + 1)
    if len(data) > MAX_DOWNLOAD_BYTES:
        raise RuntimeError("downloaded file is unexpectedly large")
    if b"CONSOLE_MANAGER_PC_VERSION" not in data or APP_URL.split("/")[-1].encode() not in data:
        raise RuntimeError("downloaded file failed validation")
    compile(data.decode("utf-8"), "console-manager-pc.py", "exec")  # syntax check before swapping
    if hashlib.sha256(data).digest() == hashlib.sha256(current).digest():
        if not quiet:
            print("Console Manager PC is already up to date.")
        return False
    tmp = APP_PATH.with_suffix(".py.tmp")
    tmp.write_bytes(data)
    os.replace(tmp, APP_PATH)
    if not quiet:
        print("Console Manager PC updated from the main GitHub branch.")
    if server_pid():
        if quiet:
            os.environ["CM_PC_NO_OPEN"] = "1"  # a background update must never pop open windows
        cmd_stop()
        cmd_start()
    return True


def _log_update(message):
    try:
        with open(UPDATE_LOG, "a", encoding="utf-8") as handle:
            handle.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), message))
    except OSError:
        pass


def quiet_update_check():
    """Background check: throttled, opt-out aware, always silent."""
    try:
        UPDATE_LAST_CHECK.write_text(str(int(time.time())), encoding="utf-8")
    except OSError:
        pass
    if UPDATE_DISABLED.exists() or os.environ.get("AC_DISABLE_AUTO_UPDATE") == "1":
        return
    try:
        changed = do_update(quiet=True)
        _log_update("updated" if changed else "already up to date")
    except Exception as error:  # noqa: BLE001 - background jobs must never crash the CLI
        _log_update("check failed: %s" % error)


def schedule_background_update(argv):
    if argv and argv[0] in ("_serve", "_update-check", "update", "update-on", "update-off", "update-status", "prank"):
        return
    if UPDATE_DISABLED.exists() or os.environ.get("AC_DISABLE_AUTO_UPDATE") == "1":
        return
    try:
        last = int(UPDATE_LAST_CHECK.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        last = 0
    if time.time() - last < UPDATE_INTERVAL:
        return
    kwargs = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
    if IS_WINDOWS:
        kwargs["creationflags"] = 0x00000008 | 0x00000200
    else:
        kwargs["start_new_session"] = True
    try:
        subprocess.Popen([sys.executable, str(APP_PATH), "_update-check"], **kwargs)
    except OSError:
        pass


def usage():
    print(__doc__.split("Installed and driven")[1].split('"""')[0].replace("the small `ac` launcher:", "the `ac` command:").strip())


def main(argv):
    schedule_background_update(argv)
    command = argv[0] if argv else "start"
    args = argv[1:]
    try:
        if command == "_serve":
            serve()
            return 0
        if command == "_update-check":
            quiet_update_check()
            return 0
        if command == "start":
            return cmd_start()
        if command == "stop":
            return cmd_stop()
        if command == "restart":
            cmd_stop()
            return cmd_start()
        if command == "status":
            pid = server_pid()
            print("Web UI: %s" % ("running (pid %d)" % pid if pid else "stopped"))
            for label, url in web_urls():
                print("  %-38s %s" % (label + ":", url))
            print("Auto-update: %s (main GitHub branch, every 6 hours when you use ac)"
                  % ("OFF" if UPDATE_DISABLED.exists() else "ON"))
            return 0
        if command == "urls":
            for label, url in web_urls():
                print("  %-38s %s" % (label + ":", url))
            return 0
        if command == "open":
            return cmd_start()
        if command == "list":
            # Listing works from the saved registry even while the UI is stopped.
            names = Console._load_json(CONSOLES_FILE, ["main"])
            for name in names:
                print("  %s" % name)
            return 0
        if command in ("create", "delete", "clear", "exec"):
            print("Run that from the Web UI, or start it first with: ac")
            return 1
        if command == "update":
            do_update(force=True)
            return 0
        if command == "update-on":
            UPDATE_DISABLED.unlink(missing_ok=True)
            print("Background updates enabled (main GitHub branch, every 6 hours).")
            return 0
        if command == "update-off":
            UPDATE_DISABLED.write_text("off\n", encoding="utf-8")
            print("Background updates disabled. Run 'ac update-on' to enable them again.")
            return 0
        if command == "update-status":
            print("Background updates: %s" % ("OFF" if UPDATE_DISABLED.exists() else "ON (main GitHub branch, every 6 hours)"))
            try:
                last = int(UPDATE_LAST_CHECK.read_text(encoding="utf-8").strip())
                print("Last check: %s" % time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(last)))
            except (OSError, ValueError):
                print("Last check: not yet")
            print("Update log: %s" % UPDATE_LOG)
            return 0
        if command == "prank":
            return cmd_prank()
        if command == "theme":
            if args:
                save_theme(args[0])
                print("Saved theme: %s" % args[0])
            else:
                print("Current theme: %s" % load_theme())
                print("Available: %s" % " ".join(sorted(THEME_IDS)))
            return 0
        if command in ("help", "--help", "-h"):
            usage()
            return 0
        print("Unknown command: %s" % command)
        usage()
        return 1
    except Exception as error:  # noqa: BLE001
        print("ERROR: %s" % error)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

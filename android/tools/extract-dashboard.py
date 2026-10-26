#!/usr/bin/env python3
"""Regenerate src/com/nishgamerrz/nishgamerr/Dashboard.java from console manager.txt.

The dashboard is copied byte for byte. The only additions are one clearly
marked <section> for the things the original does not have - the devices that
signed in, the updater and the Termux userland - and the __INITIAL_THEME__
placeholder, which the server fills in from the saved style.

    python3 tools/extract-dashboard.py
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
REPO = os.path.dirname(ANDROID)
SOURCE = os.path.join(REPO, "console manager.txt")
TARGET = os.path.join(ANDROID, "src", "com", "nishgamerrz", "nishgamerr", "Dashboard.java")

EXTRA = r'''<!-- Nishgamerr addition: not part of the reference dashboard -->
<section class="panel" id="nishExtras" style="margin-top:16px">
  <div class="panel-head">
    <h2>Termux userland</h2>
    <span class="mini-badge" id="nishTermuxBadge">&#8230;</span>
  </div>
  <div style="padding:0 18px 14px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
    <span style="color:var(--muted);font-size:12.5px;flex:1 1 300px;overflow-wrap:anywhere" id="nishTermuxText">Checking&#8230;</span>
    <button class="btn btn-ghost" type="button" id="nishTermuxButton">Reinstall Termux</button>
  </div>
  <pre style="margin:0;padding:0 18px 14px;color:var(--muted);font:11.5px/1.45 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere;max-height:120px;overflow:auto" id="nishTermuxLog"></pre>
  <div class="panel-head" style="border-top:1px solid var(--line)">
    <h2>Devices that logged in</h2>
    <span class="mini-badge" id="nishDeviceCount">&#8230;</span>
  </div>
  <div style="padding:0 18px 14px;overflow-x:auto">
    <table style="width:100%;border-collapse:collapse;font-size:12.5px">
      <thead><tr style="color:var(--muted);text-align:left">
        <th style="padding:8px 10px 8px 0;font-weight:600">Device</th>
        <th style="padding:8px 10px 8px 0;font-weight:600">IP</th>
        <th style="padding:8px 10px 8px 0;font-weight:600">Last seen</th>
        <th style="padding:8px 0;font-weight:600">Requests</th>
      </tr></thead>
      <tbody id="nishDeviceRows"><tr><td colspan="4" style="color:var(--muted);padding:10px 0">Loading&#8230;</td></tr></tbody>
    </table>
  </div>
  <div class="panel-head" style="border-top:1px solid var(--line)">
    <h2>App updates</h2>
    <span class="mini-badge" id="nishUpdateBadge">&#8230;</span>
  </div>
  <div style="padding:0 18px 18px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
    <span style="color:var(--muted);font-size:12.5px;flex:1 1 260px" id="nishUpdateText">Checking the main branch&#8230;</span>
    <button class="btn btn-ghost" type="button" id="nishUpdateButton">Check for updates</button>
  </div>
</section>
<script>
(function () {
  const rows = document.getElementById('nishDeviceRows');
  const count = document.getElementById('nishDeviceCount');
  const badge = document.getElementById('nishUpdateBadge');
  const text = document.getElementById('nishUpdateText');
  const button = document.getElementById('nishUpdateButton');
  const termuxBadge = document.getElementById('nishTermuxBadge');
  const termuxText = document.getElementById('nishTermuxText');
  const termuxLog = document.getElementById('nishTermuxLog');
  const termuxButton = document.getElementById('nishTermuxButton');

  function ago(ms) {
    if (!isFinite(ms) || ms < 0) return 'now';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + 's ago';
    if (s < 3600) return Math.round(s / 60) + 'm ago';
    if (s < 86400) return Math.round(s / 3600) + 'h ago';
    return Math.round(s / 86400) + 'd ago';
  }

  async function loadDevices() {
    try {
      const response = await fetch('/api/devices', { cache: 'no-store' });
      const data = await response.json();
      const list = data.devices || [];
      count.textContent = String(list.length);
      rows.replaceChildren();
      if (!list.length) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 4; td.style.color = 'var(--muted)'; td.style.padding = '10px 0';
        td.textContent = 'Nobody has signed in yet.';
        tr.append(td); rows.append(tr); return;
      }
      for (const device of list) {
        const tr = document.createElement('tr');
        const agent = (device.agent || 'unknown device').slice(0, 60);
        for (const value of [agent, device.ip, ago(device.last_seen_ms), String(device.hits)]) {
          const td = document.createElement('td');
          td.style.padding = '8px 10px 8px 0';
          td.style.borderTop = '1px solid var(--line)';
          td.style.overflowWrap = 'anywhere';
          td.textContent = value;
          tr.append(td);
        }
        rows.append(tr);
      }
    } catch (error) { /* the app is probably restarting */ }
  }

  async function loadTermux() {
    try {
      const response = await fetch('/api/termux', { cache: 'no-store' });
      const data = await response.json();
      if (data.ready) {
        termuxBadge.textContent = 'real termux';
        termuxText.textContent = data.state + ' - bash, coreutils and pkg install are live.';
      } else if ((data.percent || 0) > 0 && (data.percent || 0) < 100) {
        termuxBadge.textContent = (data.percent || 0) + '%';
        termuxText.textContent = (data.step || 'Installing Termux') + ' (' + (data.percent || 0) + '%)';
      } else if (data.error) {
        termuxBadge.textContent = 'failed';
        termuxText.textContent = 'Fell back to the bundled shell: ' + data.error;
      } else {
        termuxBadge.textContent = 'bundled shell';
        termuxText.textContent = data.state || 'Bundled shell in use.';
      }
      termuxLog.textContent = data.log || '';
      termuxButton.disabled = (data.percent || 0) > 0 && (data.percent || 0) < 100;
    } catch (error) { /* ignore */ }
  }

  async function loadUpdate() {
    try {
      const response = await fetch('/api/update', { cache: 'no-store' });
      const data = await response.json();
      const state = data.state || 'idle';
      badge.textContent = data.current ? 'v' + data.current : 'v?';
      if (state === 'downloading') {
        text.textContent = 'Downloading update ' + (data.percent || 0) + '%' + (data.latest ? ' (v' + data.latest + ')' : '');
        badge.textContent = (data.percent || 0) + '%';
      } else if (state === 'ready') {
        text.textContent = 'Update v' + data.latest + ' downloaded - press Install on the TV to finish.';
        badge.textContent = 'update ready';
      } else if (state === 'installing') {
        text.textContent = 'Installer opened on the TV - confirm the update there.';
        badge.textContent = 'installing';
      } else if (state === 'failed') {
        text.textContent = 'Update failed: ' + (data.error || 'unknown error');
        badge.textContent = 'failed';
      } else if (state === 'uptodate') {
        text.textContent = 'You are on the newest build (v' + data.current + ').';
        badge.textContent = 'up to date';
      } else if (state === 'checking') {
        text.textContent = 'Checking the main branch for a newer build...';
        badge.textContent = 'checking';
      } else {
        text.textContent = 'Runs quietly in the background and updates itself from the main branch.';
      }
      button.disabled = state === 'checking' || state === 'downloading' || state === 'installing';
    } catch (error) { /* ignore */ }
  }

  button.addEventListener('click', async () => {
    button.disabled = true;
    try { await fetch('/api/update/check', { method: 'POST' }); } catch (error) { /* ignore */ }
    setTimeout(loadUpdate, 300);
  });

  termuxButton.addEventListener('click', async () => {
    termuxButton.disabled = true;
    termuxText.textContent = 'Reinstalling the Termux userland…';
    try { await fetch('/api/termux/install', { method: 'POST' }); } catch (error) { /* ignore */ }
    setTimeout(loadTermux, 400);
  });

  loadDevices(); loadUpdate(); loadTermux();
  setInterval(loadDevices, 5000);
  setInterval(loadUpdate, 2000);
  setInterval(loadTermux, 2500);
})();
</script>
'''


def java_literal(text):
    out = []
    for ch in text:
        code = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif code < 32 or code > 126:
            out.append("\\u%04x" % code)
        else:
            out.append(ch)
    return "".join(out)


def main():
    source = open(SOURCE, encoding="utf-8").read()
    marker = "HTML = r'''"
    start = source.index(marker) + len(marker)
    end = source.index("'''", start)
    html = source[start:end]

    if html.count("</body>") != 1:
        sys.exit("the reference dashboard no longer ends with a single </body>")
    html = html.replace("</body>", EXTRA + "</body>")

    lines = html.split("\n")
    chunks, current = [], ""
    for index, line in enumerate(lines):
        piece = java_literal(line) + ("\\n" if index < len(lines) - 1 else "")
        if len(current) + len(piece) > 100 and current:
            chunks.append(current)
            current = ""
        current += piece
    if current:
        chunks.append(current)
    body = "\n            + ".join('"%s"' % chunk for chunk in chunks)

    java = '''package com.nishgamerrz.nishgamerr;

/**
 * The web dashboard, copied verbatim from the user's console manager.txt.
 *
 * Regenerate with tools/extract-dashboard.py - do not hand-edit the HTML below.
 * Only two things differ from the original file:
 *   - the __INITIAL_THEME__ placeholder is filled from the saved preference,
 *   - a clearly marked section at the end shows the Termux userland, the
 *     devices that logged in and the state of the in-app updater.
 */
final class Dashboard {

    /** id, label, blurb - the same eight styles the original offers. */
    static final String[][] THEMES = {
            {"purple", "Purple", "Rich violet"},
            {"midnight", "Midnight", "Balanced blue"},
            {"ocean", "Ocean", "Cool cyan"},
            {"emerald", "Emerald", "Forest green"},
            {"sunset", "Sunset", "Warm coral"},
            {"cyber", "Cyberpunk", "Electric neon"},
            {"mono", "Monochrome", "Minimal grey"},
            {"contrast", "High Contrast", "Maximum readability"},
    };

    /** What the original falls back to until the user picks a style. */
    static final String DEFAULT_THEME = "purple";

    private Dashboard() {
    }

    static boolean isTheme(String id) {
        if (id == null) return false;
        for (String[] theme : THEMES) {
            if (theme[0].equals(id)) return true;
        }
        return false;
    }

    static String html(String theme) {
        return HTML.replace("__INITIAL_THEME__", isTheme(theme) ? theme : DEFAULT_THEME);
    }

    private static final String HTML =
            %s;
}
''' % body

    open(TARGET, "w", encoding="utf-8").write(java)
    print("wrote %s (%d bytes, %d chunks, html %d bytes)" % (
        os.path.relpath(TARGET, REPO), len(java), len(chunks), len(html)))


if __name__ == "__main__":
    main()

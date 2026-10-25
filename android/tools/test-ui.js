#!/usr/bin/env node
/**
 * test-ui.js — headless checks for the screens in assets/index.html.
 *
 * There is no browser (and no emulator) in the build sandbox, so this runs the
 * app's real <script> against a tiny fake DOM and a fake `window.And` bridge,
 * then asserts that the remote-friendly UI calls the right native methods.
 *
 *   node tools/test-ui.js
 */
"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const HERE = __dirname;
const HTML = fs.readFileSync(path.join(HERE, "..", "assets", "index.html"), "utf8");
const CODE = HTML.match(/<script>([\s\S]*?)<\/script>/)[1];

let passes = 0, failures = 0;
function check(name, cond, detail) {
  if (cond) { passes++; console.log("  \u2713 " + name); }
  else { failures++; console.log("  \u2717 " + name + (detail ? "  \u2192 " + detail : "")); }
}

/* ── fake DOM, built from the real markup ─────────────────────────────── */
function collectIds(html) {
  const ids = new Set();
  const re = /\sid="([^"]+)"/g;
  let m;
  while ((m = re.exec(html))) ids.add(m[1]);
  return Array.from(ids);
}

function makeEl(id, attrs) {
  const el = {
    id: id || "", attrs: attrs || {}, value: "", textContent: "", className: "",
    checked: false, focused: false, style: {}, _handlers: {},
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); },
    fire(type, ev) {
      (this._handlers[type] || []).forEach((fn) =>
        fn.call(this, Object.assign({ preventDefault() {}, key: "" }, ev || {})));
    },
    focus() { this.focused = true; },
    select() {},
    getAttribute(n) { return this.attrs[n] === undefined ? null : this.attrs[n]; },
    setAttribute(n, v) { this.attrs[n] = String(v); },
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); },
      remove(c) { this._s.delete(c); },
      contains(c) { return this._s.has(c); },
    },
  };
  return el;
}

function boot(opts) {
  opts = opts || {};
  const els = {};
  collectIds(HTML).forEach((id) => { els[id] = makeEl(id); });

  // tabs / action buttons, parsed from the markup so the harness cannot drift
  const tabEls = [];
  const tabRe = /data-pane="([^"]+)"/g;
  let m;
  while ((m = tabRe.exec(HTML))) tabEls.push(makeEl("tab-" + m[1], { "data-pane": m[1] }));
  const actEls = [];
  const actRe = /data-act="([^"]+)"/g;
  while ((m = actRe.exec(HTML))) actEls.push(makeEl("act-" + m[1], { "data-act": m[1] }));
  // panes: class switching happens via $("pane-" + name)
  ["home", "tvhost", "remote", "settings"].forEach((p) => {
    if (!els["pane-" + p]) els["pane-" + p] = makeEl("pane-" + p);
  });

  const store = Object.assign({}, opts.storage || {});
  const session = Object.assign({}, opts.session || {});
  const ls = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: (k) => { delete store[k]; },
  };

  const calls = [];
  const bridgeOpts = opts.bridge || {};
  const bridge = bridgeOpts.disabled ? undefined : {
    load: (u) => calls.push(["load", u]),
    open: (u) => calls.push(["open", u]),
    copy: (t) => calls.push(["copy", t]),
    toast: (t) => calls.push(["toast", t]),
    ping: (u) => calls.push(["ping", u]),
    openTermux: () => calls.push(["openTermux"]),
    getLanAddress: () => (bridgeOpts.lan === undefined ? "192.168.1.50" : bridgeOpts.lan),
    getLaunchUrl: () => bridgeOpts.launchUrl || "",
    isTv: () => bridgeOpts.tv !== false,
    getStartOnBoot: () => !!bridgeOpts.startOnBoot,
    setStartOnBoot: (v) => calls.push(["setStartOnBoot", !!v]),
    runConsoleCommand: (cmd) => {
      calls.push(["cmd", cmd]);
      if (typeof bridgeOpts.termux === "function") return bridgeOpts.termux(cmd);
      return bridgeOpts.termux === undefined ? "sent" : bridgeOpts.termux;
    },
  };

  const docHandlers = {};
  const sandbox = {
    console,
    document: {
      getElementById: (id) => els[id] || null,
      querySelectorAll: (sel) =>
        sel === "[data-act]" ? actEls : sel === "[data-pane]" ? tabEls : [],
      addEventListener(t, fn) { (docHandlers[t] = docHandlers[t] || []).push(fn); },
      fire(t, ev) { (docHandlers[t] || []).forEach((fn) => fn(ev || {})); },
    },
    localStorage: ls,
    sessionStorage: {
      getItem: (k) => (k in session ? session[k] : null),
      setItem: (k, v) => { session[k] = String(v); },
      removeItem: (k) => { delete session[k]; },
    },
    navigator: { clipboard: { writeText: () => Promise.resolve() } },
    Image: class { set src(_v) {} },
    setTimeout, clearTimeout, Date, Math, JSON, String, Number, Array, Object, RegExp,
  };
  sandbox.window = sandbox;
  sandbox.window.location = { href: "file:///android_asset/index.html" };
  if (bridge) sandbox.And = bridge;

  vm.createContext(sandbox);
  vm.runInContext(CODE, sandbox);

  const byId = (id) => els[id];
  return { els, tabEls, actEls, store, session, calls, sandbox, bridge, byId };
}

/* ── normalisation / launch ───────────────────────────────────────────── */
console.log("\nPHONE + TV shell");
{
  const app = boot();
  const e = app.els;

  check("boots without throwing", true);
  check("lan address is detected from the bridge", e.hosturl.textContent === "http://192.168.1.50:5000",
        e.hosturl.textContent);
  check("setup step shows the same address", e.stepurl.textContent === "http://192.168.1.50:5000",
        e.stepurl.textContent);
  check("install command is shown in the TV host tab",
        /tv-autostart\.txt/.test(e.installcmd.textContent));
  check("device info knows this is a TV", /TV|Leanback|TV \/ Google/i.test(e.deviceinfo.textContent),
        e.deviceinfo.textContent);
  check("termux state was probed", app.calls.some(([m]) => m === "cmd"), JSON.stringify(app.calls));
  check("start-on-boot follows the stored preference", e.boot.checked === false);
}

{
  // D-pad / remote friendly: bare host, https, javascript: rejection
  const app = boot({ storage: { "rd.hostIp": "10.0.0.7" } });
  const e = app.els;

  e.url.value = "example.com/dash";
  e.open.fire("click");
  check("bare host is opened with a scheme",
        app.calls.some(([m, u]) => m === "load" && u === "http://example.com/dash"),
        JSON.stringify(app.calls));
  check("dashboard url is remembered", app.store["rd.url"] === "http://example.com/dash");

  app.calls.length = 0;
  e.url.value = "javascript:alert(1)";
  e.open.fire("click");
  check("javascript: is refused", app.calls.length === 0, JSON.stringify(app.calls));
  check("the refusal is visible", e.urlwarn.style.display === "block", e.urlwarn.style.display);

  app.calls.length = 0;
  e.url.value = "https://relay.example.org/dashboard.html";
  e.open.fire("click");
  check("https is kept as-is",
        app.calls.some(([m, u]) => m === "load" && u === "https://relay.example.org/dashboard.html"),
        JSON.stringify(app.calls));
}

{
  const tv = boot({ storage: {} });
  check("an unconfigured TV opens on the TV host tab",
        tv.els["pane-tvhost"].className.indexOf("active") >= 0 ||
        tv.store["rd.pane"] === "tvhost", "pane=" + tv.store["rd.pane"]);
}

/* ── tv host tab ──────────────────────────────────────────────────────── */
console.log("\nTV host tab");
{
  const app = boot({ storage: { "rd.hostIp": "" } });
  const e = app.els;

  app.tabEls.find((t) => t.attrs["data-pane"] === "tvhost").fire("click");
  check("tab click activates the TV host pane",
        e["pane-tvhost"].className.indexOf("active") >= 0, e["pane-tvhost"].className);
  check("only one tab is selected at a time",
        app.tabEls.filter((t) => t.attrs["aria-selected"] === "true").length === 1);

  e.hostip.value = "192.168.0.31";
  e.hostip.fire("input");
  check("editing the address rebuilds the host URL",
        e.hosturl.textContent === "http://192.168.0.31:5000", e.hosturl.textContent);

  e.hostsave.fire("click");
  check("address is saved for next launch", app.store["rd.hostIp"] === "192.168.0.31",
        app.store["rd.hostIp"]);

  app.calls.length = 0;
  e.hostcopy.fire("click");
  check("copy sends the host URL to the clipboard",
        app.calls.some(([m, u]) => m === "copy" && u === "http://192.168.0.31:5000"),
        JSON.stringify(app.calls));

  app.calls.length = 0;
  e.hostcheck.fire("click");
  check("host check pings the host URL",
        app.calls.some(([m, u]) => m === "ping" && u === "http://192.168.0.31:5000"),
        JSON.stringify(app.calls));
  app.sandbox.window.__relayPing(true, 200, 42);
  check("a good ping marks the host online", /HTTP 200/.test(e.hostconn.textContent),
        e.hostconn.textContent);
  app.sandbox.window.__relayPing(false, 0, 9000);
  check("a bad ping explains how to fix it",
        /Start console host|No answer/i.test(e.hostconn.textContent), e.hostconn.textContent);

  app.calls.length = 0;
  e.hostopen.fire("click");
  check("open on this TV loads the host page",
        app.calls.some(([m, u]) => m === "load" && u === "http://192.168.0.31:5000"),
        JSON.stringify(app.calls));
}

/* ── remote tab: termux bridge ───────────────────────────────────────── */
console.log("\nRemote (Termux) tab");
{
  const app = boot();
  const e = app.els;

  app.calls.length = 0;
  e.runinstall.fire("click");
  const install = app.calls.find(([m]) => m === "cmd");
  check("setup button hands the tv-autostart command to Termux",
        install && /urlretrieve/.test(install[1]) && /tv-autostart\.txt/.test(install[1]),
        install && install[1]);
  check("the result is reported to the user", /sent to Termux/i.test(e.remoteout.textContent),
        e.remoteout.textContent);

  app.calls.length = 0;
  e["ac-start"].fire("click");
  check("start host prefers the tv-autostart script",
        app.calls.some(([m, c]) => m === "cmd" && /tv-autostart\.txt start/.test(c)),
        JSON.stringify(app.calls));

  app.calls.length = 0;
  e["ac-urls"].fire("click");
  check("show URLs falls back to ac webui-urls",
        app.calls.some(([m, c]) => m === "cmd" && /webui-urls/.test(c)),
        JSON.stringify(app.calls));

  app.calls.length = 0;
  e["ac-app"].fire("click");
  check("open Termux app uses the launch intent",
        app.calls.some(([m]) => m === "openTermux"), JSON.stringify(app.calls));

  app.calls.length = 0;
  e.cmd.value = "screen -ls";
  e.cmdsend.fire("click");
  check("the command box sends exactly what was typed",
        app.calls.some(([m, c]) => m === "cmd" && c === "screen -ls"), JSON.stringify(app.calls));

  e.cmdreset.fire("click");
  check("reset restores the default command", e.cmd.value === "bash cm", e.cmd.value);
}

{
  const noTermux = boot({ bridge: { termux: "no-termux" } });
  noTermux.els.runinstall.fire("click");
  check("missing Termux is explained, not silently ignored",
        /Termux is not installed/i.test(noTermux.els.remoteout.textContent),
        noTermux.els.remoteout.textContent);

  const noPerm = boot({ bridge: { termux: "failed" } });
  noPerm.els.runinstall.fire("click");
  check("blocked commands point at allow-external-apps",
        /allow-external-apps/.test(noPerm.els.remoteout.textContent),
        noPerm.els.remoteout.textContent);

  const pending = boot({ bridge: { termux: "permission-requested" } });
  pending.els.runinstall.fire("click");
  check("a permission prompt is surfaced to the user",
        /allow it, then tap/i.test(pending.els.remoteout.textContent),
        pending.els.remoteout.textContent);
}

/* ── startup / boot ──────────────────────────────────────────────────── */
console.log("\nStartup behaviour");
{
  const booted = boot({ bridge: { launchUrl: "http://192.168.1.50:5000/" } });
  check("a URL handed over by the boot receiver is opened straight away",
        booted.calls.some(([m, u]) => m === "load" && u === "http://192.168.1.50:5000/"),
        JSON.stringify(booted.calls));
  check("and it is remembered as the dashboard URL",
        booted.store["rd.url"] === "http://192.168.1.50:5000/", booted.store["rd.url"]);
}

{
  const app = boot({ bridge: { startOnBoot: true } });
  check("the boot switch reflects the native preference", app.els.boot.checked === true);

  app.els.boot.checked = false;
  app.els.boot.fire("change");
  check("turning the switch off is written to the native side",
        app.calls.some(([m, v]) => m === "setStartOnBoot" && v === false),
        JSON.stringify(app.calls));
  app.els.boot.checked = true;
  app.els.boot.fire("change");
  check("turning it back on is written too",
        app.calls.some(([m, v]) => m === "setStartOnBoot" && v === true),
        JSON.stringify(app.calls));
}

{
  const app = boot({
    storage: { "rd.url": "https://relay.example.org/dash.html", "rd.auto": "1" },
  });
  check("saved URL is restored into the field",
        app.els.url.value === "https://relay.example.org/dash.html", app.els.url.value);
  check("auto-open is remembered", app.els.auto.checked === true);
}

/* ── browser preview (no bridge) ─────────────────────────────────────── */
console.log("\nBrowser preview (no bridge)");
{
  const app = boot({ bridge: { disabled: true } });
  const e = app.els;
  check("no native bridge is tolerated", /bridge|Browser/i.test(e.deviceinfo.textContent),
        e.deviceinfo.textContent);
  check("termux panel explains it needs the app",
        /browser preview/i.test(e.termuxstate.textContent), e.termuxstate.textContent);
  e.url.value = "relay.example.org";
  e.open.fire("click");
  check("navigation falls back to location.href",
        app.sandbox.window.location.href === "http://relay.example.org",
        app.sandbox.window.location.href);
}

setTimeout(() => {
  console.log("\n" + passes + " passed, " + failures + " failed\n");
  process.exit(failures ? 1 : 0);
}, 120);

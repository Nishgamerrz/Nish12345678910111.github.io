#!/usr/bin/env node
/**
 * test-ui.js — headless checks for the screens in assets/index.html.
 *
 * There is no browser (and no emulator) in the build sandbox, so this runs the
 * app's real <script> against a tiny fake DOM and a fake `window.And` bridge,
 * then asserts that tapping things calls the right native methods.
 *
 *   node tools/test-ui.js
 */
"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const HTML = fs.readFileSync(path.join(__dirname, "..", "assets", "index.html"), "utf8");
const CODE = HTML.match(/<script>([\s\S]*?)<\/script>/)[1];

let failures = 0;
let passes = 0;
function check(name, cond, detail) {
  if (cond) { passes++; console.log("  \u2713 " + name); }
  else { failures++; console.log("  \u2717 " + name + (detail ? "  \u2192 " + detail : "")); }
}

/* ── fake DOM ─────────────────────────────────────────────────────────── */
function makeEl(id, attrs) {
  const el = {
    id, attrs: attrs || {}, value: "", textContent: "", className: "",
    checked: false, focused: false, _handlers: {},
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); },
    fire(type, ev) {
      (this._handlers[type] || []).forEach((fn) =>
        fn.call(this, Object.assign({ preventDefault() {}, key: "" }, ev || {})));
    },
    focus() { this.focused = true; },
    select() {},
    getAttribute(n) { return this.attrs[n]; },
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); },
      remove(c) { this._s.delete(c); },
      contains(c) { return this._s.has(c); },
      toggle(c) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); },
    },
  };
  return el;
}

const IDS = ["url", "open", "save", "auto", "status", "conn", "check", "browser", "urlwarn", "sub", "foot"];
const ACTS = ["reload", "copy", "clear", "about"];

function boot(opts) {
  opts = opts || {};
  const els = {};
  IDS.forEach((id) => { els[id] = makeEl(id); });
  const actionEls = ACTS.map((a) => makeEl("btn-" + a, { "data-act": a }));

  const store = Object.assign({}, opts.storage || {});
  const storage = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: (k) => { delete store[k]; },
  };
  const session = {};

  const bridgeCalls = [];
  const bridge = opts.bridge === false ? undefined : {
    load: (u) => bridgeCalls.push(["load", u]),
    open: (u) => bridgeCalls.push(["open", u]),
    copy: (t) => bridgeCalls.push(["copy", t]),
    toast: (t) => bridgeCalls.push(["toast", t]),
    ping: (u) => bridgeCalls.push(["ping", u]),
  };

  const toasts = [];
  const sandbox = {
    console,
    document: {
      getElementById: (id) => els[id] || null,
      querySelectorAll: (sel) => (sel === "[data-act]" ? actionEls : []),
    },
    localStorage: storage,
    sessionStorage: {
      getItem: (k) => (k in session ? session[k] : null),
      setItem: (k, v) => { session[k] = String(v); },
      removeItem: (k) => { delete session[k]; },
    },
    navigator: {
      clipboard: { writeText: (t) => { toasts.push(t); return Promise.resolve(); } },
    },
    Image: class { set src(_v) {} },
    setTimeout, clearTimeout, Date, Math, JSON, String, Number, Array, Object, RegExp,
    __bridgeCalls: bridgeCalls,
  };
  sandbox.window = sandbox;
  if (bridge) sandbox.And = bridge;
  sandbox.window.location = { href: "file:///android_asset/index.html" };

  vm.createContext(sandbox);
  // keep a handle on the fake global so tests can drive timers
  vm.runInContext(CODE, sandbox);
  return { els, actionEls, store, session, bridgeCalls, sandbox, bridge };
}

/* ── tests ────────────────────────────────────────────────────────────── */
console.log("\nNative-bridge mode");

{
  const app = boot();
  const { els, bridgeCalls } = app;

  check("boots without throwing", true);
  check("first run leaves the URL box empty", els.url.value === "",
        "value=" + JSON.stringify(els.url.value));
  check("first run explains what to do next",
        /paste one above/i.test(els.conn.textContent), els.conn.textContent);
  check("no warning is thrown at the user on first run",
        !els.urlwarn.classList.contains("show"));

  // bare host gets https:// prefixed
  els.url.value = "example.com/dashboard.html";
  els.open.fire("click");
  check("bare host is upgraded to https",
        bridgeCalls.some(([m, u]) => m === "load" && u === "https://example.com/dashboard.html"),
        JSON.stringify(bridgeCalls));
  check("URL is remembered", app.store["rd.url"] === "https://example.com/dashboard.html");

  // javascript: is refused
  bridgeCalls.length = 0;
  els.url.value = "javascript:alert(1)";
  els.open.fire("click");
  check("javascript: URLs are rejected", bridgeCalls.length === 0,
        JSON.stringify(bridgeCalls));
  check("rejection is explained in the UI", els.urlwarn.classList.contains("show"));

  // plain http is allowed (self-hosted relays / LAN)
  bridgeCalls.length = 0;
  els.url.value = "http://192.168.1.5:3000/dashboard.html";
  els.open.fire("click");
  check("http:// on a LAN address is allowed",
        bridgeCalls.some(([m, u]) => m === "load" && u === "http://192.168.1.5:3000/dashboard.html"),
        JSON.stringify(bridgeCalls));

  // connection check -> native ping -> callback
  bridgeCalls.length = 0;
  els.check.fire("click");
  check("Check connection asks the native side to ping",
        bridgeCalls.some(([m, u]) => m === "ping" && u === "http://192.168.1.5:3000/dashboard.html"),
        JSON.stringify(bridgeCalls));
  check("status shows 'checking' while waiting", /checking/.test(els.status.textContent),
        els.status.textContent);

  app.sandbox.window.__relayPing(true, 200, 143);
  check("a good answer marks the app online", els.status.textContent === "online",
        els.status.textContent);
  check("a good answer reports latency", /143 ms/.test(els.conn.textContent), els.conn.textContent);

  app.sandbox.window.__relayPing(false, 0, 8000);
  check("a bad answer marks the app offline", els.status.textContent === "offline",
        els.status.textContent);
  check("the offline message suggests a fix", /relay host is awake|No answer/i.test(els.conn.textContent),
        els.conn.textContent);

  // copy + open externally
  bridgeCalls.length = 0;
  app.actionEls.find((e) => e.attrs["data-act"] === "copy").fire("click");
  check("Copy URL hands the URL to the clipboard bridge",
        bridgeCalls.some(([m, u]) => m === "copy" && u.startsWith("http://192.168.1.5")),
        JSON.stringify(bridgeCalls));
  bridgeCalls.length = 0;
  els.browser.fire("click");
  check("Open in browser uses the native intent bridge",
        bridgeCalls.some(([m]) => m === "open"), JSON.stringify(bridgeCalls));

  // auto-open preference
  els.auto.checked = true;
  els.auto.fire("change");
  check("auto-open preference is saved", app.store["rd.auto"] === "1");

  // two-step clear
  const clearBtn = app.actionEls.find((e) => e.attrs["data-act"] === "clear");
  clearBtn.fire("click");
  check("first Clear tap only asks for confirmation",
        /again/i.test(clearBtn.textContent) && app.store["rd.url"], clearBtn.textContent);
  clearBtn.fire("click");
  check("second Clear tap wipes saved data",
        !app.store["rd.url"] && !app.store["rd.auto"] && els.url.value === "",
        JSON.stringify(app.store));

  // about
  clearBtn.textContent = "Clear saved data";
  app.actionEls.find((e) => e.attrs["data-act"] === "about").fire("click");
  check("About mentions Android 7",
        /Android 7/.test(els.conn.textContent), els.conn.textContent);
}

console.log("\nSaved-URL boot");

{
  const app = boot({ storage: { "rd.url": "https://relay.example.org/dash.html" } });
  check("saved URL is restored into the field",
        app.els.url.value === "https://relay.example.org/dash.html", app.els.url.value);
  check("saved URL is auto-checked on launch",
        app.bridgeCalls.some(([m]) => m === "ping"), JSON.stringify(app.bridgeCalls));
}

{
  const app = boot({
    storage: { "rd.url": "https://relay.example.org/dash.html", "rd.auto": "1" },
  });
  check("auto-open stays put until the timer fires (session guard)",
        !app.session["rd.autoOpened"] || app.session["rd.autoOpened"] === "1");
  setTimeout(() => {
    check("auto-open loads the saved dashboard once",
          app.bridgeCalls.filter(([m]) => m === "load").length === 1,
          JSON.stringify(app.bridgeCalls));

    console.log("\nBrowser-preview mode (no bridge)");
    {
      const b = boot({ bridge: false });
      check("no native bridge is tolerated",
            /Android app/.test(b.els.conn.textContent), b.els.conn.textContent);
      b.els.url.value = "relay.example.org";
      b.els.open.fire("click");
      check("falls back to normal navigation",
            b.sandbox.window.location.href === "https://relay.example.org",
            b.sandbox.window.location.href);
    }

    console.log("\n" + passes + " passed, " + failures + " failed\n");
    process.exit(failures ? 1 : 0);
  }, 300);
}

# Relay Dashboard — one APK, no Termux, no Python

A **~56 KB** Android app that turns a phone or Android TV into a **self-contained
console host**: it runs its own HTTP dashboard in the background, password
protected, and starts itself when the device powers on.

```
com.nishgamerrz.relaydash · v2.0 (versionCode 3) · minSdk 24 · targetSdk 28
leanback + launcher (phone AND Android TV) · signed v2+v3 · ~56 KB
permissions: INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED,
             FOREGROUND_SERVICE, POST_NOTIFICATIONS
```

Nothing else to install. No Termux, no Python, no second APK, no Play Services.

## Install

On the TV (Android 8) open one of these in the TV's browser, or in a
“Downloader” app from the TV store:

```
https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/RelayDashboard.apk
https://nishgamerrz.github.io/RelayDashboard.apk
```

Then allow *install unknown apps* for whatever app downloaded it and press
Install. (Before this PR is merged, the same file is also at
`…/arena/f45e6977-nish12345678910111-github-io/RelayDashboard.apk`.)

Opening the app **is** the setup: the host starts immediately, a random
6-character password is generated, and start-on-boot is already on.

## What the app shows

A single control panel, built for a remote:

| Section | Contents |
|---|---|
| Status | **Running** (big, green) or **Stopped** (red), plus uptime, port and signed-in count |
| Address | `http://192.168.1.50:8080` — selectable, with **Copy address** and **Open in browser** |
| Host | **Start host** / **Stop host**, and **Start automatically when the TV powers on** |
| Password | the saved password, editable, plus **New random**; saving signs every device out |
| Port | 8080 by default, editable, restarts the host on save |
| Devices that opened it | per address: device/browser string, how long ago, request count, **Clear list** |

## What other devices get (the web dashboard)

Open `http://<tv-ip>:8080` from any phone or laptop on the same Wi-Fi:

1. **Login page** — password only, checked on the TV (PBKDF2-HMAC-SHA1, 20 000
   iterations, per-device salt; the clear password never leaves the device).
   Eight wrong tries from one address locks that address out for five minutes.
   Sessions are HttpOnly cookies, 12 h, and die on restart or password change.
2. **Dashboard** — big **Running** status, uptime, the address, a live **console**
   (`/system/bin/sh` as the app's own user — `getprop`, `ls`, `dumpsys`,
   `ping`, `top`…), and the table of **devices that opened this dashboard**.

The console output is capped at 32 KB / 10 s so a stray `cat /dev/zero` cannot
wedge the TV, and every string is escaped before it reaches HTML or JSON.

## Background + boot, honestly

- The server lives in a **foreground service** (`HostService`), because that is
  the only way Android 8+ lets an app keep accepting connections while it is
  closed or the screen is off. A low-priority notification shows the address.
- `BootReceiver` restarts it on `BOOT_COMPLETED` / `QUICKBOOT_POWERON` when
  *Start automatically* is on. Boot is one of the few places Android 8 still
  permits starting a foreground service from the background.
- Ports below 1024 are refused by the app (Android needs root for those).
- The host is **LAN-only by design**: it binds the device's Wi-Fi interface and
  has no port forwarding, no UPnP, and no internet exposure.

## Rebuild it

```bash
cd android
./tools/fetch-toolchain.sh      # JDK, javac, android.jar, dx, apktool, aapt2, apksigner
bash tools/test-server.sh       # 44 assertions against the real HTTP server
./build.sh --clean              # -> dist/RelayDashboard.apk + ../RelayDashboard.apk
```

`build.sh` refuses to finish unless **all** of these hold: javac compiled every
source (one class per file — this caught a silently dropped `MainActivity`),
dex is **format 035**, minSdk 24, TV/leanback declared, banner bundled, boot
receiver and host service in the manifest, no Termux permission, every engine
class and the `/login`, `/api/status`, `/api/run` routes present in the dex, and
a valid **v2 signature**.

### The toolchain, since there is no Android SDK here

| Piece | Source |
|---|---|
| Java runtime | `jdk4py` (pip) |
| `javac` 8 | `tools.jar` from `dataslope-tools-jar` (npm) |
| Android API 26 stubs | `Sable/android-platforms` (git) |
| dexer | AOSP `dx` compiled from source (`aosp-mirror/platform_dalvik`) |
| packaging / signing | `apktool` + `apksigner` (npm), `aapt2` (npm) |

## Design notes worth knowing

- **Why it can be 56 KB:** the server is ~600 lines of plain Java
  (`WebServer`, `Pages`, `Auth`, `DeviceLog`, `CommandRunner`) with no support
  libraries; the UI is native views built in code.
- **Testable core:** every class the server needs is free of `android.*`
  imports, so `tools/test-server.sh` starts the *shipping* server on a desktop
  JVM and drives it with curl — login, lockout, console, timeout, JSON
  escaping, device list, logout, restart, and port-in-use are all asserted
  there, not on a device.
- **Reflection-free** password swap (`WebServer.setAuth`) so a changed password
  takes effect without restarting the listener.
- **No R.java**: the notification uses a framework icon, so resources never
  need a generated R class at compile time.

## Limits (read before filing a bug)

- **Not tested on physical hardware.** This sandbox has no emulator: the APK is
  verified structurally (dex decode of all 27 classes, manifest, resources,
  signature, package badging) and the server logic is covered by the 44
  real-HTTP assertions, but the Android UI/service path can only be proven on
  your TV. If it misbehaves, tell me exactly what the screen says.
- **The console is not Termux.** It runs `/system/bin/sh` as the app's own
  unprivileged user — no `apt`, no `bash`, no root, and commands that need
  another app's data fail. It is for inspecting and poking the device
  (`getprop`, `pm list packages`, `dumpsys`, `logcat -d`…), not for running
  Node/Python servers.
- **Plain HTTP on your Wi-Fi.** The password protects the console, but traffic
  is not encrypted; anyone sniffing your LAN can read console output. Use it on
  a network you trust.
- Android may still kill a foreground service under extreme memory pressure;
  just reopen the app, or leave *Start automatically* on.
- Play Store distribution is not possible with a self-signed sideload key.

## Files

```
RelayDashboard.apk                the app (also in android/dist/)
android/
├── AndroidManifest.xml           permissions, activity, HostService, BootReceiver
├── apktool.yml                   apktool metadata (dex format note)
├── build.sh                      compile · dex · package · check · sign · verify
├── src/com/nishgamerrz/relaydash/
│   ├── MainActivity.java         the TV/phone control panel (native views)
│   ├── HostService.java          foreground service that keeps serving
│   ├── BootReceiver.java         power-on autostart
│   ├── HostState.java            single owner of the running server
│   ├── Prefs.java                password, port, start-on-boot
│   ├── WebServer.java            HTTP/1.1 server, sessions, routes, throttling
│   ├── Pages.java                login + dashboard HTML, escaping, formatting
│   ├── Auth.java                 PBKDF2 storage, constant-time check, tokens
│   ├── DeviceLog.java            who connected, when, how often
│   └── CommandRunner.java        /system/bin/sh with timeout and output cap
├── test/ServerHarness.java       starts the real server on a desktop JVM
├── tools/
│   ├── fetch-toolchain.sh        JDK + javac + android.jar + dx + apktool + aapt2
│   ├── test-server.sh            44 assertions against the real server
│   └── make_icons.py             launcher icons + TV banner
└── keystore/relay-release.keystore   signing key — keep it so updates install
```

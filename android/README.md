# Nishgamerr — one APK, a real Linux shell, no Termux

A **~2.7 MB** Android app that turns a phone or Android TV into a **self-contained
console host**: Binutils-style Linux userland inside the APK, a password
protected web dashboard with **multiple named consoles**, and it starts itself
the moment the TV powers on.

```
com.nishgamerrz.nishgamerr · v2.2 (versionCode 5) · minSdk 24 · targetSdk 28
leanback + launcher (phone AND Android TV) · signed v2+v3 · ~2.6 MB
permissions: INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED,
             FOREGROUND_SERVICE, POST_NOTIFICATIONS, WAKE_LOCK,
             READ/WRITE_EXTERNAL_STORAGE (password vault only)
```

Nothing else to install. No Termux, no Termux:Boot, no Python, no second APK,
no Play Services, no `/system/bin/sh`.

## Install

On the TV (Android 8) open this in the TV's browser, or in a "Downloader" app:

```
https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/Nishgamerr.apk
https://nishgamerrz.github.io/Nishgamerr.apk
```

Allow *install unknown apps* for whatever app downloaded it, press Install, then
**open the app once** — Android does not deliver BOOT_COMPLETED to an app that
has never been launched. After that first launch it comes back on its own.

Opening the app **is** the setup:

1. `assets/busybox/busybox-<abi>` is unpacked into the app's private data dir as
   `files/shell/usr/bin/busybox`, all **342 applets** get symlinks next to it,
   and a `pkg` shim is installed (`pkg install <applet>`, `pkg list`,
   `pkg files <applet>`, `pkg upgrade`). No network, no download, no prompt.
2. The HTTP host starts in a foreground service and shows the address, e.g.
   `http://192.168.1.24:8080`.
3. A random 6-character password is generated and shown on the TV screen.

The shell is BusyBox v1.29.3 ash — **not** the TV's own `/system/bin/sh`, so
`awk`, `sed`, `tar`, `wget`, `vi`, `grep -P`, `find -printf`, … behave the way
they do on a Linux box.

## The dashboard is the console manager's own dashboard

`Dashboard.java` is the **verbatim** HTML/CSS/JS from `console manager.txt`
(tools/extract-dashboard.py regenerates it). Nothing was redesigned: same eight
styles, same stats strip, same console cards, same terminal view, same
`/api/state`, `/api/create`, `/api/delete`, `/api/execute`, `/api/output`,
`/api/suggest`, `/api/preferences` contract. The saved style is written to
`preferences.json` next to the consoles, exactly like the original, and is
baked into `<body data-theme>` on the next load.

Two things are appended in a clearly marked section (the original has neither):
the devices that logged in, and the state of the self-updater.

## Updates itself from main

`Updater` checks `main`'s `Nishgamerr.apk` on launch, at boot and every six
hours through the watchdog job. Every step is announced on the TV (notification
plus the "App updates" card):

    Checking for updates… → Downloading update 43% → Update v2.2 is ready
    → Installer opened → Nishgamerr updated (v2.2)

The download is only accepted if the package name is ours, the version code is
newer, and it is signed with the same key as the running app. The apk is served
to the system installer through a two-method `ContentProvider` (`UpdateProvider`),
because Android 7+ rejects `file://` URIs between apps. Android still requires
one confirmation on the TV to replace an app - that is the "Install update"
press.

## Consoles

The web dashboard has console tabs, like the Console Manager reference: create
(`+ new`), switch, clear and delete consoles. Each console is its own persisted
`sh -i`, so `cd`, exported variables and background jobs survive between
commands. The list is stored in `shell/consoles/consoles.txt` and survives a
reboot.

| Method | Path | What it does |
| --- | --- | --- |
| GET | `/?c=<id>` | dashboard for one console |
| POST | `/login`, `/logout` | session cookie (HttpOnly, PBKDF2 password) |
| POST | `/api/run` | `console`, `cmd` → `{ok, console, output, tail}` |
| GET | `/api/output` | `id`, `lines` → tail of one console |
| POST | `/api/console/new` | `name` → `{ok, id, name}` (max 8) |
| POST | `/api/console/delete` | `id` (the last console cannot be deleted) |
| POST | `/api/console/clear` | `id` |
| GET | `/api/status`, `/api/devices` | host + shell state, devices that logged in |

## Password survives a reinstall

Prefs die with the app, so the password is also mirrored to
`/sdcard/Nishgamerr/password` (`password`, PBKDF2 salt, PBKDF2 hash). Uninstall
wipes `/data` but not the shared volume, so a **re-download and reinstall finds
the old password again** instead of inventing a new one. If the volume is not
writable (direct boot, no permission) the app falls back to private prefs and
never clobbers an existing vault with a freshly generated password.

## Starting when the TV starts

* `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON` (AOSP + HTC) and
  `MY_PACKAGE_REPLACED` are all registered; the receiver is `directBootAware`.
* `HostService` claims its foreground notification **first** and only then
  unpacks the shell and binds the port, so Android 8's 5-second
  `startForegroundService` deadline cannot turn the boot into an ANR.
* `WatchdogJob` re-checks a minute after boot (`nudge`) and then every 15
  minutes, and restarts the host if a vendor launcher killed it.

## Build

```bash
bash tools/fetch-toolchain.sh     # javac/dx/apktool/aapt2/apksigner + android.jar
bash tools/test-server.sh         # 51 assertions against the real server on a JVM
bash build.sh                     # compile → dex → apktool → assets → sign → verify
```

`build.sh` refuses to finish unless the dex really contains every engine class,
the `/api/*` routes, the vault path, the bundled BusyBox for arm/arm64/x86_64,
the TV banner, the boot receiver and the foreground service.

## Layout

```
src/com/nishgamerrz/nishgamerr/
  ShellRuntime.java    unpack busybox, applet links, pkg shim, PATH/HOME/PREFIX
  ShellSession.java    one persistent `sh -i` per console, marker-bounded exec
  ConsoleManager.java  named consoles, persisted, max 8
  HostState.java       the single server instance + background provisioning
  HostService.java     foreground service, notification, START_STICKY
  WatchdogJob.java     JobScheduler watchdog (periodic + post-boot nudge)
  BootReceiver.java    boot / quickboot / package-replaced
  WebServer.java       HTTP, sessions, console APIs, devices
  Pages.java           the dashboard (no CDN, no external assets)
  Prefs.java           port, boot flag, password + /sdcard vault
  Auth.java            PBKDF2-HMAC-SHA256, per-install salt
  DeviceLog.java       who logged in, when, from which IP
  MainActivity.java    TV UI: address, password, port, shell install progress
assets/busybox/        BusyBox v1.29.3-osm0sis for arm, arm64, x86_64
```

Keystore: `keystore/relay-release.keystore` (alias `relaydash`, pass `relaydash`)
— keep it, updates must be signed with the same key.

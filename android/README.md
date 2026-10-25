# Relay Dashboard — Android phone + Android TV

A **~70 KB native Android app** (no Electron, no Cordova, no SDK build) that

- opens your relay dashboard in a native shell,
- **starts itself when the device powers on**,
- turns an Android TV / Google TV into a **small console host** that any device
  on the same Wi-Fi can open in a browser,
- and can **drive Termux from the remote** (start the host, show URLs, run a
  command) without typing on a TV remote.

Download: [`RelayDashboard.apk`](../RelayDashboard.apk) at the repo root.

```
com.nishgamerrz.relaydash · v1.1 (versionCode 2) · minSdk 24 (Android 7.0) · targetSdk 28
leanback + launcher (installs on phone AND TV) · signed v2+v3 · ~70 KB
permissions: INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, Termux RUN_COMMAND (optional)
```

---

## 1. Install on the TV (or the phone)

1. Get `RelayDashboard.apk` onto the TV. Easiest path: **install a "Downloader"
   app** from the TV's app store, then open
   `https://nishgamerrz.github.io/RelayDashboard.apk`.
   (On a phone just open that link in the browser.)
2. Approve **install unknown apps** for the app you downloaded with.
3. Open **Relay Dashboard**. It appears on the TV home screen with its own banner.

There is nothing to configure to just *view* a dashboard: paste a URL, press
**Open dashboard**. The whole UI is remote-friendly — big focus rings, arrow-key
navigation, no hover menus, no tiny targets.

## 2. Make the TV the host (the bit that actually needs setup)

The dashboard host itself runs inside **Termux**, and Termux only starts things
by itself if you install one extra helper app. Three steps, once:

| Step | Where | What |
|---|---|---|
| 1 | TV app store / F-Droid | Install **Termux** (F-Droid build — the Play Store version is abandoned) **and** **Termux:Boot** |
| 2 | Termux | Paste the setup command (below), or use **TV host → Send setup command to Termux** in the app |
| 3 | Termux:Boot | Open it **once** — Android only runs boot hooks for apps that have been launched at least once |

The setup command (the exact text the app sends for you):

```bash
python3 -c 'import urllib.request as u,subprocess as s;u.urlretrieve("https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/tv-autostart.txt","tv");s.run(["bash","tv"],check=True)'
```

[`tv-autostart.txt`](../tv-autostart.txt) then:

1. installs `python`, `screen` (and `curl`) if missing,
2. sets `allow-external-apps=true` in `~/.termux/termux.properties` so the APK
   may send it commands,
3. installs **Console Manager** (your `console manager.txt`) if it is missing,
4. starts its web UI on `0.0.0.0:5000`,
5. writes a **Termux:Boot hook** (`~/.termux/boot/tv-console-host`) that takes a
   wake lock and starts the host a few seconds after the TV powers on,
6. prints the address other devices should use.

Afterwards, from Termux (or from the app's **Remote** tab):

```bash
bash ~/tv-autostart.txt status     # running? which URL?
bash ~/tv-autostart.txt start | stop | restart
bash ~/tv-autostart.txt url        # http://<tv-ip>:5000
bash ~/tv-autostart.txt boot-on    # start with the TV (default after install)
bash ~/tv-autostart.txt boot-off   # don't
bash ~/tv-autostart.txt update     # reinstall/update Console Manager
```

> **Port 5000 on a Chromecast-embedded Android TV:** some Google TV devices run
> their own service on 5000. If the address refuses connections, start the host
> on another port instead — `AC_WEB_PORT=8080 bash ~/tv-autostart.txt start` —
> and put `8080` in the app's **Setup → Server address** hints. (`tv-autostart`
> respects `AC_WEB_PORT`, Console Manager respects `AC_WEB_PORT` too.)
>
> **The host only exists while Termux is alive.** Android may kill it under
> memory pressure; the boot hook plus `termux-wake-lock` makes that rare, and
> **Remote → Start console host** brings it back in one button press.

## 3. Start on boot — what it really does

`RECEIVE_BOOT_COMPLETED` + `BootReceiver` opens the app (`MainActivity`) on
`BOOT_COMPLETED`, `QUICKBOOT_POWERON` and HTC's reboot broadcast. The app then:

- opens a URL handed to it by the boot intent (`?url=` extra / `onNewIntent`), else
- opens the saved dashboard if **“Open this dashboard automatically”** is on, else
- lands on the **TV host** tab and shows the address, so the screen is useful
  straight away.

Turn the behaviour off in **Setup → Start on boot** (stored natively in
`SharedPreferences`, so it also works when the WebView has not loaded yet;
it defaults to **on for TV devices**, **off for phones**).

Android 7+ will not show the app over the lock screen unless the device is set to
do so — on a TV there is no lock screen, so this is a non-issue there.

## 4. The Termux bridge (Remote tab)

| Button | Command it sends |
|---|---|
| Start console host | `bash $HOME/tv-autostart.txt start` → falls back to `ac webui-start` → `bash cm` |
| Show host URLs | `bash $HOME/tv-autostart.txt url` → falls back to `ac webui-urls` |
| Open Termux dashboard | `bash $PREFIX/bin/ac` (or `bash cm`) |
| Open Termux app | launches Termux itself |
| Command box | exactly what you type, nothing else |

How it works: `RUN_COMMAND` intent to `com.termux.app.RunCommandService`
(`/data/data/com.termux/files/usr/bin/bash -c "<command>"`, workdir `$HOME`,
foreground). Requirements:

- Termux installed, and `allow-external-apps=true` in
  `~/.termux/termux.properties` (the setup script does this; restart Termux after),
- the first tap triggers Android's permission dialog for
  `com.termux.permission.RUN_COMMAND` — allow it once.

The app reports exactly which of those is missing instead of failing silently.
No command runs unless you press a button.

## 5. Screens

| Tab | What it does |
|---|---|
| **Dashboard** | URL box (accepts bare hosts, `http://` LAN addresses), Open, Save, auto-open toggle, connection check with latency, copy, reload, clear. |
| **TV host** | Big LAN address (`http://192.168.1.50:5000`), open here / copy / check, the 4-step setup list, and the one-command installer. |
| **Remote** | Termux status, the four host controls, and a free-text command box. |
| **Setup** | Device info (TV detected?, Wi-Fi IP, bridge state), **Start on boot**, editable server address. |

Remote details: arrow keys move focus, Enter activates, Escape jumps back to the
first tab so the remote never gets stuck. Back on the launcher screen exits.

## 6. Build it

```bash
cd android
./tools/fetch-toolchain.sh          # JDK + apktool + aapt2 + apksigner into .tools/ (git-ignored)
./tools/make_icons.py               # icons + TV banner (needs Pillow)
node tools/test-ui.js               # 42 headless UI assertions
bash tools/test-tv-host.sh          # 23 assertions against a simulated Termux
./build.sh --clean                  # -> dist/RelayDashboard.apk + ../RelayDashboard.apk
```

`build.sh` refuses to finish unless all of these hold: **dex 035**, minSdk 24,
leanback/TV support, TV banner, boot receiver, launcher activity, icon, bundled
UI, every Java class present, valid **v2 signature**.

Why no Gradle: the shell is hand-written smali and packaged with
`apktool → apksigner`, which is why the APK is ~70 KB instead of ~5 MB. The dex
is deliberately built at API 23 so it lands in **format 035** — the variant every
Android 7 build accepts, including picky OEM ART.

## 7. Keeping updates installable

The signing key is committed on purpose (`keystore/relay-release.keystore`,
password `relaydash`) so version 1.2 installs **over** 1.1 and keeps your saved
URL and settings. Bump `versionCode` (and `versionName`, plus the copy in
`apktool.yml`) for each build. A self-signed dev key is fine for sideloading and
**not** acceptable for a Play Store release.

## 8. Honest limits

- **Not on the Play Store**, so "unknown sources" prompts are part of life.
- **Tested by inspection + simulation, not on a TV.** This sandbox has no
  emulator: the APK was verified structurally (dex round-trip, manifest,
  signatures, package badging) and all UI/Termux logic by the two test suites. If
  a device shows *“Parse error”*, *“App not installed”*, or the host does not
  come back after a reboot, tell me exactly what it says and it gets rebuilt.
- **Termux on Android TV is awkward but works** — you get no on-screen keyboard
  from the remote in some builds; the app's Remote tab exists precisely so you
  can drive it without typing.
- **The host is plain HTTP on the LAN, no password** — Console Manager's own
  design. Only use it on Wi-Fi you trust; it is not reachable from the internet.
- A TV that fully powers off (not standby) needs the boot hook; a TV in standby
  just keeps Termux running.
- Battery: on a TV the host runs while the screen is on/standby; on a phone
  expect battery use, which is why start-on-boot is off by default there.

## 9. Files

```
RelayDashboard.apk                     the app (repo root, served by GitHub Pages)
tv-autostart.txt                       Termux setup + power-on host hook
android/
├── AndroidManifest.xml                phone+TV package, permissions, boot receiver
├── apktool.yml                        apktool metadata (why dex 035)
├── build.sh                           build · check · sign · verify
├── assets/index.html                  the four screens (offline, remote-friendly)
├── smali/com/nishgamerrz/relaydash/
│   ├── MainActivity.smali             WebView host, Back, launch-URL, onNewIntent
│   ├── MainActivity$1.smali           keep http(s) inside, hand other schemes off
│   ├── JsBridge.smali                 12 JS-callable methods (incl. Termux + LAN IP)
│   ├── JsBridge$1..$7.smali           thread bodies (UI thread / background / perms)
│   ├── BootReceiver.smali             BOOT_COMPLETED → open the app
│   └── Prefs.smali                    start-on-boot preference, TV detection
├── res/mipmap-*/ic_launcher.png       launcher icons
├── res/drawable-xhdpi/banner.png      Android TV home-screen banner (320×180)
├── keystore/relay-release.keystore    signing key — keep this
└── tools/
    ├── fetch-toolchain.sh             JDK + apktool + aapt2 + apksigner
    ├── make_icons.py                  icons + banner
    ├── test-ui.js                     headless UI suite (42 assertions)
    └── test-tv-host.sh                simulated-Termux suite (23 assertions)
```

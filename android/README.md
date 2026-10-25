# Relay Dashboard — Android APK

A **~40 KB native Android app** that wraps your relay dashboard in a real
installable APK, built for **Android 7.0 (API 24) and newer**.

The download is at the **repository root**: [`RelayDashboard.apk`](../RelayDashboard.apk).

```
com.nishgamerrz.relaydash · v1.0 (versionCode 1) · minSdk 24 · targetSdk 28
signed (APK Signature Scheme v2 + v3) · 40 KB · no ads, no trackers, no analytics
```

---

## Install on the phone

1. Copy `RelayDashboard.apk` to the phone (USB, Google Drive, or open the
   GitHub link in the phone's browser).
2. Tap the file. Android will say *"For your security, your phone is not
   allowed to install unknown apps from this source"* — tap **Settings**,
   enable **Allow from this source**, then tap **Install**.
   (Android 7 calls this *Settings → Security → Unknown sources*.)
3. Open **Relay Dashboard**, paste your dashboard URL, tap **Save**, then
   **Open dashboard**.

Nothing else is needed: no Play Store, no Play Services, no account.

## What it does

| Screen | What happens |
|---|---|
| URL box | Accepts `https://`, `http://` or a bare host (`example.com` → `https://`). Anything else (`javascript:`, `file:`, …) is refused. |
| Open dashboard | Loads your page **inside** the app, full screen. |
| Save / auto-open | Remembers the URL on the phone; optional auto-open on launch. |
| Check connection | Native HTTP ping (background thread, 8 s timeout) → shows *online · HTTP 200 · 143 ms* or a plain-English failure. |
| Open in browser | Sends the URL to Chrome / whatever browser is installed. |
| Copy URL / Clear data / About | Self-explanatory; Clear asks for a second tap. |

Practical details:

- **Back button** → home screen, then exits. Long-press-free, one-handed.
- **Only `http`/`https` stay in the app.** `mailto:`, `tel:`, `tg:`, Play
  Store links etc. are handed to the matching app instead of dead-ending in
  the WebView.
- **Cleartext HTTP is allowed** (`usesCleartextTraffic`), so a relay on
  `http://192.168.1.5:3000` or a free host without TLS still works.
- **Permissions: exactly two** — `INTERNET` and `ACCESS_NETWORK_STATE`.
- The home screen is bundled (`assets/index.html`) and works offline; the
  dashboard itself needs whatever connection it normally needs.

## Why it is built this way

There is no Android SDK, Gradle, or Java compiler involved — which is also why
the APK is 40 KB instead of 5 MB:

| Piece | How |
|---|---|
| UI | `assets/index.html` — plain HTML/CSS/JS, no CDN, no framework |
| Native shell | `smali/com/nishgamerrz/relaydash/*.smali` — hand-written Dalvik assembly (WebView + JS bridge + Back handling) |
| Packaging | `apktool` (resources + `aapt2`) → `apksigner` |
| Toolchain | fetched into `.tools/` by `tools/fetch-toolchain.sh` (JDK via pip, apktool/apksigner/aapt2 via npm registry tarballs) |

`.dex` is emitted as **format 035** on purpose: it is the format every
Android 7 build accepts, and nothing here needs the newer 037+ encodings.

## Build it yourself

```bash
cd android
./tools/fetch-toolchain.sh    # once, ~60 MB into .tools/ (git-ignored)
./tools/make_icons.py         # optional: regenerate launcher icons (needs Pillow)
node tools/test-ui.js         # optional: 27 headless UI checks
./build.sh                    # -> dist/RelayDashboard.apk + ../RelayDashboard.apk
```

`build.sh` refuses to finish unless the dex format, `minSdkVersion`, launcher
activity, icon, bundled UI and signature all check out.

Point it at a real SDK instead if you prefer:

```bash
JAVA_BIN=java AAPT2_BIN=aapt2 APKTOOL_JAR=apktool.jar \
APKSIGNER_JAR=apksigner.jar KEYTOOL_BIN=keytool ./build.sh
```

## Making an update (v1.1, v1.2, …)

1. Edit `AndroidManifest.xml` → raise `android:versionCode` (2, 3, …) and
   `android:versionName` (`1.1`, …). Android only installs an update when
   `versionCode` goes **up**.
2. Also update `versionInfo:` in `apktool.yml` to match.
3. Edit smali/assets as needed, run `./build.sh`.
4. Install over the old copy — **do not uninstall first**, that wipes the
   saved URL.

The signing key lives in `keystore/relay-release.keystore` and is committed on
purpose: every build must be signed with the *same* key or Android refuses the
update. It is a personal, self-signed key with a published password
(`relaydash`) — fine for sideloading, **not** suitable for a Play Store
release (there the key must stay private).

## Limits worth knowing

- **Not from the Play Store.** Sideloading means the "unknown sources"
  prompt; some phones re-ask after every reboot.
- **The dashboard is a web page, so it is only as mobile-friendly as your
  page.** Wide tables and hover-only menus will be cramped on a 5" screen.
- **Session cookies persist** in the WebView, so logging into the dashboard
  once keeps you logged in until you use *Clear saved data*.
- **No push notifications, no background sync** — the app does nothing at all
  while closed.
- If your relay host sleeps (free tiers do), the first load after a nap can
  take ~30 s; *Check connection* tells you the host is awake before you tap
  through.
- Tested by inspection rather than on a device: this sandbox has no emulator,
  so the APK was verified structurally (dex round-trip, manifest, signatures,
  package badging) and the UI logic by the headless suite in
  `tools/test-ui.js`. If a phone throws *"Parse error"* or *"App not
  installed"* on install, say so and it will be rebuilt.

## Files

```
android/
├── AndroidManifest.xml            package, permissions, launcher activity
├── apktool.yml                    apktool metadata (+ dex format note)
├── build.sh                       build · check · sign · verify
├── assets/index.html              the app's home screen (offline)
├── smali/com/nishgamerrz/relaydash/
│   ├── MainActivity.smali         WebView host, Back button, status bar
│   ├── MainActivity$1.smali       WebViewClient: keep http(s) inside, else hand off
│   ├── JsBridge.smali             load / open / copy / toast / ping
│   └── JsBridge$1..$6.smali       the UI-thread + background-thread bodies
├── res/mipmap-*/ic_launcher.png   launcher icons (generated)
├── keystore/relay-release.keystore  signing key — keep this
└── tools/
    ├── fetch-toolchain.sh         downloads JDK + apktool + aapt2 + apksigner
    ├── make_icons.py              regenerates the icons
    ├── test-ui.js                 headless UI tests (27 assertions)
    └── icon-512.png               512 px icon for listings
```

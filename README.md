# Nish12345678910111.github.io

Relay/dashboard backend plus a self-contained Android app that hosts a console
dashboard from a phone or an Android TV.

| Path | What it is |
|---|---|
| [`RelayDashboard.apk`](RelayDashboard.apk) | **One APK, no Termux** — an Android 7+ / Android TV app that serves its own password-protected web dashboard in the background and starts on boot. ~56 KB. |
| [`android/`](android/) | Full source of that APK (plain Java + a reproducible build). See [`android/README.md`](android/README.md). |
| `dashboard.js` | The relay "brain" behind the static dashboard — claim/dispatch Discord actions from database rows. |
| `schema-dashboard-logs.sql` | One-time Supabase schema for the `dashboard_logs` table (`dashboard.js` v5). |
| `FIXES.md` | Notes for the `relay-leader-v5-db-logs-and-claim-fix` build. |
| `console manager.txt` | The original Termux installer (kept for people who want the full Termux version; the APK no longer needs it). |

## Install on the TV (Android 8)

Download on the TV:

```
https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/RelayDashboard.apk
```

or `https://nishgamerrz.github.io/RelayDashboard.apk`, then allow *install
unknown apps* for the browser/Downloader and press Install. Opening the app
starts the host — there is no setup step and no second APK.

## Using it

- **On the TV:** the app shows **Running**, the address (`http://<tv-ip>:8080`),
  the saved password, and every device that has connected.
- **On your phone/laptop (same Wi-Fi):** open that address, type the password,
  and you get the dashboard: status, a console that runs `/system/bin/sh` on the
  TV, uptime, and the connected-device list.

Details, security notes and limits: [`android/README.md`](android/README.md).

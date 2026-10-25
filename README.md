# Nish12345678910111.github.io

Relay/dashboard backend, its Android app, and the TV hosting setup.

| Path | What it is |
|---|---|
| [`RelayDashboard.apk`](RelayDashboard.apk) | **Installable app for Android 7+ phones and Android TV** — opens the dashboard, starts on boot, can host and drive Termux. ~70 KB. |
| [`tv-autostart.txt`](tv-autostart.txt) | One-command Termux setup that makes an Android TV host Console Manager by itself on power-on. |
| [`android/`](android/) | Source of the APK — smali shell + bundled HTML UI + reproducible build. See [`android/README.md`](android/README.md). |
| `dashboard.js` | The relay "brain" behind the static dashboard — claim/dispatch Discord actions from database rows. |
| `schema-dashboard-logs.sql` | One-time Supabase schema for the `dashboard_logs` table (`dashboard.js` v5). |
| `FIXES.md` | Notes for the `relay-leader-v5-db-logs-and-claim-fix` build. |
| `console manager.txt` | Termux installer script (`bash "console manager.txt"`). |

## Turn an Android TV into a host (2 commands)

On the TV: install **Termux**, **Termux:Boot** and **RelayDashboard.apk**, then
paste this into Termux once:

```bash
python3 -c 'import urllib.request as u,subprocess as s;u.urlretrieve("https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/tv-autostart.txt","tv");s.run(["bash","tv"],check=True)'
```

It installs Console Manager, starts the web host on `0.0.0.0:5000`, allows the
dashboard app to control Termux, and hooks the host to power-on. Then any phone
or laptop on the same Wi-Fi opens `http://<tv-ip>:5000`. Details, including the
boot-hook caveats and Termux:Boot requirement: [`android/README.md`](android/README.md).

Manage it later with `bash ~/tv-autostart.txt status | start | stop | url`.

## Install the app

- Phone: open `https://nishgamerrz.github.io/RelayDashboard.apk`
- TV: open that link in a “Downloader” app, then allow install from that source.

The app asks only for internet, boot-start and (optionally) the Termux command
permission. It does nothing in the background on its own.

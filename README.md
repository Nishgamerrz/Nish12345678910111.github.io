# Nish12345678910111.github.io

Relay/dashboard backend and its Android app.

| Path | What it is |
|---|---|
| [`RelayDashboard.apk`](RelayDashboard.apk) | **Installable Android app** (Android 7.0+) that wraps the dashboard in a native shell. 40 KB. |
| [`android/`](android/) | Source of that APK — smali + bundled HTML UI + reproducible build script. See [`android/README.md`](android/README.md). |
| `dashboard.js` | The relay "brain" behind the static dashboard — claim/dispatch Discord actions from database rows. |
| `schema-dashboard-logs.sql` | One-time Supabase schema for the `dashboard_logs` table (`dashboard.js` v5). |
| `FIXES.md` | Notes for the `relay-leader-v5-db-logs-and-claim-fix` build. |
| `console manager.txt` | Termux installer script (`bash "console manager.txt"`). |

## Install the app on the phone

Download `RelayDashboard.apk`, tap it, allow *install unknown apps* for the
browser you downloaded it with, then Install. Full steps, screenshots-free
walkthrough and rebuild instructions: [`android/README.md`](android/README.md).

Direct link once this branch is merged into `main`:

- GitHub Pages: `https://nishgamerrz.github.io/RelayDashboard.apk`
- GitHub raw: `https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/RelayDashboard.apk`

The app only needs the `INTERNET` permission and does nothing while closed.

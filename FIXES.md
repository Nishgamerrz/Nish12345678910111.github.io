# Relay fixes — build `relay-leader-v5-db-logs-and-claim-fix-2026-10-19`

Two changes to `dashboard.js`, plus one new SQL file you must run once in Supabase.

## Deploy

1. Run **`schema-dashboard-logs.sql`** once in the Supabase SQL editor
   (it creates the new `dashboard_logs` table and fixes the `dashboard_requests`
   status constraint — safe to re-run).
2. Replace the running `dashboard.js` with the one in this repo and restart it
   (throttle.js / your hosting panel).
3. Confirm the terminal prints:
   `[Relay] dashboard.js build: relay-leader-v5-db-logs-and-claim-fix-2026-10-19`

## What was fixed

### 1. Automatic error/log saving to a new `dashboard_logs` table

Every meaningful relay event is now saved **automatically** to a new
`dashboard_logs` table (columns: `level`, `scope`, `message`, `request_id`,
`action`, `status`, `duration_ms`, `meta`, `created_at`):

| Event | Level |
|---|---|
| Request claimed → `processing` | info |
| Request completed (with duration) | info |
| Request failed (with reason + duration) | error |
| Claim-support/constraint problem | warn |
| Leader election ACTIVE / stepped down | info / warn |
| Sweep failures, stuck-row reaper actions | error / warn |
| Startup, shutdown, unhandled rejections, **uncaught crashes** | info / error |

- Rows older than 7 days are pruned automatically every 10 minutes.
- New **`get_logs`** dashboard action reads them back, newest first —
  send `{ action: 'get_logs', query: { limit: 100 } }` (optional
  `query.level = 'error'`) from dashboard.html to show a Logs view.
- Logging is fire-and-forget: a failed log insert can never break the
  actual request handling.

### 2. Requests no longer get stuck at "processing"

Three layers now guarantee every request ends at `completed` or `error`:

1. **15 s timeout on every Discord API call** — before, one silently hung
   HTTPS call kept `dispatch()` awaiting forever, which is exactly how rows
   ended up stamped `processing` for eternity. Now the call rejects and the
   request is marked `error` with the reason.
2. **60 s hard cap on the whole dispatch** (`withTimeout`) — even if
   something below the HTTP layer wedges, the request is finalized as
   `error` ("dispatch timed out … please retry") instead of spinning.
3. **Stuck-row reaper** — every ~10 s the leader scans for `processing`
   rows older than 2.5 min (i.e. the claiming relay died mid-request:
   panel restart, crash, lost connection) and finalizes them as `error`
   with a clear "please retry" message. Nothing is ever left in limbo,
   even if a relay dies overnight.

Also: the `completed`/`error` status writes are now **error-checked** (a
silent failure there was previously invisible), and every claim, completion
and failure is written to `dashboard_logs` so you can see exactly what the
relay did and why — straight from the database.

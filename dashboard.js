require('dotenv').config();
const https = require('https');
const crypto = require('crypto');
const supabase = require('./db'); // Postgres-backed, same .from(...) API as before

// Unique build marker — bump this string every time this file changes.
// Check EVERY platform's terminal for this exact line on startup. If any
// platform does NOT print this, that platform is running a different,
// older copy of this file, full stop — no other explanation fits.
const DASHBOARD_BUILD_TAG = 'relay-leader-v5-db-logs-and-claim-fix-2026-10-19';
console.log('[Relay] dashboard.js build: ' + DASHBOARD_BUILD_TAG);

// ─── Relay leader election ──────────────────────────────────────────────
// With this bot running on more than one host, every host's dashboard.js
// was reacting to the same inserted row -- most lost the atomic claim and
// correctly, silently backed off (by design), but that still means every
// host's relay runs its own LISTEN connection and 2s sweep for nothing,
// and makes it unpredictable which single terminal ever shows real
// activity. This mirrors discord.js's own proven leader-lock pattern (see
// LEADER_LOCK_KEY there) with its own separate key, so exactly one
// dashboard.js claims requests at a time, with fast automatic failover if
// that one goes down.
const RELAY_LOCK_KEY = '999999999999999998'; // distinct from discord.js's gateway lock (...999)
const RELAY_PROCESS_ID = crypto.randomUUID();
const RELAY_LEASE_MS = 8000;     // a dead leader is noticed (and replaced) within this long
const RELAY_HEARTBEAT_MS = 3000; // well under the lease, so a live leader never loses it by accident
let relayIsLeader = false;
let relayTickCount = 0;

// ─── Persistent relay logging (new `dashboard_logs` table) ─────────────
// Every important relay event -- a request being claimed (processing),
// completed, failed, plus any internal error (sweep failures, lock
// failures, crashes) -- is now ALSO saved to a dedicated `dashboard_logs`
// table in the same database. That gives a permanent, queryable record of
// what the relay did and when, which survives restarts and can be read
// straight from the dashboard (the `get_logs` action below) instead of
// having to dig through a terminal you may not even have open.
//
// Needs its one-time schema, in the Supabase SQL editor (also in
// schema-dashboard-logs.sql in this repo):
//   CREATE TABLE IF NOT EXISTS dashboard_logs (
//       id bigint generated always as identity primary key,
//       created_at timestamptz not null default now(),
//       level text not null default 'info',   -- 'info' | 'warn' | 'error'
//       scope text not null default 'relay',  -- 'request' | 'leader' | 'sweep' | 'claim' | 'reaper' | 'process' | 'relay'
//       message text not null,
//       request_id uuid,                      -- links to dashboard_requests.id when relevant
//       action text,
//       status text,                          -- 'processing' | 'completed' | 'error' at the time of the log
//       duration_ms integer,
//       meta jsonb
//   );
const LOG_TABLE = 'dashboard_logs';
const LOG_RETENTION_MS = 7 * 24 * 60 * 60 * 1000; // prune rows older than 7 days

// Best-effort, fire-and-forget: logging must NEVER block, delay, or take
// down the actual work. A failed log insert only complains on the console.
function persistLog(level, scope, message, extra) {
    try {
        const e = extra || {};
        const row = {
            level: level,
            scope: scope,
            message: String(message).slice(0, 4000),
            request_id: e.request_id || null,
            action: e.action || null,
            status: e.status || null,
            duration_ms: typeof e.duration_ms === 'number' ? Math.round(e.duration_ms) : null,
            meta: e.meta || null,
        };
        // Mirror to the terminal too, so the console stays the live view.
        console[level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'log']('[Relay-Log:' + scope + '] ' + row.message);
        supabase.from(LOG_TABLE).insert(row).then(() => {}, (err) => {
            console.error('[Relay-Log] Could not save log row to ' + LOG_TABLE + ' (has schema-dashboard-logs.sql been run?): ' + ((err && err.message) || err));
        });
    } catch (e) {
        console.error('[Relay-Log] persistLog crashed:', e.message);
    }
}

async function tickRelayLeadership() {
    relayTickCount++;
    // Re-announce status every ~30s even with no change -- a process that
    // is simply never the leader used to stay completely silent forever,
    // which looks identical to "this code isn't running at all". Always
    // saying something, periodically, closes that gap.
    const periodicAnnounce = relayTickCount % Math.max(1, Math.round(30000 / RELAY_HEARTBEAT_MS)) === 0;
    try {
        const now = Date.now();
        const { data: row, error } = await supabase.from('data').select('data').eq('username', RELAY_LOCK_KEY).maybeSingle();
        if (error) throw error;
        const lock = row?.data ? (typeof row.data === 'string' ? JSON.parse(row.data) : row.data) : null;
        const leaseIsDead = !lock || !lock.holder || !lock.heartbeatAt || (now - lock.heartbeatAt) > RELAY_LEASE_MS;

        if (lock && lock.holder === RELAY_PROCESS_ID) {
            // Already the leader -- just renew.
            await supabase.from('data').upsert({ username: RELAY_LOCK_KEY, data: { holder: RELAY_PROCESS_ID, heartbeatAt: now } }, { onConflict: ['username'] });
            if (!relayIsLeader) { console.log('[Relay-Leader] This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') is now ACTIVE — it will claim dashboard_requests.'); persistLog('info', 'leader', 'This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') is now ACTIVE — it will claim dashboard_requests.'); }
            else if (periodicAnnounce) console.log('[Relay-Leader] Still ACTIVE (' + RELAY_PROCESS_ID.slice(0, 8) + ').');
            relayIsLeader = true;
        } else if (leaseIsDead) {
            // Nobody holds a live lease -- try to take it. upsert() isn't a
            // compare-and-swap, so re-read afterward to confirm this
            // process actually won (another host racing for the same dead
            // lease at the same instant is the only way this check matters).
            await supabase.from('data').upsert({ username: RELAY_LOCK_KEY, data: { holder: RELAY_PROCESS_ID, heartbeatAt: now } }, { onConflict: ['username'] });
            const { data: check } = await supabase.from('data').select('data').eq('username', RELAY_LOCK_KEY).maybeSingle();
            const checkLock = check?.data ? (typeof check.data === 'string' ? JSON.parse(check.data) : check.data) : null;
            const wonIt = checkLock?.holder === RELAY_PROCESS_ID;
            if (wonIt && !relayIsLeader) { console.log('[Relay-Leader] This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') is now ACTIVE — it will claim dashboard_requests.'); persistLog('info', 'leader', 'This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') is now ACTIVE — it will claim dashboard_requests.'); }
            relayIsLeader = wonIt;
        } else {
            if (relayIsLeader) { console.log('[Relay-Leader] This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') stepped down — another process holds the lease.'); persistLog('warn', 'leader', 'This process (' + RELAY_PROCESS_ID.slice(0, 8) + ') stepped down — another process holds the lease.'); }
            else if (periodicAnnounce) console.log('[Relay-Leader] STANDBY (' + RELAY_PROCESS_ID.slice(0, 8) + ') — leader is ' + (lock?.holder ? lock.holder.slice(0, 8) : 'unknown') + ', last heartbeat ' + Math.round((now - (lock?.heartbeatAt || 0)) / 1000) + 's ago.');
            relayIsLeader = false;
        }
    } catch (e) {
        // Fail open on a transient read/write error -- keep whatever
        // leadership state this process already had rather than flapping.
        console.error('[Relay-Leader] Lock check failed (keeping current state):', e.message);
    }
}

// ═══════════════════════════════════════════════════════════════════════
// DASHBOARD RELAY
// This is the "brain" behind the static dashboard.html on byet.host. It
// has NO Discord gateway connection of its own — it doesn't need one.
// Every action the dashboard needs (member lists, kick/ban, channel/role
// info, config get/set, etc.) is a plain Discord REST call, exactly like
// the old dashboard.js made, just using the bot TOKEN directly instead of
// a live gateway client. That's also why this can't live in throttle.js:
// throttle.js is a process supervisor and never had the bot token at all.
//
// Flow: dashboard.html inserts a row into `dashboard_requests` (via the
// public anon key) -> this process picks it up over Supabase Realtime
// -> does the actual Discord/Supabase work with the SERVICE ROLE key and
// the bot TOKEN (neither of which ever reaches a browser) -> writes
// `response` + `status` back onto that same row.
// ═══════════════════════════════════════════════════════════════════════

const TOKEN = process.env.DISCORD_TOKEN;
const OWNER_DISCORD_ID = process.env.OWNER_DISCORD_ID || '';
const TRUSTED_ENV = (process.env.TRUSTED || '').split(',').map(s => s.trim()).filter(Boolean);
const BOT_INVITE = process.env.BOT_INVITE || '';

if (!TOKEN) { console.error('[Relay] FATAL: DISCORD_TOKEN missing from .env'); process.exit(1); }
if (!process.env.DATABASE_URL) { console.error('[Relay] FATAL: DATABASE_URL missing from .env'); process.exit(1); }

// ─── Discord REST helper (same shape as the original dashboard.js's da/uA) ───
// Every call now has a hard 15s timeout. Without this, a single Discord
// call that never comes back (dropped connection, silent hang) would keep
// dispatch() awaiting forever -- which is exactly how a request could end
// up stamped 'processing' and then sit there for eternity. Now the call
// rejects, the request is marked 'error', and the dashboard shows why.
const DISCORD_HTTP_TIMEOUT_MS = 15000;
function hR(method, path, headers, body) {
    return new Promise((resolve, reject) => {
        const req = https.request('https://discord.com/api/v10' + path, { method, headers }, res => {
            let d = ''; res.on('data', c => d += c); res.on('end', () => { try { resolve({ s: res.statusCode, d: JSON.parse(d) }); } catch { resolve({ s: res.statusCode, d }); } });
        });
        req.setTimeout(DISCORD_HTTP_TIMEOUT_MS, () => req.destroy(new Error('Discord API call timed out after ' + (DISCORD_HTTP_TIMEOUT_MS / 1000) + 's: ' + method + ' ' + path)));
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}
function da(m, p, b) { return hR(m, p, { 'Authorization': 'Bot ' + TOKEN, 'Content-Type': 'application/json' }, b ? JSON.stringify(b) : null); }
// Posts a line to the guild's configured log channel (Logging tab / real
// notificationChannel field). Used for actions run from the dashboard --
// note the equivalent real Discord slash commands (/ban, /kick, /mute,
// /warn, /purge etc.) do NOT currently log to this channel either, only
// AutoMod violations and /clearchannel do. Fire-and-forget: never blocks
// or fails the actual moderation action if logging itself fails.
async function logModAction(gid, text) {
    try {
        const cfg = await gGD(gid);
        if (!cfg.notificationChannel) return;
        await da('POST', '/channels/' + cfg.notificationChannel + '/messages', { content: text });
    } catch (e) {}
}

let BOT_APP_ID = null;
async function resolveBotAppId() {
    try { const r = await da('GET', '/users/@me'); if (r.s === 200 && r.d?.id) BOT_APP_ID = r.d.id; } catch (e) { console.error('[Relay] Could not resolve bot app id:', e.message); }
}

// ─── Per-guild config in the shared `data` table (same shape as the rest of the bot) ───
async function gGD(gid) {
    try {
        const { data: r, error } = await supabase.from('data').select('data').eq('username', gid).maybeSingle();
        if (error) throw error;
        if (!r?.data) return {};
        return typeof r.data === 'string' ? JSON.parse(r.data) : r.data;
    } catch { return {}; }
}
function deepMerge(target, source) {
    for (const k in source) {
        if (source[k] && typeof source[k] === 'object' && !Array.isArray(source[k])) {
            if (!target[k]) target[k] = {};
            deepMerge(target[k], source[k]);
        } else target[k] = source[k];
    }
    return target;
}
async function sGD(gid, patch) {
    const existing = await gGD(gid);
    const merged = deepMerge({ ...existing }, patch);
    await supabase.from('data').upsert({ username: gid, data: merged }, { onConflict: ['username'] });
    return merged;
}
async function gTr() {
    try {
        const { data: r } = await supabase.from('data').select('data').eq('username', '__trusted_guilds__').maybeSingle();
        if (r?.data?.trusted) return [...new Set([...TRUSTED_ENV, ...r.data.trusted])];
    } catch { }
    return TRUSTED_ENV;
}

// ─── Bot's own guild cache (REST, refreshed every few seconds — mirrors fG() from the original) ───
let guildCache = [], guildCacheAt = 0;
const GUILD_CACHE_TTL = 10000;
const GUILD_CACHE_KEY = '__guild_cache__';
async function fetchBotGuilds() {
    const now = Date.now();
    if (guildCache.length && now - guildCacheAt < GUILD_CACHE_TTL) return guildCache; // this process's own copy is still fresh

    // Before ever calling Discord, check the copy shared in Supabase across
    // every hosted relay process. Discord's rate limit on this endpoint is
    // per BOT TOKEN globally, not per-process -- with several hosts running
    // (especially if the atomic-claim migration from earlier hasn't been
    // run yet, so more than one host can end up handling the same request),
    // each host had its own separate in-memory cache and could all call
    // Discord for the same single user action at once. That's what "Error:
    // Discord 429" was. A shared cache means N hosts collapse into at most
    // 1 real Discord call per TTL window, no matter how many are running.
    try {
        const { data: row } = await supabase.from('data').select('data').eq('username', GUILD_CACHE_KEY).maybeSingle();
        const shared = row?.data ? (typeof row.data === 'string' ? JSON.parse(row.data) : row.data) : null;
        if (shared && Array.isArray(shared.guilds) && now - (shared.at || 0) < GUILD_CACHE_TTL) {
            guildCache = shared.guilds;
            guildCacheAt = shared.at;
            return guildCache;
        }
    } catch (e) { /* Supabase hiccup -- fall through and just ask Discord directly */ }

    const r = await da('GET', '/users/@me/guilds?with_counts=true');
    if (r.s === 429) {
        // Discord is explicitly telling us to back off. Serving a stale
        // cached copy (ours or the shared one, however old) beats a hard
        // failure -- the guild list rarely changes minute to minute.
        if (guildCache.length) return guildCache;
        throw new Error('Discord is rate-limiting this bot right now (429) — try again in a few seconds.');
    }
    if (r.s !== 200) throw new Error('Discord ' + r.s);
    guildCache = r.d.sort((a, b) => (b.approximate_member_count || 0) - (a.approximate_member_count || 0));
    guildCacheAt = now;
    // Best-effort, fire-and-forget -- never let a slow/failed write to the
    // shared cache delay the response the user is actually waiting on.
    supabase.from('data').upsert({ username: GUILD_CACHE_KEY, data: { guilds: guildCache, at: guildCacheAt } }, { onConflict: ['username'] }).then(() => {}, () => {});
    return guildCache;
}

function fullPerms() {
    return { can_ban: 1, can_kick: 1, can_mute: 1, can_warn: 1, can_purge: 1, can_lock: 1, can_slowmode: 1, can_view_members: 1, can_view_bans: 1, can_view_economy: 1, can_use_commands: 1, can_view_config: 1, can_manage_automod: 1, can_manage_welcome: 1, can_manage_logging: 1, can_manage_roles: 1, can_send_announce: 1 };
}

// ─── Session lookup (dashboard_sessions — written by auth.php, read only here) ───
async function getSession(token) {
    if (!token) return null;
    const { data } = await supabase.from('dashboard_sessions').select('*').eq('token', token).maybeSingle();
    return data || null;
}

// ─── Per-guild permission check, mirroring the original gAu() + nP() middleware ───
async function getGuildPerms(session, guildId) {
    if (session.is_owner) return fullPerms();
    const { data: au } = await supabase.from('allowed_users').select('permissions').eq('guild_id', guildId).eq('user_id', session.user_id).maybeSingle();
    if (!au) return null;
    return { ...fullPerms(), ...(au.permissions || {}) };
}

// ─── Dashboard commands list (ported verbatim from dashboard.js) ───
const DC = [
    // ── Moderation ──
    { name: 'ban', desc: 'Ban a user', cat: 'Moderation', perm: 'can_ban', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'reason', l: 'Reason', t: 'string', r: 0 }] },
    { name: 'unban', desc: 'Remove ban', cat: 'Moderation', perm: 'can_ban', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'tempban', desc: 'Ban a user temporarily', cat: 'Moderation', perm: 'can_ban', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'hours', l: 'Hours', t: 'number', r: 0, d: '24' }, { n: 'reason', l: 'Reason', t: 'string', r: 0 }] },
    { name: 'softban', desc: 'Ban + unban to clear messages', cat: 'Moderation', perm: 'can_ban', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'reason', l: 'Reason', t: 'string', r: 0 }] },
    { name: 'kick', desc: 'Kick a user', cat: 'Moderation', perm: 'can_kick', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'reason', l: 'Reason', t: 'string', r: 0 }] },
    { name: 'mute', desc: 'Timeout user', cat: 'Moderation', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'minutes', l: 'Minutes', t: 'number', r: 0, d: '10' }, { n: 'reason', l: 'Reason', t: 'string', r: 0 }] },
    { name: 'unmute', desc: 'Remove timeout', cat: 'Moderation', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'warn', desc: 'Issue warning', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'reason', l: 'Reason', t: 'string', r: 1 }] },
    { name: 'warnings', desc: 'View warnings for a user', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'clearwarnings', desc: 'Clear all warnings for a user', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'removewarning', desc: 'Remove a single warning by number', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'index', l: 'Warning #', t: 'number', r: 1 }] },
    { name: 'modnotes', desc: 'Add a private mod note on a user', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'note', l: 'Note', t: 'string', r: 1 }] },
    { name: 'viewnotes', desc: 'View mod notes for a user', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'modstats', desc: 'Server moderation stats summary', cat: 'Moderation', perm: null, params: [] },
    { name: 'banlist', desc: 'List all banned users', cat: 'Moderation', perm: 'can_ban', params: [] },
    { name: 'dmuser', desc: 'Send a direct message to a user', cat: 'Moderation', perm: 'can_warn', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'message', l: 'Message', t: 'string', r: 1 }] },
    { name: 'nickname', desc: "Set a user's nickname", cat: 'Moderation', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'nickname', l: 'Nickname', t: 'string', r: 1 }] },
    { name: 'resetnickname', desc: "Reset a user's nickname", cat: 'Moderation', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'setmod', desc: 'Add a bot moderator', cat: 'Moderation', perm: null, params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'removemod', desc: 'Remove a bot moderator', cat: 'Moderation', perm: null, params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'setmuterole', desc: 'Set the role used for /mute', cat: 'Moderation', perm: null, params: [{ n: 'role_id', l: 'Role ID', t: 'role', r: 1 }] },
    { name: 'setnotificationchannel', desc: 'Set the mod log channel', cat: 'Moderation', perm: null, params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
    { name: 'set-prefix-channel', desc: 'Restrict ":" prefix commands to one channel', cat: 'Moderation', perm: null, params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
    { name: 'joinroleset', desc: 'Add a role auto-given on join', cat: 'Roles', perm: 'can_manage_roles', params: [{ n: 'role_id', l: 'Role ID', t: 'role', r: 1 }] },
    { name: 'joinroleunset', desc: 'Remove a join role (blank = clear all)', cat: 'Roles', perm: 'can_manage_roles', params: [{ n: 'role_id', l: 'Role ID (blank=all)', t: 'role', r: 0 }] },
    // ── Channel ──
    { name: 'purge', desc: 'Bulk delete msgs', cat: 'Channel', perm: 'can_purge', params: [{ n: 'channel_id', l: 'Channel ID', t: 'channel', r: 1 }, { n: 'amount', l: 'Amount (1-100)', t: 'number', r: 1 }] },
    { name: 'purgeuser', desc: "Bulk delete one user's recent messages", cat: 'Channel', perm: 'can_purge', params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }, { n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'limit', l: 'Scan limit', t: 'number', r: 0, d: '100' }] },
    { name: 'clearchannel', desc: 'Delete ALL messages in a channel', cat: 'Channel', perm: 'can_purge', params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
    { name: 'slowmode', desc: 'Set slowmode', cat: 'Channel', perm: 'can_slowmode', params: [{ n: 'channel_id', l: 'Channel ID', t: 'channel', r: 1 }, { n: 'seconds', l: 'Seconds', t: 'number', r: 1 }] },
    { name: 'lock', desc: 'Lock channel', cat: 'Channel', perm: 'can_lock', params: [{ n: 'channel_id', l: 'Channel ID', t: 'channel', r: 1 }] },
    { name: 'unlock', desc: 'Unlock channel', cat: 'Channel', perm: 'can_lock', params: [{ n: 'channel_id', l: 'Channel ID', t: 'channel', r: 1 }] },
    { name: 'lockdown', desc: 'Lock every text channel at once', cat: 'Channel', perm: 'can_lock', params: [] },
    { name: 'unlockdown', desc: 'Unlock channels locked by /lockdown', cat: 'Channel', perm: 'can_lock', params: [] },
    { name: 'announce', desc: 'Send announcement to channel', cat: 'Channel', perm: 'can_send_announce', params: [{ n: 'channel_id', l: 'Channel ID', t: 'channel', r: 1 }, { n: 'message', l: 'Message', t: 'string', r: 1 }] },
    { name: 'poll', desc: 'Post a reaction poll', cat: 'Channel', perm: 'can_send_announce', params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }, { n: 'question', l: 'Question', t: 'string', r: 1 }] },
    // ── Roles ──
    { name: 'addrole', desc: 'Add role to user', cat: 'Roles', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'role_id', l: 'Role ID', t: 'role', r: 1 }] },
    { name: 'removerole', desc: 'Remove role from user', cat: 'Roles', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'role_id', l: 'Role ID', t: 'role', r: 1 }] },
    { name: 'role', desc: 'Add or remove a role from a user', cat: 'Roles', perm: 'can_manage_roles', params: [{ n: 'action', l: "Action ('add' or 'remove')", t: 'string', r: 1, d: 'add' }, { n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'role_id', l: 'Role ID', t: 'role', r: 1 }] },
    // ── Voice ──
    { name: 'voicekick', desc: 'Disconnect a user from voice', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'voicemute', desc: 'Server-mute a user in voice', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'voiceunmute', desc: 'Remove voice mute', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'voicedeafen', desc: 'Server-deafen a user in voice', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'voiceundeafen', desc: 'Remove voice deafen', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }] },
    { name: 'movevoice', desc: 'Move a user to another voice channel', cat: 'Voice', perm: 'can_mute', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'channel_id', l: 'Voice Channel', t: 'channel', r: 1 }] },
    // ── Info / utility (no permission required beyond guild access) ──
    { name: 'ping', desc: 'Check the bot is responsive', cat: 'Utility', perm: null, params: [] },
    { name: 'help', desc: 'List available commands', cat: 'Utility', perm: null, params: [] },
    { name: 'avatar', desc: "Get a user's avatar", cat: 'Utility', perm: null, params: [{ n: 'user_id', l: 'User ID (blank=you)', t: 'string', r: 0 }] },
    { name: 'userinfo', desc: 'Get info about a user', cat: 'Utility', perm: null, params: [{ n: 'user_id', l: 'User ID (blank=you)', t: 'string', r: 0 }] },
    { name: 'serverinfo', desc: 'Get info about this server', cat: 'Utility', perm: null, params: [] },
    // ── Leveling ──
    { name: 'rank', desc: "View a user's level/XP", cat: 'Leveling', perm: null, params: [{ n: 'user_id', l: 'User ID (blank=you)', t: 'string', r: 0 }] },
    { name: 'leaderboard', desc: 'Top 10 by XP', cat: 'Leveling', perm: null, params: [] },
    { name: 'setlevel', desc: "Set a user's level", cat: 'Leveling', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'level', l: 'Level', t: 'number', r: 1 }] },
    { name: 'addxp', desc: "Add XP to a user", cat: 'Leveling', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'amount', l: 'Amount', t: 'number', r: 1 }] },
    // ── Economy (coins earned via the real /work command; these let mods adjust balances) ──
    { name: 'addcoins', desc: "Add coins to a user's balance", cat: 'Economy', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'amount', l: 'Amount', t: 'number', r: 1 }] },
    { name: 'removecoins', desc: "Remove coins from a user's balance", cat: 'Economy', perm: 'can_manage_roles', params: [{ n: 'user_id', l: 'User ID', t: 'string', r: 1 }, { n: 'amount', l: 'Amount', t: 'number', r: 1 }] },
    { name: 'balance', desc: "Check a user's coin balance", cat: 'Economy', perm: null, params: [{ n: 'user_id', l: 'User ID (blank=you)', t: 'string', r: 0 }] },
    // ── Welcome (also editable from the Welcome tab -- kept here for parity with the real slash commands) ──
    { name: 'set-welcome-channel', desc: 'Set the welcome message channel', cat: 'Welcome', perm: null, params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
    { name: 'set-welcome-message', desc: 'Set the welcome message text', cat: 'Welcome', perm: null, params: [{ n: 'message', l: 'Message (use {user} {server})', t: 'string', r: 1 }] },
    // ── AutoMod ──
    // There is intentionally no per-rule command here anymore (no
    // automod-antispam, automod-antilink, etc). Those used to exist
    // alongside the AutoMod tab AND the bot's own /automod-panel, so the
    // exact same nested config could be read-modify-written from three
    // independent places within moments of each other — that's what made
    // a toggle intermittently "not stick" (whichever write landed last
    // won, silently discarding the other). Config now has exactly two
    // front doors: this dashboard's AutoMod tab (below) and the bot's
    // /automod-panel — both go through get_automod/set_automod.
    // ── Ping realtime monitoring ──
    // NOTE: this only persists the setting to guild data (same as the real
    // command does). The actual live monitoring loop runs inside the
    // discord.js gateway process, not this relay -- it picks up new/removed
    // entries from this list on its own next restart, same as if the bot
    // process ever restarted after the real Discord command was used. It
    // is not instant the way running the command in Discord is, since that
    // triggers the monitor immediately inside the already-running process.
    { name: 'add-ping-realtime', desc: 'Start real-time ping monitoring in a channel (takes effect on bot restart)', cat: 'Utility', perm: null, params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
    { name: 'stop-ping-realtime', desc: 'Stop real-time ping monitoring in a channel (takes effect on bot restart)', cat: 'Utility', perm: null, params: [{ n: 'channel_id', l: 'Channel', t: 'channel', r: 1 }] },
];
const XP_FOR_NEXT_LEVEL = 50;
function mapOptToParam(o) {
    const typeMap = { 3: 'string', 4: 'number', 5: 'boolean', 6: 'channel', 7: 'channel', 8: 'role', 9: 'mentionable', 10: 'number', 11: 'attachment' };
    return { n: o.name, l: (o.name || '').replace(/_/g, ' '), t: typeMap[o.type] || 'string', r: o.required ? 1 : 0 };
}
async function gDiscordCmds(gid) {
    try {
        if (!BOT_APP_ID) await resolveBotAppId();
        const g = await da('GET', '/applications/' + BOT_APP_ID + '/commands');
        let guildCmds = [];
        if (gid) { const gr = await da('GET', '/applications/' + BOT_APP_ID + '/guilds/' + gid + '/commands'); if (gr.s === 200 && Array.isArray(gr.d)) guildCmds = gr.d; }
        const all = [...(g.s === 200 && Array.isArray(g.d) ? g.d : []), ...guildCmds];
        const seen = new Set(), out = [];
        for (const c of all) { if (seen.has(c.name)) continue; seen.add(c.name); out.push(c); }
        return out;
    } catch (e) { console.error('[Relay] gDiscordCmds:', e.message); return []; }
}
const cdMap = new Map();
function checkCooldown(gid, cmd, ms = 3000) {
    const k = gid + ':' + cmd, last = cdMap.get(k) || 0;
    if (Date.now() - last < ms) return false;
    cdMap.set(k, Date.now()); return true;
}
async function exC(gid, name, args, actorId) {
    const c = DC.find(x => x.name === name);
    if (!c) throw new Error('Unknown command');
    const tag = ' [Dash:' + actorId + ']';
    switch (name) {
        case 'ban': { const r = await da('PUT', '/guilds/' + gid + '/bans/' + args.user_id, { delete_message_days: 0, reason: (args.reason || 'No reason') + tag }); if (r.s !== 204) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\ud83d\udd28 **Ban** \u2014 ' + args.user_id + ' by <@' + actorId + '> (dashboard). Reason: ' + (args.reason || 'No reason')); return 'Banned ' + args.user_id; }
        case 'unban': { const r = await da('DELETE', '/guilds/' + gid + '/bans/' + args.user_id); if (r.s !== 204) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\u2705 **Unban** \u2014 ' + args.user_id + ' by <@' + actorId + '> (dashboard)'); return 'Unbanned ' + args.user_id; }
        case 'kick': { const r = await da('DELETE', '/guilds/' + gid + '/members/' + args.user_id); if (r.s !== 204) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\ud83d\udc62 **Kick** \u2014 ' + args.user_id + ' by <@' + actorId + '> (dashboard). Reason: ' + (args.reason || 'No reason')); return 'Kicked ' + args.user_id; }
        case 'mute': { const m = parseInt(args.minutes) || 10; const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { communication_disabled_until: new Date(Date.now() + m * 60000).toISOString() }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\ud83d\udd07 **Mute** \u2014 ' + args.user_id + ' for ' + m + 'm by <@' + actorId + '> (dashboard). Reason: ' + (args.reason || 'No reason')); return 'Muted ' + args.user_id + ' ' + m + 'm'; }
        case 'unmute': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { communication_disabled_until: null }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\ud83d\udd08 **Unmute** \u2014 ' + args.user_id + ' by <@' + actorId + '> (dashboard)'); return 'Unmuted ' + args.user_id; }
        case 'warn': { const cfg = await gGD(gid); const w = cfg.warnings || {}; if (!w[args.user_id]) w[args.user_id] = []; w[args.user_id].push({ mod: actorId, reason: args.reason, date: new Date().toISOString() }); await sGD(gid, { warnings: w }); logModAction(gid, '\u26a0\ufe0f **Warn** \u2014 ' + args.user_id + ' by <@' + actorId + '> (dashboard). Reason: ' + args.reason); return 'Warned ' + args.user_id; }
        case 'lock': {
            const r = await da('PUT', '/channels/' + args.channel_id + '/permissions/' + gid, { type: 0, allow: '0', deny: '2048' });
            if (r.s !== 204) throw new Error('Fail');
            // Persist this like the real /lock does -- messageCreate's
            // enforcement reads lockedChannels, not just the permission
            // overwrite (which role hierarchy or other roles can bypass).
            const cfg = await gGD(gid); const lockedChannels = cfg.lockedChannels || {};
            lockedChannels[args.channel_id] = false;
            await sGD(gid, { lockedChannels });
            logModAction(gid, '\ud83d\udd12 **Lock** \u2014 <#' + args.channel_id + '> by <@' + actorId + '> (dashboard)');
            return 'Locked';
        }
        case 'unlock': {
            const r = await da('DELETE', '/channels/' + args.channel_id + '/permissions/' + gid);
            if (r.s !== 204) throw new Error('Fail');
            const cfg = await gGD(gid); const lockedChannels = cfg.lockedChannels || {};
            lockedChannels[args.channel_id] = true;
            await sGD(gid, { lockedChannels });
            logModAction(gid, '\ud83d\udd13 **Unlock** \u2014 <#' + args.channel_id + '> by <@' + actorId + '> (dashboard)');
            return 'Unlocked';
        }
        case 'announce': { const r = await da('POST', '/channels/' + args.channel_id + '/messages', { content: args.message + tag }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); return 'Announced in ' + args.channel_id; }
        case 'addrole': { const r = await da('PUT', '/guilds/' + gid + '/members/' + args.user_id + '/roles/' + args.role_id); if (r.s !== 204) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\u2795 **Role Add** \u2014 <@&' + args.role_id + '> to ' + args.user_id + ' by <@' + actorId + '> (dashboard)'); return 'Role added'; }
        case 'removerole': { const r = await da('DELETE', '/guilds/' + gid + '/members/' + args.user_id + '/roles/' + args.role_id); if (r.s !== 204) throw new Error(r.d?.message || 'Fail'); logModAction(gid, '\u2796 **Role Remove** \u2014 <@&' + args.role_id + '> from ' + args.user_id + ' by <@' + actorId + '> (dashboard)'); return 'Role removed'; }
        case 'role': {
            const add = (args.action || 'add').toLowerCase() !== 'remove';
            const r = await da(add ? 'PUT' : 'DELETE', '/guilds/' + gid + '/members/' + args.user_id + '/roles/' + args.role_id);
            if (r.s !== 204) throw new Error(r.d?.message || 'Fail');
            logModAction(gid, (add ? '\u2795' : '\u2796') + ' **Role ' + (add ? 'Add' : 'Remove') + '** \u2014 <@&' + args.role_id + '> ' + (add ? 'to' : 'from') + ' ' + args.user_id + ' by <@' + actorId + '> (dashboard)');
            return (add ? 'Added' : 'Removed') + ' role ' + args.role_id + ' ' + (add ? 'to' : 'from') + ' ' + args.user_id;
        }
        case 'tempban': {
            const hours = parseInt(args.hours) || 24;
            const r = await da('PUT', '/guilds/' + gid + '/bans/' + args.user_id, { delete_message_days: 0, reason: (args.reason || 'Temporary ban') + tag });
            if (r.s !== 204) throw new Error(r.d?.message || 'Fail');
            setTimeout(() => { da('DELETE', '/guilds/' + gid + '/bans/' + args.user_id).catch(() => {}); }, hours * 60 * 60 * 1000);
            return 'Temp-banned ' + args.user_id + ' for ' + hours + 'h';
        }
        case 'softban': {
            const r = await da('PUT', '/guilds/' + gid + '/bans/' + args.user_id, { delete_message_days: 1, reason: (args.reason || 'Softban') + tag });
            if (r.s !== 204) throw new Error(r.d?.message || 'Fail');
            await da('DELETE', '/guilds/' + gid + '/bans/' + args.user_id);
            return 'Softbanned ' + args.user_id + ' (messages cleared)';
        }
        case 'purgeuser': {
            const limit = Math.min(Math.max(parseInt(args.limit) || 100, 1), 500);
            let all = [], before = null;
            while (all.length < limit) {
                let path = '/channels/' + args.channel_id + '/messages?limit=100' + (before ? '&before=' + before : '');
                const r = await da('GET', path);
                if (r.s !== 200 || !r.d.length) break;
                all = all.concat(r.d); before = r.d[r.d.length - 1].id;
                if (r.d.length < 100) break;
            }
            const ids = all.filter(m => m.author?.id === args.user_id).map(m => m.id).slice(0, 100);
            if (!ids.length) return 'No messages from ' + args.user_id + ' in the last ' + all.length + ' scanned';
            const dr = ids.length === 1
                ? await da('DELETE', '/channels/' + args.channel_id + '/messages/' + ids[0])
                : await da('POST', '/channels/' + args.channel_id + '/messages/bulk-delete', { messages: ids });
            if (dr.s !== 204) throw new Error('Delete failed (messages older than 14 days can\'t be bulk-deleted)');
            return 'Deleted ' + ids.length + ' messages from ' + args.user_id;
        }
        case 'clearchannel': {
            let total = 0;
            for (let i = 0; i < 10; i++) {
                const r = await da('GET', '/channels/' + args.channel_id + '/messages?limit=100');
                if (r.s !== 200 || !r.d.length) break;
                const ids = r.d.map(m => m.id);
                const dr = ids.length === 1
                    ? await da('DELETE', '/channels/' + args.channel_id + '/messages/' + ids[0])
                    : await da('POST', '/channels/' + args.channel_id + '/messages/bulk-delete', { messages: ids });
                if (dr.s !== 204) break;
                total += ids.length;
                if (ids.length < 100) break;
            }
            logModAction(gid, '\ud83e\uddf9 **Clear Channel** \u2014 ' + total + ' messages in <#' + args.channel_id + '> by <@' + actorId + '> (dashboard)');
            return 'Deleted ' + total + ' messages (older than 14 days can\'t be bulk-deleted and are left in place)';
        }
        case 'purge': { const a = Math.min(Math.max(parseInt(args.amount) || 10, 1), 100); const mr = await da('GET', '/channels/' + args.channel_id + '/messages?limit=' + a); if (mr.s !== 200 || !mr.d.length) throw new Error('No messages'); const ids = mr.d.map(x => x.id); const dr = await da('POST', '/channels/' + args.channel_id + '/messages/bulk-delete', { messages: ids }); if (dr.s !== 204) throw new Error('Delete failed'); logModAction(gid, '\ud83e\uddf9 **Purge** \u2014 ' + ids.length + ' messages in <#' + args.channel_id + '> by <@' + actorId + '> (dashboard)'); return 'Deleted ' + ids.length + ' msgs'; }
        case 'slowmode': { const s = Math.max(0, parseInt(args.seconds) || 0); const r = await da('PATCH', '/channels/' + args.channel_id, { rate_limit_per_user: s }); if (r.s !== 200) throw new Error('Fail'); return 'Slowmode ' + s + 's'; }
        case 'lockdown': case 'unlockdown': {
            const doLock = name === 'lockdown';
            const chR = await da('GET', '/guilds/' + gid + '/channels');
            if (chR.s !== 200) throw new Error('Could not list channels');
            const cfg = await gGD(gid); const lockedChannels = cfg.lockedChannels || {};
            let count = 0;
            for (const ch of chR.d) {
                if (![0, 5].includes(ch.type)) continue; // text/announcement channels only
                if (!doLock && lockedChannels[ch.id] === undefined) continue; // unlockdown only touches what lockdown locked
                const overwrite = doLock ? { type: 0, allow: '0', deny: '2048' } : null;
                if (doLock) await da('PUT', '/channels/' + ch.id + '/permissions/' + gid, overwrite).catch(() => {});
                else await da('DELETE', '/channels/' + ch.id + '/permissions/' + gid).catch(() => {});
                if (doLock) lockedChannels[ch.id] = false; else delete lockedChannels[ch.id];
                count++;
            }
            await sGD(gid, { lockedChannels });
            return (doLock ? 'Locked ' : 'Unlocked ') + count + ' channels';
        }
        case 'poll': {
            const r = await da('POST', '/channels/' + args.channel_id + '/messages', { content: '**Poll:** ' + args.question + tag });
            if (r.s !== 200) throw new Error(r.d?.message || 'Fail');
            for (const emoji of ['\u{1F44D}', '\u{1F44E}', '\u{1F937}']) {
                await da('PUT', '/channels/' + args.channel_id + '/messages/' + r.d.id + '/reactions/' + encodeURIComponent(emoji) + '/@me').catch(() => {});
            }
            return 'Poll posted in ' + args.channel_id;
        }
        case 'dmuser': {
            const dm = await da('POST', '/users/@me/channels', { recipient_id: args.user_id });
            if (dm.s !== 200) throw new Error('Could not open a DM with that user');
            const r = await da('POST', '/channels/' + dm.d.id + '/messages', { content: args.message });
            if (r.s !== 200) throw new Error('User has DMs closed or blocked the bot');
            return 'DM sent to ' + args.user_id;
        }
        case 'nickname': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { nick: String(args.nickname).slice(0, 32) }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail (role hierarchy?)'); return 'Nickname set for ' + args.user_id; }
        case 'resetnickname': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { nick: null }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail (role hierarchy?)'); return 'Nickname reset for ' + args.user_id; }
        case 'warnings': { const cfg = await gGD(gid); const w = (cfg.warnings || {})[args.user_id] || []; if (!w.length) return args.user_id + ' has no warnings'; return w.map((x, i) => (i + 1) + '. ' + x.reason).join('\n'); }
        case 'clearwarnings': { const cfg = await gGD(gid); const w = cfg.warnings || {}; w[args.user_id] = []; await sGD(gid, { warnings: w }); return 'Cleared warnings for ' + args.user_id; }
        case 'removewarning': {
            const cfg = await gGD(gid); const w = cfg.warnings || {}; const list = w[args.user_id] || [];
            const idx = parseInt(args.index);
            if (!idx || idx < 1 || idx > list.length) throw new Error('No warning #' + args.index + ' (they have ' + list.length + ')');
            const removed = list.splice(idx - 1, 1)[0]; w[args.user_id] = list;
            await sGD(gid, { warnings: w });
            return 'Removed warning #' + idx + ' (' + removed.reason + '), ' + list.length + ' remaining';
        }
        case 'modnotes': { const cfg = await gGD(gid); const notes = cfg.modnotes || {}; if (!notes[args.user_id]) notes[args.user_id] = []; notes[args.user_id].push({ mod: actorId, note: args.note, date: new Date().toISOString() }); await sGD(gid, { modnotes: notes }); return 'Note added for ' + args.user_id; }
        case 'viewnotes': { const cfg = await gGD(gid); const notes = (cfg.modnotes || {})[args.user_id] || []; if (!notes.length) return 'No notes for ' + args.user_id; return notes.map((n, i) => (i + 1) + '. ' + n.note + ' (by ' + n.mod + ')').join('\n'); }
        case 'setmod': { const cfg = await gGD(gid); const mods = new Set(cfg.moderators || []); mods.add(args.user_id); await sGD(gid, { moderators: [...mods] }); return args.user_id + ' is now a moderator'; }
        case 'removemod': { const cfg = await gGD(gid); const mods = (cfg.moderators || []).filter(id => id !== args.user_id); await sGD(gid, { moderators: mods }); return args.user_id + ' removed from moderators'; }
        case 'setmuterole': { await sGD(gid, { muteRole: args.role_id }); return 'Mute role set to ' + args.role_id; }
        case 'setnotificationchannel': { await sGD(gid, { notificationChannel: args.channel_id }); return 'Log channel set to ' + args.channel_id; }
        case 'set-prefix-channel': { await sGD(gid, { prefixChannel: args.channel_id }); return 'Prefix-command channel set to ' + args.channel_id; }
        case 'joinroleset': { const cfg = await gGD(gid); const roles = new Set(cfg.joinRoles || []); roles.add(args.role_id); await sGD(gid, { joinRoles: [...roles] }); return 'Added ' + args.role_id + ' as a join role (' + roles.size + ' total)'; }
        case 'joinroleunset': {
            const cfg = await gGD(gid);
            if (!args.role_id) { await sGD(gid, { joinRoles: [] }); return 'Cleared all join roles'; }
            const roles = new Set(cfg.joinRoles || []); roles.delete(args.role_id); await sGD(gid, { joinRoles: [...roles] });
            return 'Removed ' + args.role_id + ' from join roles';
        }
        case 'banlist': { const r = await da('GET', '/guilds/' + gid + '/bans?limit=1000'); if (r.s !== 200) throw new Error('Fail'); if (!r.d.length) return 'No bans'; return r.d.map(b => b.user.username + ' (' + b.user.id + ')').join('\n'); }
        case 'modstats': {
            const cfg = await gGD(gid);
            const warnings = cfg.warnings || {}; const totalWarnings = Object.values(warnings).reduce((s, a) => s + a.length, 0);
            const warnedUsers = Object.values(warnings).filter(a => a.length > 0).length;
            const notes = cfg.modnotes || {}; const totalNotes = Object.values(notes).reduce((s, a) => s + a.length, 0);
            let bansCount = 'N/A'; const br = await da('GET', '/guilds/' + gid + '/bans?limit=1000'); if (br.s === 200) bansCount = br.d.length;
            return 'Warnings: ' + totalWarnings + ' (' + warnedUsers + ' users) | Notes: ' + totalNotes + ' | Bans: ' + bansCount + ' | Moderators: ' + (cfg.moderators || []).length;
        }
        case 'ping': { const start = Date.now(); await da('GET', '/users/@me'); const botMs = Date.now() - start; return 'Discord Bot \u21d2 `' + botMs + 'ms`\n(Websocket ping isn\'t available here \u2014 this relay talks to Discord over REST, not a live gateway connection. Run /ping directly in Discord to see both.)'; }
        case 'help': { return DC.map(c => '/' + c.name + ' - ' + c.desc).join('\n'); }
        case 'avatar': {
            const uid = args.user_id || actorId;
            const r = await da('GET', '/users/' + uid);
            if (r.s !== 200) throw new Error('User not found');
            const hash = r.d.avatar;
            return hash ? ('https://cdn.discordapp.com/avatars/' + uid + '/' + hash + '.png?size=1024') : ('https://cdn.discordapp.com/embed/avatars/' + (parseInt(r.d.discriminator || '0') % 5) + '.png');
        }
        case 'userinfo': {
            const uid = args.user_id || actorId;
            const mr = await da('GET', '/guilds/' + gid + '/members/' + uid);
            if (mr.s !== 200) throw new Error('User not found in this server');
            const m = mr.d;
            return 'Username: ' + m.user.username + '\nID: ' + m.user.id + '\nJoined: ' + m.joined_at + '\nAccount created: ' + new Date(((BigInt(m.user.id) >> 22n) + 1420070400000n).toString()).toISOString() + '\nRoles: ' + (m.roles || []).length + '\nBot: ' + (m.user.bot ? 'Yes' : 'No');
        }
        case 'serverinfo': {
            const r = await da('GET', '/guilds/' + gid + '?with_counts=true');
            if (r.s !== 200) throw new Error('Fail');
            return 'Name: ' + r.d.name + '\nID: ' + r.d.id + '\nOwner: ' + r.d.owner_id + '\nMembers: ' + (r.d.approximate_member_count ?? '?') + '\nBoost tier: ' + r.d.premium_tier;
        }
        case 'rank': {
            const cfg = await gGD(gid); const uid = args.user_id || actorId;
            const exp = ((cfg.users || {})[uid] || { exp: 0 }).exp || 0;
            const level = Math.floor(exp / XP_FOR_NEXT_LEVEL);
            return uid + ' is level ' + level + ' (' + exp + ' XP, ' + (XP_FOR_NEXT_LEVEL - (exp - level * XP_FOR_NEXT_LEVEL)) + ' XP to next level)';
        }
        case 'leaderboard': {
            const cfg = await gGD(gid); const users = cfg.users || {};
            const arr = Object.entries(users).map(([id, u]) => ({ id, exp: u.exp || 0, level: Math.floor((u.exp || 0) / XP_FOR_NEXT_LEVEL) })).sort((a, b) => b.exp - a.exp).slice(0, 10);
            if (!arr.length) return 'No users yet';
            return arr.map((u, i) => (i + 1) + '. ' + u.id + ' - Level ' + u.level + ' (' + u.exp + ' XP)').join('\n');
        }
        case 'setlevel': { const cfg = await gGD(gid); const users = cfg.users || {}; const xp = (parseInt(args.level) || 0) * XP_FOR_NEXT_LEVEL; users[args.user_id] = { ...(users[args.user_id] || {}), exp: xp }; await sGD(gid, { users }); return args.user_id + ' set to level ' + args.level + ' (' + xp + ' XP)'; }
        case 'addxp': { const cfg = await gGD(gid); const users = cfg.users || {}; const u = users[args.user_id] || { exp: 0 }; u.exp = (u.exp || 0) + (parseInt(args.amount) || 0); users[args.user_id] = u; await sGD(gid, { users }); return args.user_id + ' now has ' + u.exp + ' XP'; }
        case 'addcoins': { const cfg = await gGD(gid); const users = cfg.users || {}; const u = users[args.user_id] || { exp: 0, coins: 0 }; u.coins = (u.coins || 0) + (parseInt(args.amount) || 0); users[args.user_id] = u; await sGD(gid, { users }); return args.user_id + ' now has ' + u.coins + ' coins'; }
        case 'removecoins': { const cfg = await gGD(gid); const users = cfg.users || {}; const u = users[args.user_id] || { exp: 0, coins: 0 }; u.coins = Math.max(0, (u.coins || 0) - (parseInt(args.amount) || 0)); users[args.user_id] = u; await sGD(gid, { users }); return args.user_id + ' now has ' + u.coins + ' coins'; }
        case 'balance': { const cfg = await gGD(gid); const uid = args.user_id || actorId; const u = (cfg.users || {})[uid] || { coins: 0 }; return uid + ' has ' + (u.coins || 0) + ' coins'; }
        case 'voicekick': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { channel_id: null }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail (are they in a voice channel?)'); return 'Disconnected ' + args.user_id + ' from voice'; }
        case 'voicemute': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { mute: true }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); return 'Voice-muted ' + args.user_id; }
        case 'voiceunmute': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { mute: false }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); return 'Removed voice mute for ' + args.user_id; }
        case 'voicedeafen': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { deaf: true }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); return 'Voice-deafened ' + args.user_id; }
        case 'voiceundeafen': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { deaf: false }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail'); return 'Removed voice deafen for ' + args.user_id; }
        case 'movevoice': { const r = await da('PATCH', '/guilds/' + gid + '/members/' + args.user_id, { channel_id: args.channel_id }); if (r.s !== 200) throw new Error(r.d?.message || 'Fail (are they in a voice channel?)'); return 'Moved ' + args.user_id + ' to ' + args.channel_id; }
        case 'set-welcome-channel': { await sGD(gid, { welcome: { channelId: args.channel_id } }); return 'Welcome channel set to ' + args.channel_id; }
        case 'set-welcome-message': { await sGD(gid, { welcome: { message: args.message } }); return 'Welcome message updated'; }
        // ── Ping realtime (persistence only -- see NOTE above DC entry) ──
        case 'add-ping-realtime': {
            const cfg = await gGD(gid); const list = cfg.pingChannels || [];
            if (list.includes(args.channel_id)) return args.channel_id + ' is already in the monitored list';
            list.push(args.channel_id);
            await sGD(gid, { pingChannels: list });
            return 'Added ' + args.channel_id + ' to ping-realtime monitoring. This takes effect next time the bot restarts.';
        }
        case 'stop-ping-realtime': {
            const cfg = await gGD(gid); const list = (cfg.pingChannels || []).filter(id => id !== args.channel_id);
            await sGD(gid, { pingChannels: list });
            return 'Removed ' + args.channel_id + ' from ping-realtime monitoring. This takes effect next time the bot restarts.';
        }
        default: throw new Error('Not implemented');
    }
}

const DEFAULT_AUTOMOD = {
    antiSpam: { enabled: false, maxMessages: 5, interval: 5, action: 'mute' },
    antiRepeat: { enabled: false, maxRepeats: 3, action: 'delete' },
    antiInvite: { enabled: false, action: 'delete' },
    antiLink: { enabled: false, action: 'delete' },
    antiMassMention: { enabled: false, maxMentions: 5, action: 'mute' },
    antiCaps: { enabled: false, threshold: 70, action: 'delete' },
    antiEmoji: { enabled: false, maxEmojis: 10, action: 'delete' },
    bannedWords: { enabled: false, action: 'delete', words: [] },
    whitelistChannels: [],
    whitelistRoles: [],
};
const DEFAULT_WELCOME = { channelId: '', message: 'Welcome {user} to {server}!' };
const DEFAULT_LOGGING = { channelId: '' };

// ═══════════════════════════════════════════════════════════════════════
// ACTION DISPATCH — every action the dashboard can request. `ok(x)` and
// `fail(msg)` are just small shape-helpers; the actual write to Supabase
// happens once, centrally, after this returns (see processRequest below).
// ═══════════════════════════════════════════════════════════════════════
function ok(data) { return { ok: true, data }; }
function fail(message) { return { ok: false, message }; }

async function dispatch(row) {
    const { action, params, query, body } = row;
    const p = params || {}, q = query || {}, b = body || {};

    // ── Actions that don't require a session at all ──
    if (action === 'auth_check') {
        const session = await getSession(row.token);
        return ok(session ? { auth: true, user: session.username, id: session.user_id, avatar: session.avatar, isOwner: session.is_owner } : { auth: false });
    }

    // ── Everything else requires a valid session ──
    const session = await getSession(row.token);
    if (!session) return fail('Unauthorized');

    // Per-guild access check helper (mirrors gAu + nP from the original).
    async function requirePerm(guildId, permKey) {
        const perms = await getGuildPerms(session, guildId);
        if (!perms) return { error: fail('No access') };
        if (permKey && !perms[permKey]) return { error: fail('No permission: ' + permKey) };
        return { perms };
    }
    function requireOwner() {
        return session.is_owner ? null : fail('Owner only');
    }

    switch (action) {
        case 'auth_logout': {
            await supabase.from('dashboard_sessions').delete().eq('token', row.token);
            await supabase.from('dashboard_users').update({ online: false, last_seen: new Date().toISOString() }).eq('id', session.user_id);
            return ok({ ok: true });
        }
        case 'heartbeat': {
            await supabase.from('dashboard_users').update({ online: true, last_seen: new Date().toISOString() }).eq('id', session.user_id);
            return ok({ ok: true });
        }
        case 'user_offline': {
            await supabase.from('dashboard_users').update({ online: false, last_seen: new Date().toISOString() }).eq('id', session.user_id);
            return ok({ ok: true });
        }
        case 'list_dashboard_users': {
            const { data } = await supabase.from('dashboard_users').select('*').order('created_at', { ascending: true });
            const INACTIVE_MS = 90000;
            const users = (data || []).map(u => ({ ...u, online: u.last_seen && (Date.now() - new Date(u.last_seen).getTime()) < INACTIVE_MS }));
            return ok({ users });
        }
        case 'delete_dashboard_user': {
            if (requireOwner()) return requireOwner();
            if (p.uid === session.user_id) return fail('Cannot delete self');
            await supabase.from('dashboard_users').delete().eq('id', p.uid);
            await supabase.from('allowed_users').delete().eq('user_id', p.uid);
            guildCache = [];
            return ok({ ok: true });
        }
        case 'bot_status': {
            const { data: row2 } = await supabase.from('data').select('data').eq('username', '__bot_status__').maybeSingle();
            const bd = row2?.data || {};
            const started = bd.started_at ? new Date(bd.started_at) : new Date();
            const diff = Math.floor((Date.now() - started.getTime()) / 1000);
            const h = Math.floor(diff / 3600), m = Math.floor((diff % 3600) / 60), s = diff % 60;
            const upStr = (h > 0 ? h + 'h ' : '') + (m > 0 ? m + 'm ' : '') + s + 's';
            return ok({ online: true, uptime: upStr, started_at: bd.started_at || started.toISOString() });
        }
        case 'my_guilds': {
            const all = await fetchBotGuilds();
            const trusted = await gTr();
            // Used to also require the guild to be in session.guild_ids --
            // a snapshot of the Discord servers YOU were in, captured once
            // during login by a separate Discord API call in auth.php. If
            // that call ever failed or came back empty (rate-limited,
            // proxy hiccup, whatever), guild_ids ended up [] and this
            // intersection silently zeroed out EVERY guild -- including
            // for the owner, who doesn't need that check at all: is_owner
            // already grants full access below regardless of personal
            // Discord membership. That's exactly what "logged in, online,
            // but shows Add Bot / nothing loads" was -- the bot really did
            // have guilds, the session just couldn't prove you shared them.
            // The owner now sees every guild the bot is in, full stop. A
            // non-owner's real access boundary is (and always was) the
            // allowed_users table below, so dropping the redundant, fragile
            // guild_ids check doesn't loosen anything for them either.
            if (session.is_owner) {
                return ok({ guilds: all.map(g => ({ ...g, trusted: trusted.includes(g.id), permissions: fullPerms() })), trusted, isOwner: true, invite: BOT_INVITE });
            }
            const { data: au } = await supabase.from('allowed_users').select('*').eq('user_id', session.user_id);
            const map = {}; (au || []).forEach(a => { map[a.guild_id] = a.permissions || {}; });
            return ok({ guilds: all.filter(g => map[g.id]).map(g => ({ ...g, trusted: trusted.includes(g.id), permissions: { ...fullPerms(), ...map[g.id] } })), trusted, isOwner: false, invite: BOT_INVITE });
        }
        case 'guild_leave': {
            const r1 = await requirePerm(p.id, null); if (r1.error) return r1.error;
            if (requireOwner()) return requireOwner();
            const trusted = await gTr();
            if (trusted.includes(p.id)) return fail('Trusted');
            const r = await da('DELETE', '/users/@me/guilds/' + p.id);
            if (r.s !== 204) return fail('Discord error ' + r.s);
            guildCache = guildCache.filter(g => g.id !== p.id);
            return ok({ ok: true });
        }
        case 'get_members': {
            const r1 = await requirePerm(p.id, 'can_view_members'); if (r1.error) return r1.error;
            const search = (q.search || '').toLowerCase();
            const limit = Math.min(parseInt(q.limit) || 200, 1000);
            let all = [], after = null;
            for (let i = 0; i < 15; i++) {
                let path = '/guilds/' + p.id + '/members?limit=' + Math.min(limit - all.length, 1000);
                if (after) path += '&after=' + after;
                const r = await da('GET', path);
                if (r.s !== 200 || !r.d.length) break;
                all = all.concat(r.d); after = r.d[r.d.length - 1].user.id;
                if (all.length >= limit) break;
            }
            if (search) all = all.filter(m => m.user.username.toLowerCase().includes(search) || m.user.id.includes(search));
            const g = guildCache.find(x => x.id === p.id);
            return ok({ members: all, total: g?.approximate_member_count || all.length });
        }
        case 'kick_member': {
            const r1 = await requirePerm(p.id, 'can_kick'); if (r1.error) return r1.error;
            const r = await da('DELETE', '/guilds/' + p.id + '/members/' + p.userId);
            if (r.s !== 204) return fail(r.d?.message || String(r.s));
            return ok({ ok: true });
        }
        case 'ban_member': {
            const r1 = await requirePerm(p.id, 'can_ban'); if (r1.error) return r1.error;
            const r = await da('PUT', '/guilds/' + p.id + '/bans/' + p.userId, { delete_message_days: 0, reason: (b.reason || 'Dash') + ' [Dash]' });
            if (r.s !== 204) return fail(r.d?.message || String(r.s));
            return ok({ ok: true });
        }
        case 'get_bans': {
            const r1 = await requirePerm(p.id, 'can_view_bans'); if (r1.error) return r1.error;
            const r = await da('GET', '/guilds/' + p.id + '/bans?limit=100');
            if (r.s !== 200) return fail(String(r.s));
            return ok(r.d);
        }
        case 'get_channels': {
            const r1 = await requirePerm(p.id, null); if (r1.error) return r1.error;
            const r = await da('GET', '/guilds/' + p.id + '/channels');
            if (r.s !== 200) return fail(String(r.s));
            return ok(r.d);
        }
        case 'get_roles': {
            const r1 = await requirePerm(p.id, 'can_manage_roles'); if (r1.error) return r1.error;
            const r = await da('GET', '/guilds/' + p.id + '/roles');
            if (r.s !== 200) return fail(String(r.s));
            return ok(r.d);
        }
        case 'get_guild_info': {
            const r1 = await requirePerm(p.id, null); if (r1.error) return r1.error;
            const r = await da('GET', '/guilds/' + p.id + '?with_counts=true');
            if (r.s !== 200) return fail(String(r.s));
            return ok(r.d);
        }
        case 'get_config': {
            const r1 = await requirePerm(p.gid, 'can_view_config'); if (r1.error) return r1.error;
            return ok(await gGD(p.gid));
        }
        case 'set_config': {
            const r1 = await requirePerm(p.gid, null); if (r1.error) return r1.error;
            if (requireOwner()) return requireOwner();
            return ok({ ok: true, config: await sGD(p.gid, b) });
        }
        case 'get_trusted': {
            return ok({ trusted: await gTr() });
        }
        case 'add_trusted': {
            if (requireOwner()) return requireOwner();
            if (!b.id) return fail('ID required');
            const { data: r } = await supabase.from('data').select('data').eq('username', '__trusted_guilds__').maybeSingle();
            const ex = r?.data?.trusted || [];
            const merged = [...new Set([...ex, b.id])];
            await supabase.from('data').upsert({ username: '__trusted_guilds__', data: { trusted: merged } }, { onConflict: ['username'] });
            return ok({ ok: true, trusted: merged });
        }
        case 'remove_trusted': {
            if (requireOwner()) return requireOwner();
            const { data: r } = await supabase.from('data').select('data').eq('username', '__trusted_guilds__').maybeSingle();
            const dbList = (r?.data?.trusted || []).filter(i => i !== p.id).filter(i => !TRUSTED_ENV.includes(i));
            await supabase.from('data').upsert({ username: '__trusted_guilds__', data: { trusted: dbList } }, { onConflict: ['username'] });
            guildCache = [];
            return ok({ ok: true });
        }
        case 'bot_leave_status': {
            if (requireOwner()) return requireOwner();
            const trusted = await gTr();
            for (const tid of trusted) {
                const c = await gGD(tid);
                if (c.bot_leave_active) return ok({ active: true, channel: c.bot_leave_channel || null });
            }
            return ok({ active: false });
        }
        case 'bot_leave_start': {
            if (requireOwner()) return requireOwner();
            if (!b.channel) return fail('Channel required');
            const trusted = await gTr();
            if (!trusted.length) return fail('No trusted guild set');
            await sGD(trusted[0], { bot_leave_active: true, bot_leave_channel: b.channel });
            return ok({ ok: true });
        }
        case 'bot_leave_stop': {
            if (requireOwner()) return requireOwner();
            const trusted = await gTr();
            for (const tid of trusted) await sGD(tid, { bot_leave_active: false });
            return ok({ ok: true });
        }
        case 'get_economy': {
            const r1 = await requirePerm(p.gid, 'can_view_economy'); if (r1.error) return r1.error;
            const c = await gGD(p.gid);
            const users = c.users || {};
            const sorted = Object.entries(users).map(([id, d]) => ({ id, ...d })).filter(u => (u.coins || 0) > 0 || (u.level || 0) > 0).sort((a, b2) => (b2.coins || 0) - (a.coins || 0));
            const top = await Promise.all(sorted.slice(0, 50).map(async u => {
                try { const r = await da('GET', '/users/' + u.id); if (r.s === 200) { u.username = r.d.username; u.avatar = r.d.avatar; } } catch { }
                return u;
            }));
            return ok({ users: top, total: sorted.length });
        }
        case 'get_allowed_users': {
            if (requireOwner()) return requireOwner();
            const { data } = await supabase.from('allowed_users').select('*').eq('guild_id', p.gid);
            const users = await Promise.all((data || []).map(async a => {
                const { data: u } = await supabase.from('dashboard_users').select('*').eq('id', a.user_id).maybeSingle();
                const INACTIVE_MS = 90000;
                return { ...a, username: u?.username || a.user_id, avatar: u?.avatar, online: u ? (u.last_seen && (Date.now() - new Date(u.last_seen).getTime()) < INACTIVE_MS) : false };
            }));
            return ok({ users });
        }
        case 'add_allowed_user': {
            if (requireOwner()) return requireOwner();
            if (!b.user_id) return fail('User ID required');
            const { data: u } = await supabase.from('dashboard_users').select('id').eq('id', b.user_id).maybeSingle();
            if (!u) return fail('User not registered on this dashboard yet');
            const { data: ex } = await supabase.from('allowed_users').select('id').eq('guild_id', p.gid).eq('user_id', b.user_id).maybeSingle();
            if (ex) return fail('Already allowed');
            await supabase.from('allowed_users').insert({ guild_id: p.gid, user_id: b.user_id, permissions: b.permissions || { can_view_members: 1, can_view_bans: 1, can_view_economy: 1, can_view_config: 1 } });
            return ok({ ok: true });
        }
        case 'set_allowed_permissions': {
            if (requireOwner()) return requireOwner();
            await supabase.from('allowed_users').update({ permissions: b.permissions }).eq('guild_id', p.gid).eq('user_id', p.uid);
            return ok({ ok: true });
        }
        case 'remove_allowed_user': {
            if (requireOwner()) return requireOwner();
            await supabase.from('allowed_users').delete().eq('guild_id', p.gid).eq('user_id', p.uid);
            return ok({ ok: true });
        }
        case 'get_commands': {
            try {
                const discordCmds = await gDiscordCmds(q.gid || '');
                const dashNames = new Set(DC.map(c => c.name));
                const extra = discordCmds.filter(c => !dashNames.has(c.name)).map(c => ({ name: c.name, desc: c.description || 'No description', cat: 'Bot', perm: null, executable: false, params: (c.options || []).filter(o => o.type !== 1 && o.type !== 2).map(mapOptToParam) }));
                const dash = DC.map(c => ({ ...c, executable: true }));
                return ok({ commands: [...dash, ...extra] });
            } catch { return ok({ commands: DC.map(c => ({ ...c, executable: true })) }); }
        }
        case 'execute_command': {
            const r1 = await requirePerm(p.id, 'can_use_commands'); if (r1.error) return r1.error;
            const { command, args } = b;
            if (!command || !args) return fail('Missing command/args');
            const cmd = DC.find(c => c.name === command);
            if (!cmd) return fail('Unknown command');
            if (!checkCooldown(p.id, command)) return fail('Cooldown — try again in a few seconds');
            try {
                const result = await exC(p.id, command, args, session.user_id);
                return ok({ ok: true, result });
            } catch (e) { return fail(e.message); }
        }
        case 'get_automod': {
            const r1 = await requirePerm(p.gid, 'can_manage_automod'); if (r1.error) return r1.error;
            const c = await gGD(p.gid);
            return ok(c.automod || DEFAULT_AUTOMOD);
        }
        case 'set_automod': {
            const r1 = await requirePerm(p.gid, 'can_manage_automod'); if (r1.error) return r1.error;
            return ok({ ok: true, automod: (await sGD(p.gid, { automod: b })).automod });
        }
        // ── Running bot instances / leader election (Servers tab) ──
        // Mirrors what discord.js's own leader-election block writes: one
        // row per live process (key '__instance_<processId>'), heartbeated
        // every 5s, plus a single lock row (key '999999999999999999') whose
        // `holder` is whichever process is currently allowed to actually
        // handle messages/commands. Everything else is a hot standby.
        case 'get_instances': {
            // Deliberately not owner-gated (unlike Trusted Servers / Allowed
            // Users) — this is the same kind of read-only operational status
            // as Bot Info/Bot Status, not per-guild permission data, and
            // gating it caused it to silently show nothing for anyone the
            // dashboard didn't happen to recognize as the configured owner.
            const LEADER_LOCK_KEY = '999999999999999999';
            const LEASE_MS = 15000;
            // 45s, not 20s: different hosts (especially free-tier panels)
            // can have real clock drift between them, and this comparison
            // is between THIS relay's Date.now() and a timestamp another
            // host wrote with ITS OWN clock. A tight window was silently
            // dropping perfectly-alive instances whenever a host's clock
            // ran more than ~15s behind -- they just vanished from the
            // list with no error anywhere to point at. 45s comfortably
            // tolerates realistic drift while still dropping a genuinely
            // crashed process within under a minute.
            const STALE_MS = 45000;
            const now = Date.now();

            // Both reads below used to ignore `error` entirely -- a single
            // transient network blip between THIS relay and Supabase (which
            // happens far more often than an actual instance going down,
            // especially with several hosts all hitting Supabase at once)
            // made `data` come back undefined, which silently became an
            // empty instances list on that one poll. The dashboard then
            // dutifully rendered "server disappeared" for a request that
            // never actually reflected reality, then rendered it back a few
            // seconds later once a read succeeded again -- the flicker.
            // One retry absorbs a single blip; if it still fails, this
            // throws so the request surfaces as a real error instead of a
            // fake "0 instances", and the frontend now knows to keep
            // showing its last good render instead of wiping it.
            async function readWithRetry(query) {
                let { data, error } = await query();
                if (error) { ({ data, error } = await query()); }
                if (error) throw error;
                return data;
            }
            const rows = await readWithRetry(() => supabase.from('data').select('username, data').like('username', '\\_\\_instance\\_%'));
            const lockRow = await readWithRetry(() => supabase.from('data').select('data').eq('username', LEADER_LOCK_KEY).maybeSingle());
            const lock = lockRow?.data ? (typeof lockRow.data === 'string' ? JSON.parse(lockRow.data) : lockRow.data) : null;
            const lockIsLive = lock && lock.holder && lock.heartbeatAt && (now - lock.heartbeatAt) <= LEASE_MS;

            const instances = (rows || [])
                .map(r => (typeof r.data === 'string' ? JSON.parse(r.data) : r.data))
                .filter(d => d && d.processId && (now - (d.lastHeartbeat || 0)) <= STALE_MS)
                .sort((a, b2) => (a.startedAt || 0) - (b2.startedAt || 0))
                .map((d, i) => ({
                    label: 'Server ' + (i + 1),
                    processId: d.processId,
                    startedAt: d.startedAt,
                    lastHeartbeat: d.lastHeartbeat,
                    ageMs: now - d.lastHeartbeat,
                    // Trust the lock row over the instance's own last-written
                    // isLeader flag when the lock is confirmed live -- the
                    // instance's own flag can be a few seconds stale between
                    // its own heartbeats.
                    isLeader: lockIsLive ? (d.processId === lock.holder) : !!d.isLeader
                }));

            // Opportunistic cleanup: a process that gets killed outright
            // (most hosting panels don't send a graceful shutdown signal on
            // restart/redeploy) never gets the chance to delete its own
            // row, so these just pile up in the `data` table forever unless
            // something else removes them. The bot's own leader already
            // sweeps for these once a minute, but this piggybacks on
            // whoever's actually looking at the Servers tab right now for
            // near-immediate cleanup too. 2 minutes (not the 45s display
            // cutoff above) so nothing borderline-but-still-alive ever gets
            // deleted -- this only ever removes rows that are unambiguously
            // dead. Fire-and-forget: never slows down the response.
            const INSTANCE_CLEANUP_MS = 120000;
            const deadUsernames = (rows || []).filter(r => {
                const d = typeof r.data === 'string' ? (() => { try { return JSON.parse(r.data); } catch { return null; } })() : r.data;
                return !d || !d.lastHeartbeat || (now - d.lastHeartbeat) > INSTANCE_CLEANUP_MS;
            }).map(r => r.username);
            if (deadUsernames.length) {
                supabase.from('data').delete().in('username', deadUsernames).then(() => {}, () => {});
            }

            return ok({
                instances,
                total: instances.length,
                leaderConfirmed: !!lockIsLive,
                serverTime: now
            });
        }
        case 'get_welcome': {
            const r1 = await requirePerm(p.gid, 'can_manage_welcome'); if (r1.error) return r1.error;
            const c = await gGD(p.gid);
            // Real fields your bot's guildMemberAdd handler reads are TOP-LEVEL
            // welcomeChannel/welcomeMessage -- not nested under a 'welcome' key.
            return ok({ channelId: c.welcomeChannel || '', message: c.welcomeMessage || DEFAULT_WELCOME.message });
        }
        case 'set_welcome': {
            const r1 = await requirePerm(p.gid, 'can_manage_welcome'); if (r1.error) return r1.error;
            const patch = {};
            if (b.channelId !== undefined) patch.welcomeChannel = b.channelId;
            if (b.message !== undefined) patch.welcomeMessage = b.message;
            const saved = await sGD(p.gid, patch);
            return ok({ ok: true, channelId: saved.welcomeChannel || '', message: saved.welcomeMessage || '' });
        }
        case 'get_logging': {
            const r1 = await requirePerm(p.gid, 'can_manage_logging'); if (r1.error) return r1.error;
            const c = await gGD(p.gid);
            // Real field is TOP-LEVEL notificationChannel (set by /setnotificationchannel).
            // Your bot only ever logs to it for AutoMod actions and /clearchannel --
            // there's no real per-event-type filtering, unlike what this tab used to show.
            return ok({ channelId: c.notificationChannel || '' });
        }
        case 'set_logging': {
            const r1 = await requirePerm(p.gid, 'can_manage_logging'); if (r1.error) return r1.error;
            const saved = await sGD(p.gid, { notificationChannel: b.channelId || '' });
            return ok({ ok: true, channelId: saved.notificationChannel || '' });
        }
        case 'bot_info': {
            const r = await da('GET', '/users/@me');
            if (r.s !== 200) return fail('Failed to fetch bot info');
            return ok({ id: r.d.id, username: r.d.username, avatar: r.d.avatar, verified: r.d.verified, bot: true });
        }
        // ── Persistent relay logs (new Logs tab data source) ──
        // Reads back what persistLog() saved to the `dashboard_logs` table:
        // every request claim (processing), completion, failure, and any
        // internal relay error, newest first. Optional filters: ?level=error
        // (or warn/info) and ?limit=N (default 100, max 500). Deliberately
        // not owner-gated, same reasoning as get_instances: it's read-only
        // operational status, not per-guild permission data.
        case 'get_logs': {
            const limit = Math.min(parseInt(q.limit) || 100, 500);
            let query = supabase.from(LOG_TABLE).select('*');
            if (q.level) query = query.eq('level', q.level);
            const { data, error } = await query.order('created_at', { ascending: false }).limit(limit);
            if (error) return fail('Could not read logs (has schema-dashboard-logs.sql been run?): ' + error.message);
            return ok({ logs: data || [] });
        }
        default:
            return fail('Unknown action: ' + action);
    }
}

// ═══════════════════════════════════════════════════════════════════════
// REQUEST PROCESSING LOOP
// ═══════════════════════════════════════════════════════════════════════
const inFlight = new Set(); // guards against double-processing the same row id

// Second layer of the stuck-request fix (the first is the 15s timeout
// inside hR): no matter what happens inside dispatch(), it can never hold
// a request open longer than DISPATCH_TIMEOUT_MS. If it does, the request
// is marked 'error' with a clear message instead of sitting at
// 'processing' forever. 60s comfortably covers the legitimately longest
// actions (clearchannel / lockdown loop many Discord calls; each call now
// self-limits at 15s), and the stuck-row reaper below is the final
// safety net for anything beyond even this.
const DISPATCH_TIMEOUT_MS = 60000;
function withTimeout(promise, ms, message) {
    let timer;
    const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), ms); if (timer.unref) timer.unref(); });
    return Promise.race([Promise.resolve(promise), timeout]).finally(() => clearTimeout(timer));
}

// Final safety net for requests stuck at 'processing': rows that were
// claimed but whose claiming relay died (host panel restart, crash,
// network cut) BEFORE it could write completed/error. Every STUCK_ROW_MS,
// the leader re-marks those rows as 'error' with a clear "retry it"
// message, so nothing is ever left sitting at 'processing' indefinitely
// and the dashboard always gets a definitive answer. Rows this very
// process is actively working on are skipped (inFlight); its own long
// dispatches are already bounded by DISPATCH_TIMEOUT_MS above.
const STUCK_ROW_MS = 150000; // 2.5 min — well past DISPATCH_TIMEOUT_MS, so anything older is definitionally abandoned
async function reapStuckRows() {
    try {
        const cutoff = new Date(Date.now() - STUCK_ROW_MS).toISOString();
        const { data, error } = await supabase.from('dashboard_requests').select('*').eq('status', 'processing').lt('created_at', cutoff);
        if (error) throw error;
        for (const row of data || []) {
            if (inFlight.has(row.id)) continue; // we are actively working on it right now
            let claimedBy = null;
            try { const resp = typeof row.response === 'string' ? JSON.parse(row.response) : row.response; claimedBy = resp?.claimedBy || null; } catch { }
            const msg = 'Request "' + row.action + '" (' + row.id + ') was stuck in processing for over ' + Math.round(STUCK_ROW_MS / 1000) + 's'
                + (claimedBy ? ' — claimed by relay ' + String(claimedBy).slice(0, 8) + ', which most likely died mid-request' : '')
                + ' — marked as error so it can be retried.';
            const { error: upErr } = await supabase.from('dashboard_requests').update({ status: 'error', response: { error: 'Timed out — the relay that claimed this request did not complete it in time (it likely restarted or lost connection mid-request). Please retry.' } }).eq('id', row.id).eq('status', 'processing');
            if (upErr) { console.error('[Relay-Reaper] Could not mark stuck row ' + row.id + ' as error:', upErr.message); continue; }
            persistLog('warn', 'reaper', msg, { request_id: row.id, action: row.action, status: 'error', meta: { claimedBy } });
        }
    } catch (e) {
        console.error('[Relay-Reaper] Stuck-row reaper failed:', e.message);
        persistLog('error', 'reaper', 'Stuck-row reaper failed: ' + e.message);
    }
}

let claimUnsupportedLogged = false;
async function processRequest(row) {
    if (!row || row.status !== 'pending' || inFlight.has(row.id)) return;
    inFlight.add(row.id);
    let dispatchStarted = 0; // hoisted (not inside try) so the catch below can report a duration too
    try {
        // Atomically claim this row before doing anything else. With
        // multiple hosted instances running (throttle.js starts dashboard.js
        // on every host, not just the leader), every one of those relay
        // processes gets the same Realtime INSERT event and would otherwise
        // all run the same action at once -- multiplying side effects
        // (a ban/kick/config-save firing once per host) and racing to
        // overwrite each other's response. This UPDATE only succeeds for
        // whichever process's request lands first (status must still be
        // 'pending'); every other relay's claim attempt affects 0 rows and
        // backs off.
        //
        // NOTE: this needs 'processing' added to the `dashboard_requests`
        // table's status CHECK constraint, e.g. in the Supabase SQL editor:
        //   ALTER TABLE dashboard_requests DROP CONSTRAINT <constraint_name>;
        //   ALTER TABLE dashboard_requests ADD CONSTRAINT <constraint_name>
        //     CHECK (status IN ('pending','processing','completed','error'));
        // (find <constraint_name> under Database > Tables > dashboard_requests
        // > the status column's constraints in the Supabase dashboard).
        // Until that's run, the claim attempt below fails with a
        // check-constraint error every time -- so it's wrapped in its own
        // try/catch that falls back to the old unprotected-but-working
        // behavior instead of taking the whole relay process down with it.
        let claimedHere = true;
        try {
            // response is otherwise only ever written at completion, so
            // temporarily stamping the claimer's identity into it here is
            // safe (it gets overwritten below) and, for now, the only
            // reliable way to find out WHICH process actually won a given
            // claim when it isn't one of the terminals we're watching.
            const { data: claimed, error: claimErr } = await supabase
                .from('dashboard_requests')
                .update({ status: 'processing', response: { claimedBy: RELAY_PROCESS_ID, claimedAt: new Date().toISOString() } })
                .eq('id', row.id)
                .eq('status', 'pending')
                .select();
            if (claimErr) throw claimErr;
            claimedHere = !!(claimed && claimed.length);
        } catch (claimEx) {
            if (!claimUnsupportedLogged) {
                claimUnsupportedLogged = true;
                const msg = 'Could not claim requests atomically (running without duplicate-host protection until this is fixed): ' + (claimEx?.message || claimEx) + '. Run the dashboard_requests section of schema-dashboard-logs.sql in the Supabase SQL editor to fix this for real.';
                console.error('[Relay] ' + msg);
                persistLog('warn', 'claim', msg, { request_id: row.id, action: row.action });
            }
            claimedHere = true; // fail open: process it rather than silently dropping every request
        }
        if (!claimedHere) return; // another relay instance already claimed this row

        console.log('[Relay] Claimed "' + row.action + '" (' + row.id + '), dispatching...');
        persistLog('info', 'request', 'Claimed "' + row.action + '" — dispatching (status: processing).', { request_id: row.id, action: row.action, status: 'processing' });
        dispatchStarted = Date.now();
        // Bound the dispatch in time (see withTimeout above): if it somehow
        // still hangs despite the per-call timeouts, this turns the hang
        // into an 'error' row the dashboard can actually show, instead of
        // leaving the request stamped 'processing' forever.
        const result = await withTimeout(
            dispatch(row),
            DISPATCH_TIMEOUT_MS,
            'dispatch timed out after ' + Math.round(DISPATCH_TIMEOUT_MS / 1000) + 's without finishing — please retry'
        );
        console.log('[Relay] dispatch() for "' + row.action + '" returned after ' + (Date.now() - dispatchStarted) + 'ms, ok=' + result.ok);
        const dispatchMs = Date.now() - dispatchStarted;
        if (result.ok) {
            const { error: doneErr } = await supabase.from('dashboard_requests').update({ status: 'completed', response: result.data }).eq('id', row.id);
            if (doneErr) {
                console.error('[Relay] Could not write completed status for "' + row.action + '" (' + row.id + '):', doneErr.message);
                persistLog('error', 'request', 'Action "' + row.action + '" finished OK but the completed status could not be saved: ' + doneErr.message, { request_id: row.id, action: row.action, status: 'completed', duration_ms: dispatchMs });
            } else {
                persistLog('info', 'request', 'Completed "' + row.action + '" in ' + dispatchMs + 'ms.', { request_id: row.id, action: row.action, status: 'completed', duration_ms: dispatchMs });
            }
        } else {
            const { error: doneErr } = await supabase.from('dashboard_requests').update({ status: 'error', response: { error: result.message } }).eq('id', row.id);
            if (doneErr) {
                console.error('[Relay] Could not write error status for "' + row.action + '" (' + row.id + '):', doneErr.message);
                persistLog('error', 'request', 'Action "' + row.action + '" failed ("' + result.message + '") AND the error status could not be saved: ' + doneErr.message, { request_id: row.id, action: row.action, status: 'error', duration_ms: dispatchMs });
            } else {
                persistLog('error', 'request', 'Failed "' + row.action + '" after ' + dispatchMs + 'ms: ' + result.message, { request_id: row.id, action: row.action, status: 'error', duration_ms: dispatchMs });
            }
        }
        console.log('[Relay] "' + row.action + '" (' + row.id + ') fully done.');
    } catch (e) {
        console.error('[Relay] Error processing action "' + row.action + '":', e);
        persistLog('error', 'request', 'Error while processing "' + row.action + '": ' + (e.message || e), { request_id: row.id, action: row.action, status: 'error', duration_ms: dispatchStarted ? Date.now() - dispatchStarted : null });
        // NOTE: the Supabase query builder is thenable (awaitable) but does
        // NOT implement a real .catch() method -- chaining .catch() on it
        // directly (instead of on the Promise it resolves to) threw
        // "...catch is not a function" and crashed the whole relay process,
        // which is why this used to appear as a restart loop. A plain
        // try/catch around the awaited call is the correct way to make a
        // best-effort update here.
        try {
            await supabase.from('dashboard_requests').update({ status: 'error', response: { error: e.message || 'Internal error' } }).eq('id', row.id);
        } catch (e2) { /* best effort -- the stuck-row reaper will eventually close this row out */ }
    } finally {
        inFlight.delete(row.id);
        // The row (including the caller's raw session token, needed for
        // getSession() lookups above) sits in this table until the browser
        // reads the result back. Delete it a few seconds after completion
        // -- long enough for either the Realtime push or the 1.2s poll
        // fallback to have delivered it, short enough to close the window
        // a token could otherwise be read out of this table for.
        setTimeout(() => {
            (async () => {
                try { await supabase.from('dashboard_requests').delete().eq('id', row.id); } catch (e3) { /* best effort */ }
            })();
        }, 15000);
    }
}

// Primary path: instant reaction via native Postgres LISTEN/NOTIFY
// (replaces Supabase Realtime; see the trigger in schema.sql). db.listen()
// already handles reconnecting on a dropped connection on its own, so
// there's no "CHANNEL_ERROR" bookkeeping to do here like there was before.
function startRealtimeListener() {
    require('./db').listen('dashboard_requests_insert', async (idStr) => {
        // This table's id column is a UUID (inherited from the original
        // Supabase project), not a number — Number(idStr) was always NaN
        // here, so this silently returned on every single notification
        // and every request was falling back to the slow 2s sweep poll
        // below instead of reacting instantly. Use the id as-is.
        if (!idStr || !relayIsLeader) return;
        const { data: row, error } = await supabase.from('dashboard_requests').select('*').eq('id', idStr).maybeSingle();
        if (error || !row) return;
        processRequest(row);
    });
}

// Safety-net path: catches anything Realtime might have missed (a dropped
// connection, a brief restart window, etc.) — cheap, since it only ever
// looks at rows still marked 'pending'.
let sweepTickCount = 0;
async function sweepPending() {
    if (!relayIsLeader) return; // not our job right now -- the leader is handling it
    sweepTickCount++;
    try {
        const { data, error } = await supabase.from('dashboard_requests').select('*').eq('status', 'pending').order('created_at', { ascending: true }).limit(50);
        if (error) throw error;
        // Loud on purpose, temporarily: one line every ~10s confirming the
        // sweep is alive at all, and ALWAYS a line the moment it actually
        // finds something — right now both of these are completely silent,
        // which looks identical to "this code never runs".
        if (data && data.length) {
            console.log('[Relay] Sweep found ' + data.length + ' pending row(s): ' + data.map(r => r.id).join(', '));
        } else if (sweepTickCount % 20 === 0) {
            console.log('[Relay] Sweep tick #' + sweepTickCount + ' — 0 pending rows (leader=' + relayIsLeader + ').');
        }
        for (const row of data || []) processRequest(row);
    } catch (e) {
        console.error('[Relay] Sweep error:', e.message, e.stack);
        persistLog('error', 'sweep', 'Sweep failed: ' + e.message);
    }
    // Stuck-row reaper: every ~10 ticks (about 10s at the 500ms sweep
    // interval) give any abandoned 'processing' row a definitive ending.
    if (sweepTickCount % 10 === 0) await reapStuckRows();
}

// Keeps the table from growing forever under real traffic — this is what
// lets the system handle thousands of requests over time without ever
// needing a fixed number of pre-made rows: every request is its own row,
// and this just prunes the ones that are done with.
const MAX_ROW_AGE_MS = 2 * 60 * 1000;
async function cleanupOldRows() {
    try {
        const cutoff = new Date(Date.now() - MAX_ROW_AGE_MS).toISOString();
        await supabase.from('dashboard_requests').delete().lt('created_at', cutoff);
    } catch (e) { console.error('[Relay] Cleanup error:', e.message); }
}

// Same idea for the persistent logs: keep the table useful forever by
// pruning entries older than LOG_RETENTION_MS (7 days) every 10 minutes.
async function cleanupOldLogs() {
    try {
        const cutoff = new Date(Date.now() - LOG_RETENTION_MS).toISOString();
        await supabase.from(LOG_TABLE).delete().lt('created_at', cutoff);
    } catch (e) { console.error('[Relay] Log cleanup error:', e.message); }
}

(async () => {
    await resolveBotAppId();
    await tickRelayLeadership();
    console.log('[Relay-Leader] Startup status: ' + (relayIsLeader ? 'ACTIVE' : 'STANDBY') + ' — full process id: ' + RELAY_PROCESS_ID);
    persistLog('info', 'relay', 'Dashboard relay started (build ' + DASHBOARD_BUILD_TAG + ', process ' + RELAY_PROCESS_ID.slice(0, 8) + ', mode ' + (relayIsLeader ? 'ACTIVE' : 'STANDBY') + ').');
    setInterval(tickRelayLeadership, RELAY_HEARTBEAT_MS);
    startRealtimeListener();
    await sweepPending();
    setInterval(sweepPending, 500);
    setInterval(cleanupOldRows, 30000);
    setInterval(cleanupOldLogs, 10 * 60 * 1000);
    console.log('[Relay] Dashboard relay is up — listening for dashboard_requests.');
})();

// Last-resort safety net: any crash or unhandled rejection is now ALSO
// saved to dashboard_logs before the process (re)starts, so a relay that
// dies overnight leaves a written trace in the database instead of just
// vanishing from a terminal nobody was watching.
process.on('unhandledRejection', (reason) => {
    const msg = 'Unhandled promise rejection: ' + ((reason && reason.stack) ? reason.stack : reason);
    console.error('[Relay] ' + msg);
    persistLog('error', 'process', msg);
});
process.on('uncaughtException', (err) => {
    const msg = 'Uncaught exception: ' + ((err && err.stack) ? err.stack : err);
    console.error('[Relay] ' + msg);
    try { persistLog('error', 'process', msg); } catch { }
    // Give the log insert a brief moment to fire, then exit like before so
    // the supervisor (throttle.js) restarts the relay cleanly.
    setTimeout(() => process.exit(1), 500).unref?.();
});

process.on('SIGINT', () => { persistLog('info', 'process', 'Relay received SIGINT — shutting down.'); process.exit(0); });
process.on('SIGTERM', () => { persistLog('info', 'process', 'Relay received SIGTERM — shutting down.'); process.exit(0); });

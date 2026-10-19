-- ═══════════════════════════════════════════════════════════════════════
-- ONE-TIME MIGRATION for dashboard.js build
--   relay-leader-v5-db-logs-and-claim-fix-2026-10-19
--
-- Run the whole file once in: Supabase Dashboard → SQL Editor → New query
-- It is safe to re-run (every statement is idempotent).
--
-- It does two things:
--   1) Creates the NEW `dashboard_logs` table that the relay now writes
--      every important event into automatically (request claimed /
--      processing, completed, failed, internal errors, leader changes,
--      crashes), and which the dashboard reads back with the new
--      `get_logs` action.
--   2) Fixes the `dashboard_requests.status` CHECK constraint so the
--      'processing' status is actually allowed. Until this runs, the
--      relay's atomic claim silently fails and falls back to unprotected
--      processing (duplicate-host risk), and rows could previously get
--      stuck in limbo. After this runs, the claim works for real.
-- ═══════════════════════════════════════════════════════════════════════


-- ───────────────────────────────────────────────────────────────────────
-- 1) NEW TABLE: persistent relay logs
-- ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.dashboard_logs (
    id          bigint generated always as identity primary key,
    created_at  timestamptz NOT NULL DEFAULT now(),
    level       text        NOT NULL DEFAULT 'info',  -- 'info' | 'warn' | 'error'
    scope       text        NOT NULL DEFAULT 'relay', -- 'request' | 'claim' | 'leader' | 'sweep' | 'reaper' | 'process' | 'relay'
    message     text        NOT NULL,
    request_id  uuid,                                 -- matches dashboard_requests.id when the log is about a request
    action      text,                                 -- e.g. 'ban', 'get_members', 'set_config'
    status      text,                                 -- lifecycle status at log time: 'processing' | 'completed' | 'error'
    duration_ms integer,                              -- how long the action took, when known
    meta        jsonb                                 -- any extra context (claimedBy, etc.)
);

-- Newest-first reads (the dashboard's get_logs action) and per-request
-- lookups are the two access patterns that matter.
CREATE INDEX IF NOT EXISTS idx_dashboard_logs_created_at
    ON public.dashboard_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dashboard_logs_request_id
    ON public.dashboard_logs (request_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_logs_level
    ON public.dashboard_logs (level);

-- Lock the table down: only the server-side relay (service role, which
-- bypasses RLS) writes and reads it. No anon/public access policies are
-- created on purpose. Log rows are written by dashboard.js itself; the
-- dashboard UI reads them through the relay's `get_logs` action, never
-- directly from the browser.
ALTER TABLE public.dashboard_logs ENABLE ROW LEVEL SECURITY;


-- ───────────────────────────────────────────────────────────────────────
-- 2) FIX: allow 'processing' in dashboard_requests.status
--    (this is what makes the relay's atomic claim actually work)
-- ───────────────────────────────────────────────────────────────────────
-- Drop whichever CHECK constraint currently guards the status column
-- (its generated name differs per project, so resolve it dynamically),
-- then re-add it WITH 'processing' included. Re-running is safe: the DO
-- block simply drops and re-creates the same constraint.
DO $$
DECLARE
    c text;
BEGIN
    SELECT conname INTO c
    FROM pg_constraint
    WHERE conrelid = 'public.dashboard_requests'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%status%'
    LIMIT 1;

    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.dashboard_requests DROP CONSTRAINT %I', c);
    END IF;
END $$;

ALTER TABLE public.dashboard_requests
    ADD CONSTRAINT dashboard_requests_status_check
    CHECK (status IN ('pending', 'processing', 'completed', 'error'));

-- The relay's 500ms sweep polls by status, and the stuck-row reaper scans
-- for 'processing' rows — both get a cheap index with this.
CREATE INDEX IF NOT EXISTS idx_dashboard_requests_status
    ON public.dashboard_requests (status);


-- Done. Restart the relay (throttle.js / your hosting panel) afterwards
-- and check the terminal for:
--   [Relay] dashboard.js build: relay-leader-v5-db-logs-and-claim-fix-2026-10-19

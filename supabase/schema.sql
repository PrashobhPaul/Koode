-- ===========================================================================
-- TripPulse — complete Supabase backend in ONE file.
--
-- Paste this whole file into the Supabase SQL editor and run it once.
-- That is the entire server-side setup: no Edge Functions, no auth providers,
-- no dashboard toggles, no CLI. Safe to re-run (idempotent).
--
-- Security model (capability tokens, enforced 100% in Postgres):
--   * access_key  = SHA-256(tripId:secret) — what viewers derive from the
--     Trip ID + password the driver shares. Grants READ ONLY, and only while
--     the trip has not expired.
--   * owner_token = random secret generated on the DRIVER's device at trip
--     creation and never shared. Every write RPC verifies it, so only the
--     device that created the trip can write or modify anything.
--   * All tables have RLS enabled with no policies and no direct grants —
--     the ONLY way in is through the functions below.
--   * A trip expires 30 minutes after the driver reaches the destination
--     (the app stamps expires_at at arrival). Expired trips return nothing
--     and are deleted by tp_cleanup().
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

create table if not exists tp_trips (
  access_key  text primary key,
  owner_token text not null,
  meta        jsonb not null default '{}'::jsonb,
  expires_at  timestamptz not null,
  created_at  timestamptz not null default now()
);

create table if not exists tp_state (
  access_key text primary key references tp_trips (access_key) on delete cascade,
  state      jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists tp_events (
  event_id   text primary key,
  access_key text not null references tp_trips (access_key) on delete cascade,
  event      jsonb not null,
  event_time bigint not null,
  created_at timestamptz not null default now()
);
create index if not exists tp_events_by_trip on tp_events (access_key, event_time);

create table if not exists tp_locations (
  sample_id  text primary key,
  access_key text not null references tp_trips (access_key) on delete cascade,
  sample     jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists tp_locations_by_trip on tp_locations (access_key);

create table if not exists tp_viewers (
  access_key  text not null references tp_trips (access_key) on delete cascade,
  viewer_name text not null,
  joined_at   timestamptz not null default now(),
  primary key (access_key, viewer_name)
);

-- Lock everything down: RLS on, no policies, no direct table access.
alter table tp_trips     enable row level security;
alter table tp_state     enable row level security;
alter table tp_events    enable row level security;
alter table tp_locations enable row level security;
alter table tp_viewers   enable row level security;

revoke all on tp_trips, tp_state, tp_events, tp_locations, tp_viewers
  from anon, authenticated;

-- ---------------------------------------------------------------------------
-- Internal helper: does this access_key belong to a live (non-expired) trip?
-- Deletes the trip on the spot if it has expired, so even without any cron
-- the data dies the first time anyone touches it after expiry.
-- ---------------------------------------------------------------------------
create or replace function tp_live_trip(p_access_key text)
returns tp_trips
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  select * into r from tp_trips where access_key = p_access_key;
  if not found then return null; end if;
  if r.expires_at <= now() then
    delete from tp_trips where access_key = p_access_key;
    return null;
  end if;
  return r;
end $$;
revoke execute on function tp_live_trip(text) from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Driver-side write RPCs (all verify owner_token)
-- ---------------------------------------------------------------------------

-- First write claims the trip and records the owner token; later writes must
-- present the same token. Returns false on token mismatch.
create or replace function tp_upsert_meta(
  p_access_key text, p_owner_token text, p_meta jsonb, p_expires_ms bigint
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  insert into tp_trips (access_key, owner_token, meta, expires_at)
  values (p_access_key, p_owner_token, p_meta, to_timestamp(p_expires_ms / 1000.0))
  on conflict (access_key) do update
    set meta = excluded.meta, expires_at = excluded.expires_at
    where tp_trips.owner_token = excluded.owner_token;
  return found;
end $$;

create or replace function tp_set_expiry(
  p_access_key text, p_owner_token text, p_expires_ms bigint
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  update tp_trips set expires_at = to_timestamp(p_expires_ms / 1000.0)
  where access_key = p_access_key and owner_token = p_owner_token;
  return found;
end $$;

create or replace function tp_push_state(
  p_access_key text, p_owner_token text, p_state jsonb
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return false;
  end if;
  insert into tp_state (access_key, state, updated_at)
  values (p_access_key, p_state, now())
  on conflict (access_key) do update set state = excluded.state, updated_at = now();
  return true;
end $$;

-- Write-once event append: 'ACKED' on insert, 'EXISTS' on idempotent retry,
-- 'DENIED' on bad token. Mirrors the old write-once RTDB rule.
create or replace function tp_append_event(
  p_access_key text, p_owner_token text, p_event_id text, p_event jsonb, p_event_time bigint
) returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return 'DENIED';
  end if;
  insert into tp_events (event_id, access_key, event, event_time)
  values (p_event_id, p_access_key, p_event, p_event_time)
  on conflict (event_id) do nothing;
  if found then return 'ACKED'; else return 'EXISTS'; end if;
end $$;

-- Batch location samples: {"sampleId": {...}, ...}; duplicates are ignored.
create or replace function tp_append_locations(
  p_access_key text, p_owner_token text, p_samples jsonb
) returns boolean
language plpgsql security definer set search_path = public as $$
declare k text; v jsonb;
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return false;
  end if;
  for k, v in select * from jsonb_each(p_samples) loop
    insert into tp_locations (sample_id, access_key, sample)
    values (k, p_access_key, v)
    on conflict (sample_id) do nothing;
  end loop;
  return true;
end $$;

-- ---------------------------------------------------------------------------
-- Viewer-side read RPCs (capability = access_key, gated on expiry)
-- ---------------------------------------------------------------------------

create or replace function tp_get_meta(p_access_key text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return null; end if;
  return r.meta || jsonb_build_object(
    'expiresAt', (extract(epoch from r.expires_at) * 1000)::bigint);
end $$;

create or replace function tp_get_state(p_access_key text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; s jsonb;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return null; end if;
  select state into s from tp_state where access_key = p_access_key;
  return s;
end $$;

-- Events newer than p_since (epoch ms), oldest first, capped at 500.
create or replace function tp_get_events(p_access_key text, p_since bigint)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; out jsonb;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return null; end if;
  select coalesce(jsonb_agg(e.ev order by e.event_time), '[]'::jsonb) into out
  from (
    select event || jsonb_build_object('eventId', event_id) as ev, event_time
    from tp_events
    where access_key = p_access_key and event_time > p_since
    order by event_time
    limit 500
  ) e;
  return out;
end $$;

-- A viewer announces themself by name so the driver can see who is watching.
create or replace function tp_register_viewer(p_access_key text, p_viewer text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return false; end if;
  insert into tp_viewers (access_key, viewer_name)
  values (p_access_key, left(trim(p_viewer), 40))
  on conflict (access_key, viewer_name) do update set joined_at = now();
  return true;
end $$;

create or replace function tp_get_viewers(p_access_key text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; out jsonb;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return '[]'::jsonb; end if;
  select coalesce(jsonb_agg(viewer_name order by joined_at), '[]'::jsonb) into out
  from tp_viewers where access_key = p_access_key;
  return out;
end $$;

-- ---------------------------------------------------------------------------
-- Utility RPCs
-- ---------------------------------------------------------------------------

-- Server clock (epoch ms) so viewer freshness math survives device clock skew.
create or replace function tp_now() returns bigint
language sql stable security definer set search_path = public as
$$ select (extract(epoch from now()) * 1000)::bigint $$;

-- Destroys every expired trip (cascades to state/events/locations/viewers).
-- Called by pg_cron (below) and by the repo's scheduled GitHub workflow,
-- which doubles as a free-tier keep-alive ping. Only touches expired rows,
-- so it is safe to expose to anon.
create or replace function tp_cleanup() returns integer
language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  delete from tp_trips where expires_at <= now();
  get diagnostics n = row_count;
  return n;
end $$;

-- ---------------------------------------------------------------------------
-- Grants: RPCs are the only surface reachable with the anon key.
-- ---------------------------------------------------------------------------
grant execute on function
  tp_upsert_meta(text, text, jsonb, bigint),
  tp_set_expiry(text, text, bigint),
  tp_push_state(text, text, jsonb),
  tp_append_event(text, text, text, jsonb, bigint),
  tp_append_locations(text, text, jsonb),
  tp_get_meta(text),
  tp_get_state(text),
  tp_get_events(text, bigint),
  tp_register_viewer(text, text),
  tp_get_viewers(text),
  tp_now(),
  tp_cleanup()
to anon;

-- ---------------------------------------------------------------------------
-- In-database scheduled destruction (best effort — if pg_cron is unavailable
-- on this project, the GitHub workflow performs the same cleanup instead, and
-- expired trips are in any case unreadable immediately and deleted on first
-- touch by tp_live_trip).
-- ---------------------------------------------------------------------------
do $$
begin
  create extension if not exists pg_cron;
  begin
    perform cron.unschedule('tp-cleanup');
  exception when others then null;
  end;
  perform cron.schedule('tp-cleanup', '*/10 * * * *', 'select public.tp_cleanup()');
exception when others then
  raise notice 'pg_cron not available; relying on GitHub-workflow + on-read cleanup.';
end $$;

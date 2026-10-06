-- ===========================================================================
-- TripPulse — complete Supabase backend in ONE file.
--
-- Paste this whole file into the Supabase SQL editor and run it once.
-- That is the whole database: no auth providers, no dashboard toggles, no
-- CLI. Safe to re-run (idempotent). Server push (v3, at the end) also needs
-- the tp-push Edge Function (supabase/functions/tp-push) and two Vault
-- entries described there; without them everything else works unchanged.
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
--   * A journey expires ONE HOUR after its traveller ends it (the app
--     stamps expires_at at completion, never at mere arrival — only the
--     traveller closes a journey). Expired journeys return nothing and are
--     deleted by tp_cleanup().
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

-- The owner reads the journey's own samples back, oldest first, 2000 at a
-- time from p_since (exclusive). The phone is the record's author but older
-- builds trimmed their copy; the cloud holds the whole path.
create or replace function tp_get_locations(
  p_access_key text, p_owner_token text, p_since bigint
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare out jsonb;
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return null;
  end if;
  select coalesce(jsonb_agg(l.sample order by l.t), '[]'::jsonb) into out
  from (
    select sample, (sample->>'t')::bigint as t from tp_locations
    where access_key = p_access_key and (sample->>'t')::bigint > p_since
    order by (sample->>'t')::bigint
    limit 2000
  ) l;
  return out;
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
  tp_get_locations(text, text, bigint),
  tp_get_meta(text),
  tp_get_state(text),
  tp_get_events(text, bigint),
  tp_register_viewer(text, text),
  tp_get_viewers(text),
  tp_now(),
  tp_cleanup()
to anon;

-- ===========================================================================
-- v2 additions — trip-id-only viewing with OWNER APPROVAL BY NAME.
--
-- The trip id alone can now be shared. A viewer's phone holds a permanent
-- random device token; they request access with the trip id + their name,
-- and NOTHING is readable until the owner approves that name. The
-- id+password path above still works for instant access. Everything below is
-- additive and idempotent — re-running this whole file is always safe.
-- ===========================================================================

-- The public trip identifier, extracted from meta at write time.
alter table tp_trips add column if not exists trip_id text;
update tp_trips set trip_id = meta->>'tripId' where trip_id is null;
create unique index if not exists tp_trips_trip_id on tp_trips (trip_id);

-- Viewer registry grows device tokens + approval status. Rows created by the
-- legacy password path count as approved.
alter table tp_viewers add column if not exists viewer_token text;
alter table tp_viewers add column if not exists status text not null default 'APPROVED';
create unique index if not exists tp_viewers_token on tp_viewers (access_key, viewer_token)
  where viewer_token is not null;

-- Keep trip_id in sync on every meta write.
create or replace function tp_upsert_meta(
  p_access_key text, p_owner_token text, p_meta jsonb, p_expires_ms bigint
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  insert into tp_trips (access_key, owner_token, meta, expires_at, trip_id)
  values (p_access_key, p_owner_token, p_meta, to_timestamp(p_expires_ms / 1000.0), p_meta->>'tripId')
  on conflict (access_key) do update
    set meta = excluded.meta, expires_at = excluded.expires_at, trip_id = excluded.trip_id
    where tp_trips.owner_token = excluded.owner_token;
  return found;
end $$;

create or replace function tp_live_trip_by_id(p_trip_id text)
returns tp_trips
language plpgsql security definer set search_path = public as $$
declare k text;
begin
  select access_key into k from tp_trips where trip_id = p_trip_id;
  if not found then return null; end if;
  return tp_live_trip(k);
end $$;
revoke execute on function tp_live_trip_by_id(text) from public, anon, authenticated;

-- A viewer asks to follow using only the trip id + their name.
-- Returns the resulting status: PENDING (or APPROVED/DENIED if re-requested).
create or replace function tp_request_join(p_trip_id text, p_viewer_token text, p_viewer_name text)
returns text
language plpgsql security definer set search_path = public as $$
declare r tp_trips; s text;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return 'NOT_FOUND'; end if;
  select status into s from tp_viewers
    where access_key = r.access_key and viewer_token = p_viewer_token;
  if found then return s; end if;
  -- two people may share a name; suffix the second with a token fragment so
  -- the (access_key, viewer_name) primary key never swallows a request
  insert into tp_viewers (access_key, viewer_name, viewer_token, status)
  values (
    r.access_key,
    case when exists (select 1 from tp_viewers where access_key = r.access_key
                      and viewer_name = left(trim(p_viewer_name), 40))
         then left(trim(p_viewer_name), 34) || ' #' || left(p_viewer_token, 4)
         else left(trim(p_viewer_name), 40) end,
    p_viewer_token, 'PENDING'
  )
  on conflict do nothing;
  return 'PENDING';
end $$;

-- The viewer polls their own status while waiting for the owner.
create or replace function tp_join_status(p_trip_id text, p_viewer_token text)
returns text
language plpgsql security definer set search_path = public as $$
declare r tp_trips; s text;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return 'NOT_FOUND'; end if;
  select status into s from tp_viewers
    where access_key = r.access_key and viewer_token = p_viewer_token;
  if not found then return 'NONE'; end if;
  return s;
end $$;

-- Owner: list join requests / current viewers (name + token + status).
create or replace function tp_get_join_requests(p_access_key text, p_owner_token text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare out jsonb;
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return '[]'::jsonb;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object(
           'name', viewer_name, 'token', viewer_token, 'status', status)
           order by joined_at), '[]'::jsonb) into out
  from tp_viewers
  where access_key = p_access_key and viewer_token is not null;
  return out;
end $$;

-- Owner approves or denies a request by the viewer's device token.
create or replace function tp_set_viewer_status(
  p_access_key text, p_owner_token text, p_viewer_token text, p_status text
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_status not in ('APPROVED', 'DENIED') then return false; end if;
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return false;
  end if;
  update tp_viewers set status = p_status
  where access_key = p_access_key and viewer_token = p_viewer_token;
  return found;
end $$;

-- Approved-token reads: same data as the access-key reads, but gated on the
-- owner having approved this device.
create or replace function tp_get_meta_t(p_trip_id text, p_viewer_token text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return null; end if;
  if not exists (select 1 from tp_viewers where access_key = r.access_key
                 and viewer_token = p_viewer_token and status = 'APPROVED') then
    return null;
  end if;
  return r.meta || jsonb_build_object(
    'expiresAt', (extract(epoch from r.expires_at) * 1000)::bigint);
end $$;

create or replace function tp_get_state_t(p_trip_id text, p_viewer_token text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; s jsonb;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return null; end if;
  if not exists (select 1 from tp_viewers where access_key = r.access_key
                 and viewer_token = p_viewer_token and status = 'APPROVED') then
    return null;
  end if;
  select state into s from tp_state where access_key = r.access_key;
  return s;
end $$;

create or replace function tp_get_events_t(p_trip_id text, p_viewer_token text, p_since bigint)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; out jsonb;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return null; end if;
  if not exists (select 1 from tp_viewers where access_key = r.access_key
                 and viewer_token = p_viewer_token and status = 'APPROVED') then
    return null;
  end if;
  select coalesce(jsonb_agg(e.ev order by e.event_time), '[]'::jsonb) into out
  from (
    select event || jsonb_build_object('eventId', event_id) as ev, event_time
    from tp_events
    where access_key = r.access_key and event_time > p_since
    order by event_time
    limit 500
  ) e;
  return out;
end $$;

grant execute on function
  tp_request_join(text, text, text),
  tp_join_status(text, text),
  tp_get_join_requests(text, text),
  tp_set_viewer_status(text, text, text, text),
  tp_get_meta_t(text, text),
  tp_get_state_t(text, text),
  tp_get_events_t(text, text, bigint)
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

-- ===========================================================================
-- v3 additions — SERVER PUSH to the people following a journey.
--
-- Followers' phones register an FCM token against a journey they follow.
-- Every meaningful event the traveller's device writes is fanned out by the
-- tp-push Edge Function as a high-priority FCM data message, which wakes the
-- follower's Koode even when it is closed. Recipients are exactly the people
-- the traveller let in: approved devices (trip-id path) or holders of the
-- passcode (id+password path). A device the traveller denies stops receiving
-- at once, and trip-id followers are never sent the passcode-derived key.
--
-- Delivery is at-least-once: each event is claimed, sent and acknowledged;
-- anything unacknowledged is re-sent by a one-minute sweeper (up to 6
-- attempts, within 2 hours), and FCM holds messages for a day for phones that
-- are offline. The app de-duplicates, so a re-send never shows twice.
--
-- Push can never slow or fail the traveller's own write: the trigger only
-- enqueues an async HTTP call (pg_net) and swallows its own errors.
--
-- One-time project setup (Vault), done once per Supabase project:
--   select vault.create_secret('https://<ref>.supabase.co/functions/v1/tp-push', 'tp_push_url');
-- The shared secret is created below. FCM credentials: the Edge Function
-- secret FCM_SERVICE_ACCOUNT (Firebase service-account JSON), or the same
-- JSON in Vault as 'fcm_service_account'. Until either exists nothing is
-- sent and nothing is lost: followers keep polling as before.
-- ===========================================================================

create extension if not exists pg_net with schema extensions;

create table if not exists tp_push_tokens (
  access_key   text not null references tp_trips (access_key) on delete cascade,
  fcm_token    text not null,
  -- set when registered through the trip-id path: the approval that gates it
  viewer_token text,
  created_at   timestamptz not null default now(),
  primary key (access_key, fcm_token)
);
create index if not exists tp_push_tokens_fcm on tp_push_tokens (fcm_token);

create table if not exists tp_push_log (
  event_id        text primary key references tp_events (event_id) on delete cascade,
  attempts        int not null default 0,
  last_attempt_at timestamptz,
  done            boolean not null default false
);

alter table tp_push_tokens enable row level security;
alter table tp_push_log enable row level security;
revoke all on tp_push_tokens, tp_push_log from anon, authenticated;

-- The shared secret between Postgres and tp-push (generated once, in Vault).
do $$
begin
  if not exists (select 1 from vault.secrets where name = 'tp_push_secret') then
    perform vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'tp_push_secret');
  end if;
end $$;

create or replace function tp_push_vault(p_name text) returns text
language sql stable security definer set search_path = public as $$
  select decrypted_secret from vault.decrypted_secrets where name = p_name limit 1
$$;
revoke execute on function tp_push_vault(text) from public, anon, authenticated;

-- The events worth waking a follower for. Mirrors the app's
-- FollowerAlerts.NOTIFY_TYPES; the app still makes the final call (break
-- de-duplication, private types), so this list only needs to be a superset.
create or replace function tp_push_worthy(p_type text) returns boolean
language sql immutable set search_path = public as $$
  select p_type = any (array[
    'TRIP_STARTED','TRIP_PAUSED','TRIP_RESUMED','TRIP_COMPLETED','ARRIVAL_DETECTED',
    'TOLL_CROSSED','BREAK_CHECKPOINT',
    'WATER_REPORTED','FOOD_REPORTED','TEA_COFFEE_REPORTED','SNACK_REPORTED',
    'TOILET_REPORTED','REST_REPORTED','FUEL_STOP','CHARGE_STOP',
    'LEG_STARTED','BOARDED','TRANSIT_HALTED','TRANSIT_RESUMED','DEBOARDED',
    'OVERNIGHT_CONFIRMED','HALT_CONFIRMED','HALT_CANCELLED','HALT_RESUMED','MORNING_RESUME',
    'QUICK_NOTE','PASSENGER_JOINED','PASSENGER_LEFT','VEHICLE_ISSUE',
    'WELLBEING_ALERT','JOURNEY_UPDATE',
    'DESTINATION_CHANGED','TRAVEL_MODE_CHANGED','PLANNED_HALT_CREATED','PLANNED_HALT_CHANGED',
    'PLANNED_HALT_CANCELLED','ETA_SIGNIFICANTLY_CHANGED','JOURNEY_PLAN_REVISED',
    'SOS_ACTIVATED','SOS_RESOLVED','INCIDENT','POSSIBLE_INCIDENT'
  ])
$$;

-- ---------------------------------------------------------------------------
-- Follower-side registration (anon)
-- ---------------------------------------------------------------------------

-- Passcode path: whoever can read the journey may receive its pushes.
create or replace function tp_register_push(p_access_key text, p_fcm_token text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  if coalesce(length(p_fcm_token), 0) not between 20 and 4096 then return false; end if;
  r := tp_live_trip(p_access_key);
  if r is null then return false; end if;
  if (select count(*) from tp_push_tokens where access_key = r.access_key) >= 100 then
    return exists (select 1 from tp_push_tokens
                   where access_key = r.access_key and fcm_token = p_fcm_token);
  end if;
  insert into tp_push_tokens (access_key, fcm_token)
  values (r.access_key, p_fcm_token)
  on conflict (access_key, fcm_token) do nothing;
  return true;
end $$;

-- Trip-id path: only a device the traveller approved.
create or replace function tp_register_push_t(p_trip_id text, p_viewer_token text, p_fcm_token text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  if coalesce(length(p_fcm_token), 0) not between 20 and 4096 then return false; end if;
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return false; end if;
  if not exists (select 1 from tp_viewers where access_key = r.access_key
                 and viewer_token = p_viewer_token and status = 'APPROVED') then
    return false;
  end if;
  insert into tp_push_tokens (access_key, fcm_token, viewer_token)
  values (r.access_key, p_fcm_token, p_viewer_token)
  on conflict (access_key, fcm_token) do update set viewer_token = excluded.viewer_token;
  return true;
end $$;

-- The device's token rotated or the app was reset: forget it everywhere.
create or replace function tp_unregister_push(p_fcm_token text)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  delete from tp_push_tokens where fcm_token = p_fcm_token;
  return found;
end $$;

grant execute on function
  tp_register_push(text, text),
  tp_register_push_t(text, text, text),
  tp_unregister_push(text)
to anon;

-- ---------------------------------------------------------------------------
-- tp-push side (service role + shared secret)
-- ---------------------------------------------------------------------------

-- Hands one event to the sender, at most once per minute and 6 times in all.
-- Returns null when there is nothing (more) to send.
create or replace function tp_push_claim(p_event_id text, p_secret text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare e tp_events; t tp_trips; recipients jsonb;
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return null; end if;
  select * into e from tp_events where event_id = p_event_id;
  if not found or not tp_push_worthy(e.event->>'type') then return null; end if;
  select * into t from tp_trips where access_key = e.access_key and expires_at > now();
  if not found then return null; end if;

  insert into tp_push_log as l (event_id, attempts, last_attempt_at)
  values (p_event_id, 1, now())
  on conflict (event_id) do update
    set attempts = l.attempts + 1, last_attempt_at = now()
    where not l.done and l.attempts < 6 and l.last_attempt_at < now() - interval '1 minute';
  if not found then return null; end if;

  -- k = passcode-path device (may be told the access key); trip-id devices
  -- only while still approved, and only ever told the trip id.
  select coalesce(jsonb_agg(jsonb_build_object('t', p.fcm_token, 'k', p.viewer_token is null)), '[]'::jsonb)
    into recipients
  from tp_push_tokens p
  left join tp_viewers v on v.access_key = p.access_key and v.viewer_token = p.viewer_token
  where p.access_key = e.access_key
    and (p.viewer_token is null or v.status = 'APPROVED');

  if jsonb_array_length(recipients) = 0 then
    update tp_push_log set done = true where event_id = p_event_id;
    return null;
  end if;

  return jsonb_build_object(
    'eventId', e.event_id, 'accessKey', e.access_key, 'tripId', t.trip_id,
    'eventTime', e.event_time, 'event', e.event, 'recipients', recipients);
end $$;

-- The sender reports back: prune dead tokens; acknowledge when every send
-- succeeded (otherwise the sweeper retries).
create or replace function tp_push_done(p_event_id text, p_secret text, p_dead text[], p_complete boolean)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return false; end if;
  if coalesce(array_length(p_dead, 1), 0) > 0 then
    delete from tp_push_tokens where fcm_token = any (p_dead);
  end if;
  if p_complete then
    update tp_push_log set done = true where event_id = p_event_id;
  end if;
  return true;
end $$;

-- Firebase credentials kept in Vault instead of the function's env.
create or replace function tp_push_fcm_account(p_secret text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare raw text;
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return null; end if;
  raw := tp_push_vault('fcm_service_account');
  if raw is null then return null; end if;
  return raw::jsonb;
end $$;

revoke execute on function
  tp_push_claim(text, text),
  tp_push_done(text, text, text[], boolean),
  tp_push_fcm_account(text)
from public, anon, authenticated;
grant execute on function
  tp_push_claim(text, text),
  tp_push_done(text, text, text[], boolean),
  tp_push_fcm_account(text)
to service_role;

-- ---------------------------------------------------------------------------
-- Dispatch: on every new event, and a one-minute sweeper for retries
-- ---------------------------------------------------------------------------

create or replace function tp_push_dispatch(p_event_id text) returns void
language plpgsql security definer set search_path = public as $$
declare url text; secret text;
begin
  url := tp_push_vault('tp_push_url');
  secret := tp_push_vault('tp_push_secret');
  if url is null or secret is null then return; end if;
  perform net.http_post(
    url := url,
    body := jsonb_build_object('eventId', p_event_id),
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-tp-push-secret', secret),
    timeout_milliseconds := 10000
  );
end $$;
revoke execute on function tp_push_dispatch(text) from public, anon, authenticated;

create or replace function tp_events_push_trigger() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tp_push_worthy(new.event->>'type')
     and exists (select 1 from tp_push_tokens where access_key = new.access_key) then
    perform tp_push_dispatch(new.event_id);
  end if;
  return null;
exception when others then
  -- never let push get in the way of the traveller's write
  raise warning 'tp push dispatch failed: %', sqlerrm;
  return null;
end $$;

drop trigger if exists tp_events_push on tp_events;
create trigger tp_events_push after insert on tp_events
  for each row execute function tp_events_push_trigger();
revoke execute on function tp_events_push_trigger() from public, anon, authenticated;

-- Re-sends anything unacknowledged (the trigger's call failed, FCM hiccuped,
-- or the function was down). Bounded: last 2 hours, 50 events per run.
create or replace function tp_push_sweep() returns integer
language plpgsql security definer set search_path = public as $$
declare n integer := 0; ev record;
begin
  for ev in
    select e.event_id
    from tp_events e
    left join tp_push_log l on l.event_id = e.event_id
    where e.created_at > now() - interval '2 hours'
      and e.created_at < now() - interval '1 minute'
      and tp_push_worthy(e.event->>'type')
      and exists (select 1 from tp_push_tokens p where p.access_key = e.access_key)
      and (l.event_id is null
           or (not l.done and l.attempts < 6 and l.last_attempt_at < now() - interval '1 minute'))
    order by e.created_at
    limit 50
  loop
    perform tp_push_dispatch(ev.event_id);
    n := n + 1;
  end loop;
  return n;
end $$;
revoke execute on function tp_push_sweep() from public, anon, authenticated;

do $$
begin
  begin
    perform cron.unschedule('tp-push-sweep');
  exception when others then null;
  end;
  perform cron.schedule('tp-push-sweep', '* * * * *', 'select public.tp_push_sweep()');
exception when others then
  raise notice 'pg_cron not available; push retries disabled (first sends still happen).';
end $$;

-- ===========================================================================
-- v4 additions — APPROVED JOURNEY ANALYTICS, VERIFIED REPORTS, and
-- PER-RECIPIENT PUSH DELIVERY.
--
-- Journey followers are tp_viewers (v2); their devices are tp_push_tokens
-- (v3). This section adds:
--   * tp_push_deliveries — one row per journey / event / recipient / channel,
--     the canonical identity of a single notification. A re-sent event only
--     goes to recipients that have not already received it.
--   * tp_journey_analytics — the traveller-approved, NON-FINANCIAL journey
--     analytics. Financial keys are rejected; expenses never leave the phone.
--   * tp_journey_reports — the approved journey report (the timeline PDF,
--     never the expense PDF) with its private Storage reference.
--   * The approval gate: completion and report notifications are sent only
--     after the traveller approved the journey analytics, and nothing but
--     SOS/incidents is sent while a closed journey waits for review.
--
-- No SMS, no WhatsApp Business API: FCM is the only server channel.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Which events may reach followers, and when
-- ---------------------------------------------------------------------------

create or replace function tp_push_worthy(p_type text) returns boolean
language sql immutable set search_path = public as $$
  select p_type = any (array[
    'TRIP_STARTED','TRIP_PAUSED','TRIP_RESUMED','TRIP_COMPLETED','ARRIVAL_DETECTED',
    'TOLL_CROSSED','BREAK_CHECKPOINT',
    'WATER_REPORTED','FOOD_REPORTED','TEA_COFFEE_REPORTED','SNACK_REPORTED',
    'TOILET_REPORTED','REST_REPORTED','FUEL_STOP','CHARGE_STOP',
    'LEG_STARTED','BOARDED','TRANSIT_HALTED','TRANSIT_RESUMED','DEBOARDED',
    'OVERNIGHT_CONFIRMED','HALT_CONFIRMED','HALT_CANCELLED','HALT_RESUMED','MORNING_RESUME',
    'QUICK_NOTE','PASSENGER_JOINED','PASSENGER_LEFT','VEHICLE_ISSUE',
    'WELLBEING_ALERT','JOURNEY_UPDATE',
    'DESTINATION_CHANGED','TRAVEL_MODE_CHANGED','PLANNED_HALT_CREATED','PLANNED_HALT_CHANGED',
    'PLANNED_HALT_CANCELLED','ETA_SIGNIFICANTLY_CHANGED','JOURNEY_PLAN_REVISED',
    'SOS_ACTIVATED','SOS_RESOLVED','INCIDENT','POSSIBLE_INCIDENT',
    'JOURNEY_REPORT_AVAILABLE'
  ])
$$;

-- Safety events are never held back, whatever state the journey is in.
create or replace function tp_push_critical(p_type text) returns boolean
language sql immutable set search_path = public as $$
  select p_type = any (array['SOS_ACTIVATED','SOS_RESOLVED','INCIDENT','POSSIBLE_INCIDENT'])
$$;

-- Events that announce the end of the journey: only after approval.
create or replace function tp_push_needs_approval(p_type text) returns boolean
language sql immutable set search_path = public as $$
  select p_type = any (array['TRIP_COMPLETED','JOURNEY_REPORT_AVAILABLE'])
$$;

-- ---------------------------------------------------------------------------
-- Approved analytics (non-financial) and verified reports
-- ---------------------------------------------------------------------------

create table if not exists tp_journey_analytics (
  access_key     text primary key references tp_trips (access_key) on delete cascade,
  approved_at    timestamptz not null,
  approved_by    text,
  safe_confirmed boolean not null default false,
  auto_closed    boolean not null default false,
  analytics      jsonb not null,
  published_at   timestamptz not null default now()
);

create table if not exists tp_journey_reports (
  access_key   text not null references tp_trips (access_key) on delete cascade,
  version      int not null,
  -- the follower-safe journey report; the expense report is never uploaded
  kind         text not null default 'JOURNEY_TIMELINE' check (kind = 'JOURNEY_TIMELINE'),
  bucket       text not null default 'journey-reports',
  object_path  text not null unique,
  sha256       text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  bytes        int not null check (bytes between 1 and 10485760),
  status       text not null default 'PENDING' check (status in ('PENDING', 'AVAILABLE')),
  created_at   timestamptz not null default now(),
  available_at timestamptz,
  primary key (access_key, version)
);

-- Storage objects can only be removed through the Storage API, so removed
-- report rows leave their object path here for tp-report to delete.
create table if not exists tp_report_trash (
  object_path text primary key,
  bucket      text not null,
  queued_at   timestamptz not null default now()
);

create table if not exists tp_push_deliveries (
  event_id        text not null references tp_events (event_id) on delete cascade,
  access_key      text not null,
  recipient       text not null,              -- the device's FCM token
  channel         text not null default 'FCM' check (channel = 'FCM'),
  status          text not null default 'PENDING'
                    check (status in ('PENDING', 'SENT', 'FAILED', 'DEAD')),
  attempts        int not null default 0,
  last_attempt_at timestamptz,
  sent_at         timestamptz,
  primary key (event_id, recipient, channel)
);
create index if not exists tp_push_deliveries_journey on tp_push_deliveries (access_key);

alter table tp_journey_analytics enable row level security;
alter table tp_journey_reports enable row level security;
alter table tp_report_trash enable row level security;
alter table tp_push_deliveries enable row level security;
revoke all on tp_journey_analytics, tp_journey_reports, tp_report_trash, tp_push_deliveries
  from anon, authenticated;

-- Private bucket: reachable only through short-lived signed URLs from tp-report.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('journey-reports', 'journey-reports', false, 10485760, array['application/pdf'])
on conflict (id) do update
  set public = false, file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- May this event be pushed to followers now? Held events are not dropped:
-- approving the journey releases them (tp_publish_analytics).
create or replace function tp_push_eligible(p_access_key text, p_type text) returns boolean
language sql stable security definer set search_path = public as $$
  select tp_push_worthy(p_type) and (
    tp_push_critical(p_type)
    or exists (select 1 from tp_journey_analytics a where a.access_key = p_access_key)
    or (not tp_push_needs_approval(p_type)
        and coalesce((select (s.state->>'wrappingUp')::boolean from tp_state s
                      where s.access_key = p_access_key), false) = false)
  )
$$;

create or replace function tp_report_trash_trigger() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into tp_report_trash (object_path, bucket) values (old.object_path, old.bucket)
  on conflict do nothing;
  return old;
end $$;
revoke execute on function tp_report_trash_trigger() from public, anon, authenticated;
drop trigger if exists tp_journey_reports_trash on tp_journey_reports;
create trigger tp_journey_reports_trash before delete on tp_journey_reports
  for each row execute function tp_report_trash_trigger();

-- Financial analytics stay on the traveller's phone. Any money-shaped key or
-- a rupee amount anywhere in the document is refused.
create or replace function tp_is_financial(p_doc jsonb) returns boolean
language sql immutable set search_path = public as $$
  select p_doc::text ~* '"[^"]*(amount|cost|price|fare|expens|spend|money|rupee|currency|balance|payment|paid|inr)[^"]*"\s*:'
      or p_doc::text like '%₹%'
$$;

-- Releases every event of a journey that was waiting on approval.
create or replace function tp_push_release(p_access_key text) returns integer
language plpgsql security definer set search_path = public as $$
declare n integer := 0; ev record;
begin
  for ev in
    select e.event_id from tp_events e
    left join tp_push_log l on l.event_id = e.event_id
    where e.access_key = p_access_key
      and e.created_at > now() - interval '24 hours'
      and l.event_id is null
      and tp_push_eligible(e.access_key, e.event->>'type')
    order by e.event_time
    limit 50
  loop
    perform tp_push_dispatch(ev.event_id);
    n := n + 1;
  end loop;
  return n;
exception when others then
  raise warning 'tp push release failed: %', sqlerrm;
  return n;
end $$;
revoke execute on function tp_push_release(text) from public, anon, authenticated;

-- Traveller (owner token): publish the approved, non-financial analytics.
-- Returns OK, DENIED (not the owner) or FINANCIAL (refused).
create or replace function tp_publish_analytics(
  p_access_key text, p_owner_token text, p_analytics jsonb, p_approved_at_ms bigint,
  p_approved_by text, p_safe_confirmed boolean, p_auto_closed boolean
) returns text
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return 'DENIED';
  end if;
  if p_analytics is null or jsonb_typeof(p_analytics) <> 'object' then return 'INVALID'; end if;
  if tp_is_financial(p_analytics) then return 'FINANCIAL'; end if;
  insert into tp_journey_analytics (access_key, approved_at, approved_by, safe_confirmed, auto_closed, analytics)
  values (p_access_key, to_timestamp(p_approved_at_ms / 1000.0), left(p_approved_by, 60),
          coalesce(p_safe_confirmed, false), coalesce(p_auto_closed, false), p_analytics)
  on conflict (access_key) do update
    set approved_at = excluded.approved_at, approved_by = excluded.approved_by,
        safe_confirmed = excluded.safe_confirmed, auto_closed = excluded.auto_closed,
        analytics = excluded.analytics, published_at = now();
  perform tp_push_release(p_access_key);
  return 'OK';
end $$;

-- Followers read the approved analytics through the same capabilities as
-- the journey itself.
create or replace function tp_get_analytics(p_access_key text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; out jsonb;
begin
  r := tp_live_trip(p_access_key);
  if r is null then return null; end if;
  select jsonb_build_object('approvedAt', (extract(epoch from approved_at) * 1000)::bigint,
           'approvedBy', approved_by, 'safeConfirmed', safe_confirmed,
           'autoClosed', auto_closed, 'analytics', analytics)
    into out from tp_journey_analytics where access_key = r.access_key;
  return out;
end $$;

create or replace function tp_get_analytics_t(p_trip_id text, p_viewer_token text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips;
begin
  r := tp_live_trip_by_id(p_trip_id);
  if r is null then return null; end if;
  if not exists (select 1 from tp_viewers where access_key = r.access_key
                 and viewer_token = p_viewer_token and status = 'APPROVED') then
    return null;
  end if;
  return tp_get_analytics(r.access_key);
end $$;

-- tp-report (service role, owner token forwarded): reserve the next report
-- version and its object path. Only after the analytics were approved.
create or replace function tp_report_begin(
  p_access_key text, p_owner_token text, p_sha256 text, p_bytes int
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v int; path text;
begin
  if not exists (select 1 from tp_trips where access_key = p_access_key
                 and owner_token = p_owner_token and expires_at > now()) then
    return jsonb_build_object('error', 'DENIED');
  end if;
  if not exists (select 1 from tp_journey_analytics where access_key = p_access_key) then
    return jsonb_build_object('error', 'NOT_APPROVED');
  end if;
  if p_sha256 !~ '^[0-9a-f]{64}$' or p_bytes not between 1 and 10485760 then
    return jsonb_build_object('error', 'INVALID');
  end if;
  select coalesce(max(version), 0) + 1 into v from tp_journey_reports where access_key = p_access_key;
  if v > 5 then return jsonb_build_object('error', 'TOO_MANY'); end if;
  path := encode(extensions.digest(p_access_key, 'sha256'), 'hex') || '/'
          || encode(extensions.gen_random_bytes(16), 'hex') || '.pdf';
  insert into tp_journey_reports (access_key, version, object_path, sha256, bytes)
  values (p_access_key, v, path, p_sha256, p_bytes);
  return jsonb_build_object('version', v, 'bucket', 'journey-reports', 'path', path);
end $$;

-- Traveller, after uploading: mark the report available and announce it.
-- Returns OK, DENIED, NOT_FOUND or NOT_UPLOADED.
create or replace function tp_report_publish(p_access_key text, p_owner_token text, p_version int)
returns text
language plpgsql security definer set search_path = public as $$
declare rep tp_journey_reports; ev_id text; now_ms bigint;
begin
  if not exists (select 1 from tp_trips
                 where access_key = p_access_key and owner_token = p_owner_token) then
    return 'DENIED';
  end if;
  select * into rep from tp_journey_reports where access_key = p_access_key and version = p_version;
  if not found then return 'NOT_FOUND'; end if;
  if rep.status = 'AVAILABLE' then return 'OK'; end if;
  if not exists (select 1 from storage.objects
                 where bucket_id = rep.bucket and name = rep.object_path) then
    return 'NOT_UPLOADED';
  end if;
  update tp_journey_reports set status = 'AVAILABLE', available_at = now()
  where access_key = p_access_key and version = p_version;

  now_ms := (extract(epoch from now()) * 1000)::bigint;
  ev_id := 'report-' || md5(p_access_key || ':' || p_version);
  insert into tp_events (event_id, access_key, event, event_time)
  values (ev_id, p_access_key, jsonb_build_object(
      'type', 'JOURNEY_REPORT_AVAILABLE', 'eventTime', now_ms, 'receivedAt', now_ms,
      'source', 'SERVER', 'schemaVersion', 1,
      'payload', jsonb_build_object('reportVersion', p_version)), now_ms)
  on conflict (event_id) do nothing;
  return 'OK';
end $$;

-- tp-report (service role): where the latest available report is, for
-- someone allowed to read the journey.
create or replace function tp_report_locate(p_access_key text, p_trip_id text, p_viewer_token text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare r tp_trips; rep tp_journey_reports;
begin
  if p_access_key is not null and p_access_key <> '' then
    r := tp_live_trip(p_access_key);
  else
    r := tp_live_trip_by_id(p_trip_id);
    if r is not null and not exists (select 1 from tp_viewers where access_key = r.access_key
                   and viewer_token = p_viewer_token and status = 'APPROVED') then
      r := null;
    end if;
  end if;
  if r is null then return null; end if;
  select * into rep from tp_journey_reports
  where access_key = r.access_key and status = 'AVAILABLE'
  order by version desc limit 1;
  if not found then return null; end if;
  return jsonb_build_object('version', rep.version, 'bucket', rep.bucket, 'path', rep.object_path,
                            'sha256', rep.sha256, 'bytes', rep.bytes);
end $$;

-- tp-report garbage collection (shared secret).
create or replace function tp_report_trash_take(p_secret text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare out jsonb;
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return null; end if;
  select coalesce(jsonb_agg(jsonb_build_object('bucket', bucket, 'path', object_path)), '[]'::jsonb)
    into out from (select * from tp_report_trash order by queued_at limit 100) t;
  return out;
end $$;

create or replace function tp_report_trash_clear(p_secret text, p_paths text[]) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return false; end if;
  delete from tp_report_trash where object_path = any (p_paths);
  return true;
end $$;

-- Follower: stop push for ONE journey (unfollowed), by access key or trip id.
create or replace function tp_unregister_push_for(p_ref text, p_fcm_token text)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  delete from tp_push_tokens
  where fcm_token = p_fcm_token
    and (access_key = p_ref or access_key in (select access_key from tp_trips where trip_id = p_ref));
  return found;
end $$;

-- ---------------------------------------------------------------------------
-- Push delivery, now per recipient
-- ---------------------------------------------------------------------------

-- Hands one event to the sender: at most once a minute and 6 times in all,
-- and only to recipients that have not already received it. Events not yet
-- eligible (waiting on approval) are left untouched so they can be released.
create or replace function tp_push_claim(p_event_id text, p_secret text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare e tp_events; t tp_trips; recipients jsonb;
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return null; end if;
  select * into e from tp_events where event_id = p_event_id;
  if not found or not tp_push_eligible(e.access_key, e.event->>'type') then return null; end if;
  select * into t from tp_trips where access_key = e.access_key and expires_at > now();
  if not found then return null; end if;

  insert into tp_push_log as l (event_id, attempts, last_attempt_at)
  values (p_event_id, 1, now())
  on conflict (event_id) do update
    set attempts = l.attempts + 1, last_attempt_at = now()
    where not l.done and l.attempts < 6 and l.last_attempt_at < now() - interval '1 minute';
  if not found then return null; end if;

  -- k = passcode-path device (may be told the access key); trip-id devices
  -- only while still approved, and only ever told the trip id.
  with due as (
    select p.fcm_token, p.viewer_token is null as via_key
    from tp_push_tokens p
    left join tp_viewers v on v.access_key = p.access_key and v.viewer_token = p.viewer_token
    where p.access_key = e.access_key
      and (p.viewer_token is null or v.status = 'APPROVED')
      and not exists (select 1 from tp_push_deliveries d
                      where d.event_id = e.event_id and d.recipient = p.fcm_token
                        and d.channel = 'FCM' and d.status in ('SENT', 'DEAD'))
  ), marked as (
    insert into tp_push_deliveries as d (event_id, access_key, recipient, attempts, last_attempt_at)
    select e.event_id, e.access_key, fcm_token, 1, now() from due
    on conflict (event_id, recipient, channel) do update
      set attempts = d.attempts + 1, last_attempt_at = now(), status = 'PENDING'
    returning recipient
  )
  select coalesce(jsonb_agg(jsonb_build_object('t', due.fcm_token, 'k', due.via_key)), '[]'::jsonb)
    into recipients
  from due join marked on marked.recipient = due.fcm_token;

  if jsonb_array_length(recipients) = 0 then
    update tp_push_log set done = true where event_id = p_event_id;
    return null;
  end if;

  return jsonb_build_object(
    'eventId', e.event_id, 'accessKey', e.access_key, 'tripId', t.trip_id,
    'eventTime', e.event_time, 'event', e.event, 'recipients', recipients);
end $$;

-- The sender reports each recipient: SENT, FAILED (retry) or DEAD (token
-- gone, removed). The event is done once nobody is left to retry.
drop function if exists tp_push_done(text, text, text[], boolean);
create or replace function tp_push_done(p_event_id text, p_secret text, p_results jsonb)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if p_secret is distinct from tp_push_vault('tp_push_secret') then return false; end if;
  update tp_push_deliveries d
     set status = r.s, sent_at = case when r.s = 'SENT' then now() else d.sent_at end
    from jsonb_to_recordset(coalesce(p_results, '[]'::jsonb)) as r(t text, s text)
   where d.event_id = p_event_id and d.recipient = r.t and d.channel = 'FCM'
     and r.s in ('SENT', 'FAILED', 'DEAD');
  delete from tp_push_tokens
   where fcm_token in (select r.t from jsonb_to_recordset(coalesce(p_results, '[]'::jsonb)) as r(t text, s text)
                       where r.s = 'DEAD');
  if not exists (select 1 from tp_push_deliveries
                 where event_id = p_event_id and status in ('PENDING', 'FAILED')) then
    update tp_push_log set done = true where event_id = p_event_id;
  end if;
  return true;
end $$;

create or replace function tp_events_push_trigger() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tp_push_eligible(new.access_key, new.event->>'type')
     and exists (select 1 from tp_push_tokens where access_key = new.access_key) then
    perform tp_push_dispatch(new.event_id);
  end if;
  return null;
exception when others then
  raise warning 'tp push dispatch failed: %', sqlerrm;
  return null;
end $$;

create or replace function tp_push_sweep() returns integer
language plpgsql security definer set search_path = public as $$
declare n integer := 0; ev record;
begin
  for ev in
    select e.event_id
    from tp_events e
    left join tp_push_log l on l.event_id = e.event_id
    where e.created_at > now() - interval '2 hours'
      and e.created_at < now() - interval '1 minute'
      and exists (select 1 from tp_push_tokens p where p.access_key = e.access_key)
      and (l.event_id is null
           or (not l.done and l.attempts < 6 and l.last_attempt_at < now() - interval '1 minute'))
      and tp_push_eligible(e.access_key, e.event->>'type')
    order by e.created_at
    limit 50
  loop
    perform tp_push_dispatch(ev.event_id);
    n := n + 1;
  end loop;

  -- report objects whose journey is gone: tp-report deletes them
  if exists (select 1 from tp_report_trash) then
    perform tp_report_gc_dispatch();
  end if;
  return n;
end $$;

create or replace function tp_report_gc_dispatch() returns void
language plpgsql security definer set search_path = public as $$
declare url text; secret text;
begin
  url := replace(tp_push_vault('tp_push_url'), '/tp-push', '/tp-report');
  secret := tp_push_vault('tp_push_secret');
  if url is null or secret is null then return; end if;
  perform net.http_post(
    url := url,
    body := jsonb_build_object('action', 'gc'),
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-tp-push-secret', secret),
    timeout_milliseconds := 10000
  );
end $$;

revoke execute on function
  tp_push_eligible(text, text),
  tp_report_gc_dispatch(),
  tp_events_push_trigger(),
  tp_push_sweep()
from public, anon, authenticated;
revoke execute on function
  tp_push_claim(text, text),
  tp_push_done(text, text, jsonb),
  tp_report_begin(text, text, text, int),
  tp_report_locate(text, text, text),
  tp_report_trash_take(text),
  tp_report_trash_clear(text, text[])
from public, anon, authenticated;
grant execute on function
  tp_push_claim(text, text),
  tp_push_done(text, text, jsonb),
  tp_report_begin(text, text, text, int),
  tp_report_locate(text, text, text),
  tp_report_trash_take(text),
  tp_report_trash_clear(text, text[])
to service_role;
grant execute on function
  tp_publish_analytics(text, text, jsonb, bigint, text, boolean, boolean),
  tp_get_analytics(text),
  tp_get_analytics_t(text, text),
  tp_report_publish(text, text, int),
  tp_unregister_push_for(text, text)
to anon;

-- ===========================================================================
-- v5 additions — TOLL PLAZAS BY LOCATION (no SMS).
--
-- Koode counts toll crossings on car and bike journeys from location: the
-- phone checks its path against known toll booths. The booths come from
-- OpenStreetMap (barrier=toll_booth, © OpenStreetMap contributors, ODbL),
-- fetched here weekly so every phone downloads one small list from Koode
-- instead of querying OpenStreetMap itself.
--
-- The fetch is asynchronous (pg_net): tp_toll_refresh_request() asks
-- Overpass for India in two halves; tp_toll_refresh_collect() loads the
-- answers when they arrive. Both run from pg_cron. A failed or partial fetch
-- never empties the list: booths are only removed once a complete refresh
-- has not seen them for two weeks.
-- ===========================================================================

create table if not exists tp_toll_plazas (
  osm_id  text primary key,             -- 'n<node id>'
  lat     double precision not null,
  lng     double precision not null,
  name    text,
  seen_at timestamptz not null default now()
);

create table if not exists tp_toll_refresh (
  part         text primary key,        -- 'south' | 'north'
  request_id   bigint,
  requested_at timestamptz,
  loaded_at    timestamptz,
  booths       int
);

alter table tp_toll_plazas enable row level security;
alter table tp_toll_refresh enable row level security;
revoke all on tp_toll_plazas, tp_toll_refresh from anon, authenticated;

create or replace function tp_toll_refresh_request() returns void
language plpgsql security definer set search_path = public as $$
declare half record; req bigint;
begin
  for half in
    select * from (values
      ('south', '6.5,68.0,21.0,97.5'),
      ('north', '21.0,68.0,35.7,97.5')
    ) as p(name, bbox)
  loop
    req := net.http_get(
      url := 'https://overpass-api.de/api/interpreter',
      params := jsonb_build_object('data',
        '[out:json][timeout:150];node["barrier"="toll_booth"](' || half.bbox || ');out;'),
      timeout_milliseconds := 170000
    );
    insert into tp_toll_refresh (part, request_id, requested_at)
    values (half.name, req, now())
    on conflict (part) do update set request_id = excluded.request_id, requested_at = excluded.requested_at;
  end loop;
end $$;

-- Loads whatever answers have arrived. Returns the number of booths loaded.
create or replace function tp_toll_refresh_collect() returns integer
language plpgsql security definer set search_path = public as $$
declare r record; resp record; n integer := 0; loaded integer;
begin
  for r in select * from tp_toll_refresh where request_id is not null loop
    select status_code, content into resp from net._http_response where id = r.request_id;
    if not found then
      -- no answer after an hour: give up until the next weekly request
      if r.requested_at < now() - interval '1 hour' then
        update tp_toll_refresh set request_id = null where part = r.part;
      end if;
      continue;
    end if;
    begin
      if resp.status_code = 200 then
        insert into tp_toll_plazas as t (osm_id, lat, lng, name, seen_at)
        select 'n' || (e->>'id'), (e->>'lat')::double precision, (e->>'lon')::double precision,
               nullif(trim(coalesce(e->'tags'->>'name:en', e->'tags'->>'name')), ''), now()
        from jsonb_array_elements(resp.content::jsonb -> 'elements') e
        where e->>'type' = 'node' and e ? 'lat' and e ? 'lon'
        on conflict (osm_id) do update
          set lat = excluded.lat, lng = excluded.lng, name = excluded.name, seen_at = excluded.seen_at;
        get diagnostics loaded = row_count;
        update tp_toll_refresh set request_id = null, loaded_at = now(), booths = loaded where part = r.part;
        n := n + loaded;
      else
        update tp_toll_refresh set request_id = null where part = r.part;
      end if;
    exception when others then
      raise warning 'toll refresh (%) failed: %', r.part, sqlerrm;
      update tp_toll_refresh set request_id = null where part = r.part;
    end;
  end loop;

  -- Forget booths OpenStreetMap no longer has, only after complete refreshes.
  if (select count(*) from tp_toll_refresh where loaded_at > now() - interval '8 days') = 2 then
    delete from tp_toll_plazas where seen_at < now() - interval '15 days';
  end if;
  return n;
end $$;

-- App (anon): the booth list, if newer than what the phone has.
-- {"version": <ms>, "plazas": "id,lat,lng,name\n..."} — plazas omitted when
-- the phone is already up to date.
create or replace function tp_toll_plazas(p_since_ms bigint)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v bigint; body text;
begin
  select (extract(epoch from max(seen_at)) * 1000)::bigint into v from tp_toll_plazas;
  if v is null then return jsonb_build_object('version', 0); end if;
  if p_since_ms is not null and p_since_ms >= v then
    return jsonb_build_object('version', v);
  end if;
  select string_agg(
           osm_id || ',' || round(lat::numeric, 6) || ',' || round(lng::numeric, 6) || ','
             || coalesce(replace(replace(name, E'\n', ' '), E'\r', ' '), ''),
           E'\n' order by osm_id)
    into body from tp_toll_plazas;
  return jsonb_build_object('version', v, 'plazas', body);
end $$;

revoke execute on function tp_toll_refresh_request(), tp_toll_refresh_collect()
  from public, anon, authenticated;
grant execute on function tp_toll_plazas(bigint) to anon;

do $$
begin
  begin perform cron.unschedule('tp-toll-refresh'); exception when others then null; end;
  begin perform cron.unschedule('tp-toll-collect'); exception when others then null; end;
  perform cron.schedule('tp-toll-refresh', '17 3 * * 0', 'select public.tp_toll_refresh_request()');
  perform cron.schedule('tp-toll-collect', '*/5 * * * *', 'select public.tp_toll_refresh_collect()');
exception when others then
  raise notice 'pg_cron not available; refresh toll plazas by calling tp_toll_refresh_request() then tp_toll_refresh_collect().';
end $$;

-- ---------------------------------------------------------------------------
-- v6: a live nudge for followers (Supabase Realtime broadcast)
--
-- Followers used to ask for the state every 20 s. Now each state write also
-- broadcasts a data-free "fix" message on the journey's live channel, and a
-- follower who is listening fetches at once through the same approved read
-- as before. The message carries nothing but a time: where the traveller is
-- is still only ever read through tp_get_state / tp_get_state_t.
--
-- The channel name is random per journey and is handed out only inside the
-- state itself (state.liveChannel), so only someone who may already read the
-- state can listen for its nudges. A nudge that cannot be sent never costs
-- the state write; followers keep polling as the fallback.
-- ---------------------------------------------------------------------------

alter table tp_trips add column if not exists live_channel text;
alter table tp_trips alter column live_channel set default replace(gen_random_uuid()::text, '-', '');
update tp_trips set live_channel = replace(gen_random_uuid()::text, '-', '') where live_channel is null;

create or replace function tp_push_state(
  p_access_key text, p_owner_token text, p_state jsonb
) returns boolean
language plpgsql security definer set search_path = public as $$
declare v_channel text;
begin
  select live_channel into v_channel from tp_trips
  where access_key = p_access_key and owner_token = p_owner_token;
  if not found then
    return false;
  end if;
  if v_channel is null then
    v_channel := replace(gen_random_uuid()::text, '-', '');
    update tp_trips set live_channel = v_channel where access_key = p_access_key;
  end if;
  insert into tp_state (access_key, state, updated_at)
  values (p_access_key, p_state || jsonb_build_object('liveChannel', v_channel), now())
  on conflict (access_key) do update set state = excluded.state, updated_at = now();
  begin
    perform realtime.send(
      jsonb_build_object('at', (extract(epoch from now()) * 1000)::bigint),
      'fix', 'koode:' || v_channel, false);
  exception when others then
    null; -- Realtime unavailable: the write stands, followers poll.
  end;
  return true;
end $$;

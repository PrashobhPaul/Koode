-- Server push, the approval gate, approved analytics and verified reports.
-- Each block raises (and the run fails) if a guarantee does not hold.
\set ON_ERROR_STOP 1

select vault.create_secret('https://example.test/functions/v1/tp-push', 'tp_push_url')
where not exists (select 1 from vault.secrets where name = 'tp_push_url');

create function pg_temp.calls() returns bigint language sql as $$ select count(*) from net.calls $$;
create function pg_temp.secret() returns text language sql as $$ select tp_push_vault('tp_push_secret') $$;
create function pg_temp.backdate(p_event text) returns void language sql as $$
  update tp_push_log set last_attempt_at = now() - interval '2 minutes' where event_id = p_event $$;
create function pg_temp.ev(p_id text, p_type text, p_key text default 'key-1', p_owner text default 'owner-1')
returns text language sql as $$
  select tp_append_event(p_key, p_owner, p_id, jsonb_build_object('type', p_type, 'payload', '{}'::jsonb),
                         (extract(epoch from now()) * 1000)::bigint) $$;

-- A live journey, one passcode follower, one approved and one denied trip-id follower.
set role anon;
select tp_upsert_meta('key-1', 'owner-1', '{"tripId":"TRIP-1"}', ((extract(epoch from now()) + 7200) * 1000)::bigint);
select tp_push_state('key-1', 'owner-1', '{"journey":"DRIVING"}');
select tp_register_push('key-1', 'token-passcode-aaaaaaaaaaaaaaaa');
select tp_request_join('TRIP-1', 'viewer-ok', 'Amma');
select tp_request_join('TRIP-1', 'viewer-no', 'Stranger');
reset role;
select tp_set_viewer_status('key-1', 'owner-1', 'viewer-ok', 'APPROVED');
set role anon;
do $$ begin
  assert tp_register_push_t('TRIP-1', 'viewer-ok', 'token-approved-bbbbbbbbbbbbbbbb'), 'approved follower registers';
  assert not tp_register_push_t('TRIP-1', 'viewer-no', 'token-pending-cccccccccccccccc'), 'pending follower refused';
end $$;
reset role;

-- 1. Meaningful events dispatch; noise does not.
do $$ declare c0 bigint := pg_temp.calls(); begin
  perform pg_temp.ev('ev-toll', 'TOLL_CROSSED');
  assert pg_temp.calls() = c0 + 1, 'toll crossing dispatched';
  perform pg_temp.ev('ev-loc', 'LOCATION_UPDATE');
  assert pg_temp.calls() = c0 + 1, 'location noise never dispatched';
  perform pg_temp.ev('ev-dest', 'DESTINATION_CHANGED');
  assert pg_temp.calls() = c0 + 2, 'plan change dispatched';
end $$;

-- 2. Per-recipient idempotency: journey / event / recipient / channel.
do $$ declare c jsonb; begin
  c := tp_push_claim('ev-toll', pg_temp.secret());
  assert jsonb_array_length(c->'recipients') = 2, 'both followers are due';
  assert (select count(*) from tp_push_deliveries where event_id = 'ev-toll') = 2, 'two delivery rows';
  perform tp_push_done('ev-toll', pg_temp.secret(), jsonb_build_array(
    jsonb_build_object('t', 'token-passcode-aaaaaaaaaaaaaaaa', 's', 'SENT'),
    jsonb_build_object('t', 'token-approved-bbbbbbbbbbbbbbbb', 's', 'FAILED')));
  assert not (select done from tp_push_log where event_id = 'ev-toll'), 'a failure keeps the event open';
  assert tp_push_claim('ev-toll', pg_temp.secret()) is null, 'no retry within a minute';
  perform pg_temp.backdate('ev-toll');
  c := tp_push_claim('ev-toll', pg_temp.secret());
  assert jsonb_array_length(c->'recipients') = 1
     and c->'recipients'->0->>'t' = 'token-approved-bbbbbbbbbbbbbbbb', 'retry goes only to the one that failed';
  perform tp_push_done('ev-toll', pg_temp.secret(), jsonb_build_array(
    jsonb_build_object('t', 'token-approved-bbbbbbbbbbbbbbbb', 's', 'SENT')));
  assert (select done from tp_push_log where event_id = 'ev-toll'), 'done once everyone has it';
  perform pg_temp.backdate('ev-toll');
  assert tp_push_claim('ev-toll', pg_temp.secret()) is null, 'never sent twice';
  assert (select count(*) from tp_push_deliveries where event_id = 'ev-toll' and status = 'SENT') = 2;
end $$;

-- 3. Trip-id followers are never given the passcode-derived key; a wrong
--    secret gets nothing.
do $$ declare c jsonb; begin
  assert tp_push_claim('ev-dest', 'wrong') is null, 'wrong secret refused';
  c := tp_push_claim('ev-dest', pg_temp.secret());
  assert (select bool_and(case when r->>'t' like 'token-approved%' then (r->>'k')::boolean = false
                               else (r->>'k')::boolean end)
          from jsonb_array_elements(c->'recipients') r), 'k is true only for passcode devices';
  perform tp_push_done('ev-dest', pg_temp.secret(), jsonb_build_array(
    jsonb_build_object('t', 'token-passcode-aaaaaaaaaaaaaaaa', 's', 'SENT'),
    jsonb_build_object('t', 'token-approved-bbbbbbbbbbbbbbbb', 's', 'DEAD')));
  assert not exists (select 1 from tp_push_tokens where fcm_token = 'token-approved-bbbbbbbbbbbbbbbb'),
    'a dead token is removed';
end $$;
set role anon;
select tp_register_push_t('TRIP-1', 'viewer-ok', 'token-approved-bbbbbbbbbbbbbbbb');
reset role;

-- 4. Nothing reaches followers before approval of the journey analytics.
do $$ declare c0 bigint; begin
  -- a live journey still cannot announce its completion
  c0 := pg_temp.calls();
  perform pg_temp.ev('ev-done-early', 'TRIP_COMPLETED');
  assert pg_temp.calls() = c0, 'completion held without approval';
  assert tp_push_claim('ev-done-early', pg_temp.secret()) is null, 'and cannot be claimed';
  assert not exists (select 1 from tp_push_log where event_id = 'ev-done-early'), 'held, not burned';
  delete from tp_events where event_id = 'ev-done-early';
end $$;
set role anon;
select tp_push_state('key-1', 'owner-1', '{"journey":"ARRIVED","wrappingUp":true}');
reset role;
do $$ declare c0 bigint := pg_temp.calls(); begin
  perform pg_temp.ev('ev-note', 'QUICK_NOTE');
  perform pg_temp.ev('ev-done', 'TRIP_COMPLETED');
  assert pg_temp.calls() = c0, 'nothing is sent while the journey waits for review';
  perform pg_temp.backdate('ev-note');
  update tp_events set created_at = now() - interval '5 minutes' where event_id in ('ev-note', 'ev-done');
  assert tp_push_sweep() = 0, 'the sweeper holds them too';
  perform pg_temp.ev('ev-sos', 'SOS_ACTIVATED');
  assert pg_temp.calls() = c0 + 1, 'SOS is never held';
end $$;

-- 5. Approval: financial analytics refused; clean analytics release the held events.
select set_config('test.c0', pg_temp.calls()::text, false);
set role anon;
do $$ begin
  assert tp_publish_analytics('key-1', 'not-the-owner', '{"distanceKm":320}', 0, 'x', false, false) = 'DENIED';
  assert tp_publish_analytics('key-1', 'owner-1', '{"distanceKm":320,"fuelCost":2800}', 0, 'Amma', false, false) = 'FINANCIAL';
  assert tp_publish_analytics('key-1', 'owner-1', '{"summary":{"expenses":[1]}}', 0, 'Amma', false, false) = 'FINANCIAL';
  assert tp_publish_analytics('key-1', 'owner-1', '{"note":"spent ₹450"}', 0, 'Amma', false, false) = 'FINANCIAL';
  assert tp_get_analytics('key-1') is null, 'nothing stored on refusal';
  assert tp_publish_analytics('key-1', 'owner-1',
    '{"distanceKm":320.5,"drivingSeconds":21000,"stops":4,"tollsCrossed":6}',
    (extract(epoch from now()) * 1000)::bigint, 'Amma', true, false) = 'OK';
  assert (tp_get_analytics('key-1')->'analytics'->>'stops')::int = 4, 'followers can read approved analytics';
  assert (tp_get_analytics_t('TRIP-1', 'viewer-ok')->>'safeConfirmed')::boolean, 'trip-id follower too';
  assert tp_get_analytics_t('TRIP-1', 'viewer-no') is null, 'not a denied/pending one';
end $$;
reset role;
do $$ begin
  assert (select count(*) from net.calls where id > current_setting('test.c0')::bigint
          and body->>'eventId' in ('ev-note', 'ev-done')) = 2, 'approval releases the held note and completion';
end $$;

-- 6. Reports: only after approval, only once uploaded, announced once.
set role anon;
select tp_upsert_meta('key-2', 'owner-2', '{"tripId":"TRIP-2"}', ((extract(epoch from now()) + 7200) * 1000)::bigint);
reset role;
set role service_role;
do $$ declare b jsonb; begin
  b := tp_report_begin('key-2', 'owner-2', repeat('a', 64), 1000);
  assert b->>'error' = 'NOT_APPROVED', 'no report before approval';
  b := tp_report_begin('key-1', 'owner-2', repeat('a', 64), 1000);
  assert b->>'error' = 'DENIED', 'only the owner';
  b := tp_report_begin('key-1', 'owner-1', 'not-a-hash', 1000);
  assert b->>'error' = 'INVALID';
  b := tp_report_begin('key-1', 'owner-1', repeat('b', 64), 52000);
  assert (b->>'version')::int = 1 and b->>'path' like '%.pdf', 'version 1 reserved';
  perform set_config('test.path', b->>'path', false);
end $$;
reset role;
set role anon;
do $$ begin
  assert tp_report_publish('key-1', 'owner-1', 1) = 'NOT_UPLOADED', 'not before the file exists';
end $$;
reset role;
insert into storage.objects (bucket_id, name, metadata)
values ('journey-reports', current_setting('test.path'), '{"size":52000}');
select set_config('test.c0', pg_temp.calls()::text, false);
set role anon;
do $$ begin
  assert tp_report_publish('key-1', 'owner-2', 1) = 'DENIED';
  assert tp_report_publish('key-1', 'owner-1', 1) = 'OK';
  assert tp_report_publish('key-1', 'owner-1', 1) = 'OK', 'idempotent';
end $$;
reset role;
do $$ begin
  assert pg_temp.calls() = current_setting('test.c0')::bigint + 1, 'report availability announced exactly once';
  assert (select count(*) from tp_events where event->>'type' = 'JOURNEY_REPORT_AVAILABLE') = 1;
end $$;
set role service_role;
do $$ begin
  assert tp_report_locate('key-1', null, null)->>'path' = current_setting('test.path'), 'passcode follower';
  assert tp_report_locate(null, 'TRIP-1', 'viewer-ok')->>'path' = current_setting('test.path'), 'approved follower';
  assert tp_report_locate(null, 'TRIP-1', 'viewer-no') is null, 'not a pending/denied one';
  assert tp_report_locate('key-2', null, null) is null, 'no report, nothing';
end $$;
reset role;

-- 7. Privileges: the sender-side and table surface is closed to the app key.
set role anon;
do $$ begin
  begin perform tp_push_claim('ev-toll', 'x'); assert false, 'anon must not claim';
  exception when insufficient_privilege then null; end;
  begin perform tp_report_begin('key-1', 'owner-1', repeat('c', 64), 10); assert false, 'anon must not begin';
  exception when insufficient_privilege then null; end;
  begin perform tp_report_locate('key-1', null, null); assert false, 'anon must not locate';
  exception when insufficient_privilege then null; end;
  begin perform 1 from tp_journey_analytics; assert false, 'anon must not read analytics table';
  exception when insufficient_privilege then null; end;
  begin perform 1 from tp_push_deliveries; assert false, 'anon must not read deliveries';
  exception when insufficient_privilege then null; end;
end $$;
reset role;

-- 8. Unfollowing one journey stops only that journey's pushes.
set role anon;
select tp_register_push('key-2', 'token-passcode-aaaaaaaaaaaaaaaa');
do $$ begin
  assert tp_unregister_push_for('TRIP-2', 'token-passcode-aaaaaaaaaaaaaaaa'), 'by trip id';
end $$;
reset role;
do $$ begin
  assert not exists (select 1 from tp_push_tokens where access_key = 'key-2'), 'journey 2 forgotten';
  assert exists (select 1 from tp_push_tokens where access_key = 'key-1'
                 and fcm_token = 'token-passcode-aaaaaaaaaaaaaaaa'), 'journey 1 untouched';
end $$;

-- 9. A journey's end removes its report, and tp-report is asked to delete the file.
do $$ declare t jsonb; c0 bigint := pg_temp.calls(); begin
  update tp_trips set expires_at = now() - interval '1 minute' where access_key = 'key-1';
  perform tp_cleanup();
  assert not exists (select 1 from tp_journey_reports where access_key = 'key-1');
  assert not exists (select 1 from tp_journey_analytics where access_key = 'key-1');
  t := tp_report_trash_take(pg_temp.secret());
  assert t->0->>'path' = current_setting('test.path'), 'object queued for deletion';
  assert tp_report_trash_take('wrong') is null;
  perform tp_push_sweep();
  assert exists (select 1 from net.calls where id > c0 and body->>'action' = 'gc'
                 and url like '%/tp-report'), 'sweeper asks tp-report to collect';
  assert tp_report_trash_clear(pg_temp.secret(), array[current_setting('test.path')]);
  assert not exists (select 1 from tp_report_trash);
end $$;

select 'push, approval gate, analytics and reports: all assertions passed' as result;

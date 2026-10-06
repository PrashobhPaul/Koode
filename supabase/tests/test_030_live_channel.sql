-- The live nudge: every state write broadcasts a data-free message on the
-- journey's own random channel, and only readers of the state learn the name.
\set ON_ERROR_STOP 1

set role anon;
select tp_upsert_meta('key-live', 'owner-live', '{"tripId":"TRIP-LIVE"}', ((extract(epoch from now()) + 7200) * 1000)::bigint);
reset role;

do $$ declare n0 bigint := (select count(*) from realtime.sent); ch text; s jsonb; begin
  set local role anon;
  assert tp_push_state('key-live', 'owner-live', '{"journey":"DRIVING","lat":17.4}'), 'owner writes state';
  s := tp_get_state('key-live');
  ch := s->>'liveChannel';
  assert ch is not null and length(ch) = 32, 'state carries a 32-hex live channel';
  assert s->>'journey' = 'DRIVING' and (s->>'lat')::float = 17.4, 'the state itself is untouched';
  reset role;
  assert (select count(*) from realtime.sent) = n0 + 1, 'one nudge per write';
  assert exists (select 1 from realtime.sent where topic = 'koode:' || ch and event = 'fix' and not private), 'nudge on the journey''s public channel';
  assert not exists (select 1 from realtime.sent where topic = 'koode:' || ch and (payload ? 'lat' or payload ? 'state')), 'the nudge carries no position';

  -- The channel is stable for the journey, and different for another one.
  set local role anon;
  perform tp_push_state('key-live', 'owner-live', '{"journey":"STOPPED"}');
  assert tp_get_state('key-live')->>'liveChannel' = ch, 'same channel on the next write';
  perform tp_upsert_meta('key-other', 'owner-other', '{"tripId":"TRIP-OTHER"}', ((extract(epoch from now()) + 7200) * 1000)::bigint);
  perform tp_push_state('key-other', 'owner-other', '{"journey":"DRIVING"}');
  assert tp_get_state('key-other')->>'liveChannel' <> ch, 'each journey has its own channel';

  -- A wrong owner token writes nothing and sends nothing.
  reset role;
  n0 := (select count(*) from realtime.sent);
  set local role anon;
  assert not tp_push_state('key-live', 'not-the-owner', '{"journey":"HIJACK"}'), 'wrong owner refused';
  reset role;
  assert (select count(*) from realtime.sent) = n0, 'no nudge for a refused write';
  assert (select state->>'journey' from tp_state where access_key = 'key-live') = 'STOPPED', 'state unchanged';
end $$;

-- Realtime unavailable: the write still stands.
do $$ begin
  alter function realtime.send(jsonb, text, text, boolean) rename to send_off;
  set local role anon;
  assert tp_push_state('key-live', 'owner-live', '{"journey":"DRIVING"}'), 'write stands without Realtime';
  reset role;
  alter function realtime.send_off(jsonb, text, text, boolean) rename to send;
end $$;

select 'live channel ok';

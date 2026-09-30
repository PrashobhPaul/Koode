-- Toll plazas from OpenStreetMap: requested, collected, served, never emptied
-- by a failed refresh.
\set ON_ERROR_STOP 1

-- 1. A refresh asks Overpass for both halves of India.
do $$ begin
  perform tp_toll_refresh_request();
  assert (select count(*) from tp_toll_refresh where request_id is not null) = 2, 'two parts requested';
  assert (select count(*) from net.calls where url like '%overpass%'
          and body->>'data' like '%barrier"="toll_booth"%') = 2, 'Overpass queried for toll booths';
end $$;

-- 2. Answers arrive: south fine, north an error page.
insert into net._http_response (id, status_code, content)
select request_id, 200, '{"elements":[
  {"type":"node","id":101,"lat":10.3742,"lon":76.303,"tags":{"barrier":"toll_booth","name":"Paliyekkara Toll Plaza"}},
  {"type":"node","id":102,"lat":10.3742,"lon":76.3034,"tags":{"barrier":"toll_booth","name:en":"Paliyekkara, NH 544"}},
  {"type":"node","id":103,"lat":12.91,"lon":77.61,"tags":{"barrier":"toll_booth"}}
]}' from tp_toll_refresh where part = 'south';
insert into net._http_response (id, status_code, content)
select request_id, 504, '<html>timeout</html>' from tp_toll_refresh where part = 'north';

do $$ begin
  assert tp_toll_refresh_collect() = 3, 'three booths loaded';
  assert (select count(*) from tp_toll_plazas) = 3;
  assert (select name from tp_toll_plazas where osm_id = 'n102') = 'Paliyekkara, NH 544', 'English name preferred';
  assert (select name from tp_toll_plazas where osm_id = 'n103') is null, 'unnamed booths kept';
  assert (select request_id from tp_toll_refresh where part = 'north') is null, 'failed part released';
  assert (select loaded_at from tp_toll_refresh where part = 'north') is null, 'but not marked loaded';
end $$;

-- 3. A broken answer never empties the list.
do $$ begin
  perform tp_toll_refresh_request();
end $$;
insert into net._http_response (id, status_code, content)
select request_id, 200, '{"elements": [trunca' from tp_toll_refresh where part = 'south';
do $$ begin
  perform tp_toll_refresh_collect();
  assert (select count(*) from tp_toll_plazas) = 3, 'list survives a corrupt answer';
end $$;

-- 4. Served to the app, only when newer.
set role anon;
do $$ declare r jsonb; v bigint; begin
  r := tp_toll_plazas(null);
  v := (r->>'version')::bigint;
  assert v > 0 and r ? 'plazas', 'first download has the list';
  assert array_length(string_to_array(r->>'plazas', E'\n'), 1) = 3, 'one line per booth';
  assert (r->>'plazas') like '%n101,10.374200,76.303000,Paliyekkara Toll Plaza%', 'id,lat,lng,name';
  r := tp_toll_plazas(v);
  assert not (r ? 'plazas'), 'up to date: nothing re-sent';
  begin perform tp_toll_refresh_request(); assert false, 'anon must not trigger refreshes';
  exception when insufficient_privilege then null; end;
  begin perform 1 from tp_toll_plazas; assert false, 'anon reads only through the function';
  exception when insufficient_privilege then null; end;
end $$;
reset role;

-- 5. Scheduled.
do $$ begin
  assert exists (select 1 from cron.job where jobname = 'tp-toll-refresh');
  assert exists (select 1 from cron.job where jobname = 'tp-toll-collect');
end $$;

select 'toll plazas: all assertions passed' as result;

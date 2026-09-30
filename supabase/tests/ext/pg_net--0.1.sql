create schema if not exists net;
create table net.calls (id bigserial primary key, url text, body jsonb, headers jsonb, at timestamptz default now());
-- responses are written by tests to simulate the network
create table net._http_response (id bigint primary key, status_code int, content text, created timestamptz default now());
create function net.http_post(url text, body jsonb default '{}', params jsonb default '{}',
  headers jsonb default '{}', timeout_milliseconds int default 5000) returns bigint
language sql as $$ insert into net.calls (url, body, headers) values (url, body, headers) returning id $$;
create function net.http_get(url text, params jsonb default '{}', headers jsonb default '{}',
  timeout_milliseconds int default 5000) returns bigint
language sql as $$ insert into net.calls (url, body, headers) values (url, params, headers) returning id $$;

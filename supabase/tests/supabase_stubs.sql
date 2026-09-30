-- Local stand-ins for the parts of a Supabase project that schema.sql relies
-- on, so the whole backend can be tested against a plain PostgreSQL 16.
-- Not for production: Supabase provides the real vault, storage and roles.

do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then create role service_role nologin bypassrls; end if;
end $$;

create schema extensions;
create extension pgcrypto with schema extensions;

-- vault: plain-text stand-in with the same surface schema.sql uses
create schema vault;
create table vault.secrets (id uuid primary key default gen_random_uuid(), name text unique, secret text);
create view vault.decrypted_secrets as select id, name, secret as decrypted_secret from vault.secrets;
create function vault.create_secret(p_secret text, p_name text) returns uuid
language sql as $$ insert into vault.secrets (name, secret) values (p_name, p_secret) returning id $$;

-- storage: just the two tables the SQL touches
create schema storage;
create table storage.buckets (
  id text primary key, name text not null, public boolean default false,
  file_size_limit bigint, allowed_mime_types text[]
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(), bucket_id text references storage.buckets (id),
  name text, metadata jsonb, created_at timestamptz default now()
);

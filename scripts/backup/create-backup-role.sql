-- Read-only role used by the backup (NFR-008, ADR-0002). Run it as the role that owns the tables
-- (postgres in Supabase, app in CI), with the password in the BACKUP_ROLE_PASSWORD environment variable.
-- It is safe to run again: it only resets the password and the grants.
\set ON_ERROR_STOP on
\getenv backup_password BACKUP_ROLE_PASSWORD

select 'create role backup_reader login'
where not exists (select from pg_roles where rolname = 'backup_reader') \gexec

-- bypassrls: Supabase turns row level security on for every table; without it the dump would have zero rows.
alter role backup_reader login bypassrls password :'backup_password';

grant usage on schema public to backup_reader;
grant select on all tables in schema public to backup_reader;
grant select on all sequences in schema public to backup_reader;

-- Tables and sequences created later by a migration (by the role running this script).
alter default privileges in schema public grant select on tables to backup_reader;
alter default privileges in schema public grant select on sequences to backup_reader;

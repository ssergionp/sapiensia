-- SEC-028: the financial record only receives new rows. One generic guard, so a future financial table needs
-- only its two triggers. Same mechanism as audit_events (V10), but with no retention exception.
-- Data is never fixed in place: only a future migration that explicitly disables these triggers (and re-enables
-- them in the same migration) may correct a row, so the change is visible in review.
-- The message carries only the table name, never a column of the row (the payload may hold personal data).
create function financial_record_guard() returns trigger language plpgsql as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '42501';
end $$;

create trigger payment_events_guard_row before update or delete on payment_events
  for each row execute function financial_record_guard();
create trigger payment_events_guard_truncate before truncate on payment_events
  for each statement execute function financial_record_guard();

-- SEC-028: restricted access. With row level security on and no policy, every role but the owner (the application)
-- sees no rows and inserts none, even if it holds a grant. The Supabase Data API roles exist only on Supabase.
alter table payment_events enable row level security;
revoke all on payment_events from public;
do $$
declare api_role text;
begin
  foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = api_role) then
      execute format('revoke all on payment_events from %I', api_role);
    end if;
  end loop;
end $$;

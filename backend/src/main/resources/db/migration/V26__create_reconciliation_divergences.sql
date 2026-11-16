-- Reconciliation divergences (#39, ADR-0002): each difference the nightly reconciliation finds between an Order and
-- its Asaas charge is remembered once per (kind, charge), so it is reported once and not every night.
-- The reconciliation only inserts rows here; it never updates or deletes them. Only closed codes, the charge id and the
-- Order id are stored: no personal data and no provider response body.
create table reconciliation_divergences (
  id bigint generated always as identity primary key,
  kind text not null check (kind ~ '^[A-Z_]{1,40}$'),
  charge_id text not null check (charge_id ~ '^[A-Za-z0-9_]{1,64}$'),
  order_id uuid,
  found_at timestamp with time zone not null,
  unique (kind, charge_id)
);

-- Restricted access (same as payment_events, V17).
alter table reconciliation_divergences enable row level security;
revoke all on reconciliation_divergences from public;
do $$
declare api_role text;
begin
  foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = api_role) then
      execute format('revoke all on reconciliation_divergences from %I', api_role);
    end if;
  end loop;
end $$;

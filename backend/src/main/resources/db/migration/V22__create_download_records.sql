-- Download record (ADR-0007, #33): one row per download link issued to a Customer for a Product. It keeps only the
-- Customer, the Product, the File version (product_files.uploaded_at) and the date. It stores no IP and no data about
-- where the request came from. It is append-only, like the financial record: it is the proof of delivery in a refund
-- dispute and the base of the daily limit (#34).
-- Note for #41: download_records references customers (id) and is append-only, so deleting an account must anonymize the customers row in place, never delete it.
create table download_records (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers (id),
  product_id uuid not null references products (id),
  file_uploaded_at timestamp with time zone not null,
  downloaded_at timestamp with time zone not null
);
create index download_records_customer_product_day on download_records (customer_id, product_id, downloaded_at);

create trigger download_records_guard_row before update or delete on download_records
  for each row execute function financial_record_guard();
create trigger download_records_guard_truncate before truncate on download_records
  for each statement execute function financial_record_guard();

-- Restricted access (same as payment_events, V17).
alter table download_records enable row level security;
revoke all on download_records from public;
do $$
declare api_role text;
begin
  foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = api_role) then
      execute format('revoke all on download_records from %I', api_role);
    end if;
  end loop;
end $$;

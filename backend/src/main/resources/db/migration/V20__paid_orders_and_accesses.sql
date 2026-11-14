-- Paid Orders (ADR-0003: an expired Order paid later becomes paid).
alter table orders drop constraint orders_status_check;
alter table orders add constraint orders_status_check check (status in ('PENDING', 'EXPIRED', 'PAID'));
alter table orders add column paid_at timestamp with time zone;
alter table orders add constraint orders_paid_at_check check ((status = 'PAID') = (paid_at is not null));

-- The right of a Customer to download a Product (FR-024). One per Customer and Product; order_id is the Order that
-- granted it. Revocation (refund, fraud) is #36.
create table accesses (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers (id),
  product_id uuid not null references products (id),
  order_id uuid not null references orders (id),
  granted_at timestamp with time zone not null,
  constraint accesses_customer_product_key unique (customer_id, product_id),
  constraint accesses_order_id_key unique (order_id)
);

-- What processing a payment event ended with (SEC-028: append-only, like payment_events). At most one final result
-- per event; GAVE_UP is not final, so an event that was given up can be reprocessed and get its final result later.
create table payment_event_results (
  id uuid primary key default gen_random_uuid(),
  payment_event_id uuid not null references payment_events (id),
  result varchar(32) not null check (result ~ '^[A-Z_]+$'),
  order_id uuid references orders (id),
  processed_at timestamp with time zone not null
);
create unique index payment_event_results_one_final on payment_event_results (payment_event_id)
  where result <> 'GAVE_UP';
create index payment_event_results_order_id on payment_event_results (order_id);

create trigger payment_event_results_guard_row before update or delete on payment_event_results
  for each row execute function financial_record_guard();
create trigger payment_event_results_guard_truncate before truncate on payment_event_results
  for each statement execute function financial_record_guard();

-- SEC-028: restricted access (same as payment_events, V17).
alter table payment_event_results enable row level security;
revoke all on payment_event_results from public;
do $$
declare api_role text;
begin
  foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = api_role) then
      execute format('revoke all on payment_event_results from %I', api_role);
    end if;
  end loop;
end $$;

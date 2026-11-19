-- Refund request on the site (#213): the Customer asks for a Refund of a paid Order; the request is kept here, and the
-- Access is suspended (accesses.suspended_at, null = not suspended) until the Administrator decides.
create table refund_requests (
  id uuid primary key,
  order_id uuid not null unique references orders (id),
  customer_id uuid not null references customers (id),
  reason text not null,
  details text,
  requested_at timestamp with time zone not null,
  constraint refund_requests_reason_check
    check (reason in ('NOT_AS_EXPECTED', 'DEFECTIVE_FILE', 'BOUGHT_BY_MISTAKE', 'OTHER')),
  constraint refund_requests_details_check check (char_length(details) between 1 and 500)
);
create index refund_requests_customer_requested_idx on refund_requests (customer_id, requested_at);

alter table accesses add column suspended_at timestamp with time zone;

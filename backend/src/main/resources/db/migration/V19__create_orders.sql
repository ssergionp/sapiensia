-- The Asaas customer code of a Customer; the CPF is never stored (ADR-0003, SEC-033).
create table payment_customers (
  customer_id uuid primary key references customers (id) on delete cascade,
  provider_customer_id varchar(64) not null,
  created_at timestamp with time zone not null default now(),
  constraint payment_customers_provider_customer_id_key unique (provider_customer_id)
);

-- One Order is one Product and one Pix charge (FR-019). price_cents is the server price when the Order was created.
-- The Pix copy-paste code and QR Code are read from Asaas on every view and never stored.
create table orders (
  id uuid primary key,
  customer_id uuid not null references customers (id),
  product_id uuid not null references products (id),
  price_cents integer not null check (price_cents > 0),
  status varchar(16) not null check (status in ('PENDING', 'EXPIRED')),
  provider_payment_id varchar(64) not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  expired_at timestamp with time zone,
  constraint orders_provider_payment_id_key unique (provider_payment_id)
);

create unique index orders_one_pending_per_customer_product on orders (customer_id, product_id) where status = 'PENDING';
create index orders_pending_expires_at on orders (expires_at) where status = 'PENDING';

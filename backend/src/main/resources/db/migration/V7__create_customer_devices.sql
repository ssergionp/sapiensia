create table customer_devices (
    customer_id uuid not null references customers (id) on delete cascade,
    token_hash varchar(64) not null,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (customer_id, token_hash)
);

alter table customers add column first_login_at timestamptz null;

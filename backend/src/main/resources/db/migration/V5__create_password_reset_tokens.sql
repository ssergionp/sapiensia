create table password_reset_tokens (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers (id) on delete cascade,
  token_hash varchar(64) not null,
  expires_at timestamp with time zone not null,
  used_at timestamp with time zone,
  created_at timestamp with time zone not null default now(),
  constraint password_reset_tokens_token_hash_key unique (token_hash)
);

create index password_reset_tokens_customer_id_idx on password_reset_tokens (customer_id);

create table email_confirmation_tokens (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers (id) on delete cascade,
  token_hash varchar(64) not null,
  expires_at timestamp with time zone not null,
  failed_attempts smallint not null default 0,
  created_at timestamp with time zone not null default now(),
  constraint email_confirmation_tokens_token_hash_key unique (token_hash)
);

create index email_confirmation_tokens_customer_id_idx on email_confirmation_tokens (customer_id);

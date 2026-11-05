create table administrators (
  id uuid primary key default gen_random_uuid(),
  email varchar(254) not null,
  password_hash varchar(255),
  session_version integer not null default 0,
  password_link_sent_at timestamp with time zone,
  created_at timestamp with time zone not null default now(),
  constraint administrators_email_key unique (email)
);

create table administrator_password_tokens (
  id uuid primary key default gen_random_uuid(),
  administrator_id uuid not null references administrators (id) on delete cascade,
  token_hash varchar(64) not null,
  expires_at timestamp with time zone not null,
  used_at timestamp with time zone,
  created_at timestamp with time zone not null default now(),
  constraint administrator_password_tokens_token_hash_key unique (token_hash)
);

create index administrator_password_tokens_administrator_id_idx on administrator_password_tokens (administrator_id);

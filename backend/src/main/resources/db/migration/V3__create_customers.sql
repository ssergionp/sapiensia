create table customers (
  id uuid primary key default gen_random_uuid(),
  email varchar(254) not null,
  password_hash varchar(255) not null,
  email_confirmed_at timestamp with time zone,
  created_at timestamp with time zone not null default now(),
  constraint customers_email_key unique (email)
);

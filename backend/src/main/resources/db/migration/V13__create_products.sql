create table products (
  id uuid primary key default gen_random_uuid(),
  title varchar(120) not null,
  description varchar(5000) not null,
  category varchar(16) not null check (category in ('HEALTH', 'LAW', 'FINANCE', 'SECURITY', 'EDUCATION', 'TECHNOLOGY', 'BUSINESS', 'OTHER')),
  price_cents integer not null check (price_cents > 0),
  status varchar(16) not null default 'DRAFT' check (status in ('DRAFT')),
  version integer not null default 0,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now()
);

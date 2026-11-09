create table administrator_recovery_codes (
  id uuid primary key default gen_random_uuid(),
  administrator_id uuid not null references administrators (id) on delete cascade,
  code_hash varchar(64) not null,
  used_at timestamp with time zone,
  created_at timestamp with time zone not null default now()
);
create index administrator_recovery_codes_administrator_id_idx on administrator_recovery_codes (administrator_id);

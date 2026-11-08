create table audit_events (
  id uuid primary key default gen_random_uuid(),
  occurred_at timestamp with time zone not null,
  actor_id uuid,
  action varchar(64) not null check (action ~ '^[A-Z_]+$'),
  object_type varchar(32) check (object_type ~ '^[A-Z_]+$'),
  object_id uuid,
  result varchar(16) not null check (result ~ '^[A-Z_]+$'),
  correlation_id uuid not null,
  origin inet
);
create index audit_events_occurred_at_idx on audit_events (occurred_at);

-- append-only: UPDATE and TRUNCATE never; DELETE only of rows older than the 365-day retention floor
create function audit_events_guard_row() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    if old.occurred_at < now() - interval '365 days' then
      return old;
    end if;
  end if;
  raise exception 'audit_events is append-only' using errcode = '42501';
end $$;

create function audit_events_guard_truncate() returns trigger language plpgsql as $$
begin
  raise exception 'audit_events is append-only' using errcode = '42501';
end $$;

create trigger audit_events_guard_row before update or delete on audit_events
  for each row execute function audit_events_guard_row();
create trigger audit_events_guard_truncate before truncate on audit_events
  for each statement execute function audit_events_guard_truncate();

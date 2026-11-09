-- same cutoff as AuditEvents.deleteOlderThan: start of the current UTC day minus whole days, independent of the
-- session time zone (the V10 version used now() - interval, which shifts with daylight saving time)
create or replace function audit_events_guard_row() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    if old.occurred_at < (date_trunc('day', now() at time zone 'UTC') - interval '365 days') at time zone 'UTC' then
      return old;
    end if;
  end if;
  raise exception 'audit_events is append-only' using errcode = '42501';
end $$;

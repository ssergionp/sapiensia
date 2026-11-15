-- Alerts for the Administrator (#38): the detecting code only writes a row here; a recurring task e-mails them grouped.
-- No foreign keys: other features are referenced by id only.
create table admin_alerts (
    id bigint generated always as identity primary key,
    type text not null check (type ~ '^[A-Z_]{1,40}$'),
    detail text check (detail ~ '^[A-Za-z0-9_-]{1,64}$'),
    order_id uuid,
    payment_event_id uuid,
    occurrences integer not null default 1 check (occurrences > 0),
    occurred_at timestamp with time zone not null,
    email_attempted_at timestamp with time zone,
    email_sent_at timestamp with time zone,
    email_failed_at timestamp with time zone,
    resolved_at timestamp with time zone
);

-- one pending row per identical alert: a repeated one only raises the counter
create unique index admin_alerts_pending_uq
    on admin_alerts (type, detail, order_id, payment_event_id) nulls not distinct
    where email_sent_at is null and email_failed_at is null and resolved_at is null;

create index admin_alerts_sent_idx on admin_alerts (email_sent_at) where email_sent_at is not null;

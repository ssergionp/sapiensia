-- Every webhook event from the payment provider, stored before any processing (FR-021). provider_event_id is the
-- provider's event id and the deduplication key (SEC-025, ADR-0003); payload is the body as received, with any
-- cpfCnpj redacted by the application (the Asaas payload does not carry it; ADR-0003 forbids storing the CPF).
create table payment_events (
  id uuid primary key default gen_random_uuid(),
  provider_event_id varchar(255) not null,
  event_type varchar(64) not null check (event_type ~ '^[A-Z0-9_]+$'),
  payload jsonb not null,
  received_at timestamp with time zone not null,
  constraint payment_events_provider_event_id_key unique (provider_event_id)
);

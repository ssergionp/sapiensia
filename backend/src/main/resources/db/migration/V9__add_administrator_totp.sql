-- TOTP of the Administrator (#15). totp_secret set with totp_enabled_at null: enrollment started, not confirmed.
-- totp_last_step: the last accepted 30 s step; a code is accepted only for a greater step (no replay).
alter table administrators
  add column totp_secret varchar(255),
  add column totp_enabled_at timestamp with time zone,
  add column totp_last_step bigint;

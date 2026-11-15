-- Delivery state of the payment confirmation e-mail (FR-024); it never changes the Order status.
alter table orders add column payment_email_sent_at timestamptz;
alter table orders add column payment_email_failed_at timestamptz;

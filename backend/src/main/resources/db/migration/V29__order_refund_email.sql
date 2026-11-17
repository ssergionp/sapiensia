-- Delivery state of the refund e-mail to the Customer (#131); it never changes the Order status.
alter table orders add column refund_email_sent_at timestamptz;
alter table orders add column refund_email_failed_at timestamptz;

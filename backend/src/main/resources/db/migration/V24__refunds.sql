-- Refund and Pix MED (#36): an Order can end REFUNDED, and its Access is cancelled (accesses.cancelled_at, V21).
alter table orders drop constraint orders_status_check;
alter table orders add constraint orders_status_check
  check (status in ('PENDING', 'EXPIRED', 'PAID', 'REFUNDED'));
alter table orders add column refunded_at timestamp with time zone;
alter table orders add constraint orders_refunded_at_check check ((status = 'REFUNDED') = (refunded_at is not null));

-- A refund can arrive before the payment was seen, so a REFUNDED Order may or may not have paid_at.
alter table orders drop constraint orders_paid_at_check;
alter table orders add constraint orders_paid_at_check check (
  (status = 'PAID' and paid_at is not null)
  or (status in ('PENDING', 'EXPIRED') and paid_at is null)
  or status = 'REFUNDED');

-- Repurchase after a cancellation: only an ACTIVE Access is unique per Customer and Product. The cancelled row stays
-- as history; the new purchase gets a new row. accesses_order_id_key stays: one Access per Order.
alter table accesses drop constraint accesses_customer_product_key;
create unique index accesses_one_active_per_customer_product on accesses (customer_id, product_id)
  where cancelled_at is null;

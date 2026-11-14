-- Access cancellation (#32 reads it, #36 writes it). null = active Access. An Access is cancelled by #36 (refund, Pix
-- MED or confirmed fraud), which fills this column; the Library (#32) lists only Accesses where it is null.
-- Note for #36: accesses_customer_product_key unique (customer_id, product_id) and Accesses.exists (the
-- 409 ALREADY_OWNED check) ignore cancelled_at, so after a cancellation the Customer could not buy the same Product
-- again. #36 must decide that.
alter table accesses add column cancelled_at timestamp with time zone;

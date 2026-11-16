-- When the Customer's personal data was anonymized in place (#41, FR-036, ADR-0007). The row stays, because Orders,
-- Accesses and Download records point to it; null means the account was never anonymized.
alter table customers add column anonymized_at timestamptz;

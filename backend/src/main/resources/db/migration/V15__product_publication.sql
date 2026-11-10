alter table products drop constraint products_status_check;
alter table products add constraint products_status_check check (status in ('DRAFT', 'PUBLISHED', 'UNPUBLISHED'));
alter table products add column first_published_at timestamp with time zone;
-- DRAFT exists only before the first publication, and the date is set from then on, so a Product never goes back to DRAFT
alter table products add constraint products_publication_consistent check ((status = 'DRAFT') = (first_published_at is null));

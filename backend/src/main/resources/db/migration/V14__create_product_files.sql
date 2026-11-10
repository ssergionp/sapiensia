create table product_files (
  product_id uuid primary key references products (id),
  object_key varchar(120) not null unique check (object_key ~ '^products/[0-9a-f-]{36}/[0-9a-f-]{36}\.pdf$'),
  size_bytes bigint not null check (size_bytes between 1 and 52428800),
  page_count integer not null check (page_count between 1 and 1000),
  uploaded_at timestamp with time zone not null default now()
);

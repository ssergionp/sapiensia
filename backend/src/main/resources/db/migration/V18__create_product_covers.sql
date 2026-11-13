create table product_covers (
  product_id uuid primary key references products (id),
  object_key varchar(120) not null unique
    check (object_key ~ '^covers/[0-9a-f-]{36}/[0-9a-f-]{36}\.(png|jpg|webp)$'),
  media_type varchar(16) not null check (media_type in ('image/png', 'image/jpeg', 'image/webp')),
  size_bytes integer not null check (size_bytes between 1 and 2097152),
  width integer not null check (width between 1 and 4000),
  height integer not null check (height between 1 and 4000),
  version char(16) not null,
  uploaded_at timestamp with time zone not null default now()
);

-- Every object key that may become an orphan is written here before it exists in the storage or stops being current.
create table pending_object_deletions (
  object_key varchar(120) primary key,
  created_at timestamp with time zone not null default now()
);

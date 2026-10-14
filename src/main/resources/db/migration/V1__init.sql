create table users
(
    id             bigint generated always as identity primary key,
    email          varchar(320) not null unique,
    password_hash  varchar(100) not null,
    first_name     varchar(100) not null,
    last_name      varchar(100) not null,
    role           varchar(20)  not null,
    email_verified boolean      not null default false,
    enabled        boolean      not null default true,
    locale         varchar(10)  not null default 'en',
    created_at     timestamp(6) with time zone not null default now(),
    updated_at     timestamp(6) with time zone not null default now()
);

-- One-time tokens sent by email (email verification, password reset). Only the SHA-256 hash is stored.
create table user_tokens
(
    id         bigint generated always as identity primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    type       varchar(30) not null,
    token_hash varchar(64) not null unique,
    expires_at timestamp(6) with time zone not null,
    used_at    timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null default now()
);

create index idx_user_tokens_user_type on user_tokens (user_id, type);

-- Refresh tokens are rotated on every use; tokens issued from the same login share a family.
create table refresh_tokens
(
    id         bigint generated always as identity primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    family_id  uuid        not null,
    token_hash varchar(64) not null unique,
    expires_at timestamp(6) with time zone not null,
    revoked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null default now()
);

create index idx_refresh_tokens_family on refresh_tokens (family_id);
create index idx_refresh_tokens_user on refresh_tokens (user_id);

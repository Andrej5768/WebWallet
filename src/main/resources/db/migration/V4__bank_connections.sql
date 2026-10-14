-- Connection of a user to a bank API; the token is encrypted
create table bank_connections
(
    id                 bigint generated always as identity primary key,
    user_id            bigint       not null references users (id) on delete cascade,
    provider           varchar(20)  not null,
    encrypted_token    varchar(500) not null,
    client_name        varchar(200),
    -- Unguessable part of the webhook URL
    webhook_secret     varchar(64)  not null unique,
    -- Monobank allows one request per minute per token
    last_request_at    timestamp(6) with time zone,
    last_error         varchar(500),
    webhook_registered boolean      not null default false,
    created_at         timestamp(6) with time zone not null default now(),
    unique (user_id, provider)
);

-- A bank account or card of a connection and the WebWallet account its operations go to
create table bank_account_links
(
    id                  bigint generated always as identity primary key,
    connection_id       bigint       not null references bank_connections (id) on delete cascade,
    external_account_id varchar(100) not null,
    title               varchar(200) not null,
    currency            varchar(3)   not null,
    -- Null means the bank account is not synchronized
    account_id          bigint references accounts (id) on delete set null,
    -- Operations up to this moment are imported
    synced_until        timestamp(6) with time zone not null,
    -- A statement returns at most 500 operations: when a period had more, the operations in
    -- [gap_to, gap_target] are imported and [synced_until, gap_to] is requested next
    gap_to              timestamp(6) with time zone,
    gap_target          timestamp(6) with time zone,
    bank_balance        numeric(19, 4),
    unique (connection_id, external_account_id)
);

-- Merchant category code of card payments, for categorization rules
alter table transactions add column mcc smallint;

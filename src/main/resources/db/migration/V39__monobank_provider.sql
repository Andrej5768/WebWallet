-- How a bank connection reaches the user: the personal token, or as a service provider with the id of the access
-- request the user approved in the bank app (kept, encrypted, in encrypted_token)
alter table bank_connections
    add column auth_mode varchar(20) not null default 'TOKEN';

-- An access request to Monobank waiting for the user to approve it in the bank app; it carries the settings of the
-- connection to create then. The bank keeps a request open for 24 hours.
create table monobank_auth_requests
(
    id                      bigint generated always as identity primary key,
    user_id                 bigint                      not null references users (id) on delete cascade,
    encrypted_request_id    varchar(500)                not null,
    -- Hash of the unguessable part of the callback URL
    callback_secret         varchar(64)                 not null unique,
    accept_url              varchar(500)                not null,
    status                  varchar(20)                 not null,
    since                   date,
    sync_mode               varchar(20),
    window_start            time,
    window_end              time,
    daily_at                time,
    -- The bank is asked about the request at most this often while the app polls
    checked_at              timestamp(6) with time zone,
    expires_at              timestamp(6) with time zone not null,
    created_at              timestamp(6) with time zone not null
);

create index idx_monobank_auth_requests_user on monobank_auth_requests (user_id);

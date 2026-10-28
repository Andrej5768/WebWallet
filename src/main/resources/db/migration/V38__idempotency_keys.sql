-- The responses of create requests sent with an Idempotency-Key, replayed when the same request is sent again
-- (an app retrying after a lost response); kept for a day.
create table idempotency_keys
(
    id            bigserial primary key,
    user_id       bigint                   not null references users (id) on delete cascade,
    request_key   uuid                     not null,
    request_hash  varchar(64)              not null,
    status        integer                  not null,
    content_type  varchar(100),
    response_body text                     not null,
    created_at    timestamp with time zone not null,
    unique (user_id, request_key)
);

create index idx_idempotency_keys_created_at on idempotency_keys (created_at);

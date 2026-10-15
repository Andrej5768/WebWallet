-- A parsed import waiting for the user to review it. Only the parsed records are kept (as JSON), never the file or a
-- token, and only for a limited time.
create table import_previews
(
    id         uuid        primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    source     varchar(20) not null,
    -- The parsed input that is imported on confirmation
    input      text        not null,
    -- Operations and summary shown to the user
    preview    text        not null,
    created_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null
);

create index idx_import_previews_user on import_previews (user_id);
create index idx_import_previews_expires on import_previews (expires_at);

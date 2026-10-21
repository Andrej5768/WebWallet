-- The Telegram chat of a user: notifications go there, and operations written to the bot are recorded.
create table telegram_links
(
    user_id                bigint primary key references users (id) on delete cascade,
    chat_id                bigint                      not null unique,
    username               varchar(100),
    first_name             varchar(200),
    -- Account of the operations written to the bot without naming an account
    quick_entry_account_id bigint                      references accounts (id) on delete set null,
    linked_at              timestamp(6) with time zone not null default now()
);

-- One-time code of the t.me link that connects a chat to the user; only its hash is stored.
create table telegram_link_codes
(
    user_id    bigint primary key references users (id) on delete cascade,
    code_hash  varchar(64)                 not null unique,
    expires_at timestamp(6) with time zone not null
);

-- What a user is notified about, and when the reminders and reports are sent (an hour of Kyiv time).
create table notification_settings
(
    user_id     bigint primary key references users (id) on delete cascade,
    notify_hour integer  not null default 9 check (notify_hour between 0 and 23),
    recurring   boolean  not null default true,
    budgets     boolean  not null default true,
    taxes       boolean  not null default true,
    banks       boolean  not null default true,
    forecast    boolean  not null default true,
    goals       boolean  not null default true,
    weekly      boolean  not null default true,
    monthly     boolean  not null default true
);

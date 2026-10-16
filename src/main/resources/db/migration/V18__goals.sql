-- A savings goal: money to collect by a date, on an account (e.g. a Monobank jar) or counted by hand
create table goals
(
    id            bigint generated always as identity primary key,
    user_id       bigint         not null references users (id) on delete cascade,
    name          varchar(100)   not null,
    target_amount numeric(19, 4) not null check (target_amount > 0),
    currency      varchar(3)     not null,
    target_date   date,
    -- The balance of the account is the progress; without one, saved is kept by hand
    account_id    bigint references accounts (id) on delete set null,
    saved         numeric(19, 4) not null default 0,
    -- The progress when the goal was set, from which the pace is counted
    start_date    date           not null,
    start_amount  numeric(19, 4) not null default 0,
    icon          varchar(50),
    color         varchar(20),
    achieved_on   date,
    sort_order    int            not null default 0,
    created_at    timestamp(6) with time zone not null,
    updated_at    timestamp(6) with time zone not null
);

create index idx_goals_user on goals (user_id);

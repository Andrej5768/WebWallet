-- A payment that repeats on a schedule: rent, subscriptions, salary. Its occurrences are computed from the schedule;
-- only what happened to one (paid by a transaction, skipped, or kept pending by the user) is stored.
create table recurring_payments
(
    id            bigint generated always as identity primary key,
    user_id       bigint         not null references users (id) on delete cascade,
    name          varchar(100)   not null,
    type          varchar(10)    not null,
    account_id    bigint         not null references accounts (id) on delete cascade,
    amount        numeric(19, 4) not null check (amount > 0),
    to_account_id bigint references accounts (id) on delete cascade,
    to_amount     numeric(19, 4) check (to_amount > 0),
    category_id   bigint references categories (id) on delete set null,
    payee_id      bigint references payees (id) on delete set null,
    comment       varchar(1000),
    -- DAILY, WEEKLY, MONTHLY or YEARLY, every interval_count of them, counted from start_date
    frequency     varchar(10)    not null,
    interval_count int           not null check (interval_count between 1 and 365),
    start_date    date           not null,
    end_date      date,
    -- Create the transaction on the due date instead of waiting for it to be matched or confirmed
    auto_create   boolean        not null default false,
    -- The amount is an estimate, e.g. of a utility bill
    variable_amount boolean      not null default false,
    -- How much a matching transaction may differ from the amount, in percent
    tolerance     int            not null default 10 check (tolerance between 0 and 50),
    created_at    timestamp(6) with time zone not null,
    updated_at    timestamp(6) with time zone not null,
    check ((type = 'TRANSFER') = (to_account_id is not null)),
    check (end_date is null or end_date >= start_date)
);

create index idx_recurring_payments_user on recurring_payments (user_id);

create table recurring_occurrences
(
    id             bigint generated always as identity primary key,
    recurring_id   bigint      not null references recurring_payments (id) on delete cascade,
    -- The scheduled date of the occurrence
    due_date       date        not null,
    -- PAID (by the transaction), SKIPPED, or PENDING: reset by the user, so it is not matched automatically again
    status         varchar(10) not null,
    -- A deleted transaction makes the occurrence pending again
    transaction_id bigint references transactions (id) on delete cascade,
    check ((status = 'PAID') = (transaction_id is not null)),
    unique (recurring_id, due_date)
);

create unique index uq_recurring_occurrences_transaction on recurring_occurrences (transaction_id);

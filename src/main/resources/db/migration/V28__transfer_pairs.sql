-- An expense and an income merged into one transfer keep the import id of the income, so that importing the same file
-- again does not bring the income back.
alter table transactions
    add column to_external_id varchar(200);

-- Pairs of an expense and an income the user said are not a transfer; they are not suggested again.
create table transfer_dismissals
(
    id         bigint generated always as identity primary key,
    user_id    bigint not null references users (id) on delete cascade,
    expense_id bigint not null references transactions (id) on delete cascade,
    income_id  bigint not null references transactions (id) on delete cascade,
    created_at timestamp(6) with time zone not null default now(),
    unique (expense_id, income_id)
);

create index idx_transfer_dismissals_user on transfer_dismissals (user_id);

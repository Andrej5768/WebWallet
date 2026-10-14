alter table users add column base_currency varchar(3) not null default 'UAH';

create table accounts
(
    id              bigint generated always as identity primary key,
    user_id         bigint         not null references users (id) on delete cascade,
    name            varchar(100)   not null,
    type            varchar(20)    not null,
    currency        varchar(3)     not null,
    -- Envelope budgeting distributes only the money of budget accounts
    in_budget       boolean        not null,
    archived        boolean        not null default false,
    opening_balance numeric(19, 4) not null default 0,
    credit_limit    numeric(19, 4),
    interest_rate   numeric(9, 4),
    sort_order      int            not null default 0,
    created_at      timestamp(6) with time zone not null default now(),
    updated_at      timestamp(6) with time zone not null default now()
);

create index idx_accounts_user on accounts (user_id);

create table categories
(
    id         bigint generated always as identity primary key,
    user_id    bigint       not null references users (id) on delete cascade,
    parent_id  bigint references categories (id) on delete cascade,
    name       varchar(100) not null,
    kind       varchar(10)  not null,
    archived   boolean      not null default false,
    sort_order int          not null default 0
);

create index idx_categories_user on categories (user_id);

create table tags
(
    id      bigint generated always as identity primary key,
    user_id bigint       not null references users (id) on delete cascade,
    name    varchar(100) not null,
    unique (user_id, name)
);

create table payees
(
    id      bigint generated always as identity primary key,
    user_id bigint       not null references users (id) on delete cascade,
    name    varchar(200) not null,
    unique (user_id, name)
);

-- EXPENSE and INCOME change one account by `amount`; a TRANSFER takes `amount` from `account_id` and puts
-- `to_amount` (in the currency of the target account) to `to_account_id`. Amounts are always positive.
create table transactions
(
    id                bigint generated always as identity primary key,
    user_id           bigint         not null references users (id) on delete cascade,
    type              varchar(10)    not null,
    date              date           not null,
    account_id        bigint         not null references accounts (id),
    amount            numeric(19, 4) not null check (amount > 0),
    to_account_id     bigint references accounts (id),
    to_amount         numeric(19, 4) check (to_amount > 0),
    -- Purchase in a foreign currency: the amount as charged by the merchant
    original_amount   numeric(19, 4),
    original_currency varchar(3),
    payee_id          bigint references payees (id) on delete set null,
    comment           varchar(1000),
    source            varchar(20)    not null default 'MANUAL',
    -- Id in the source system (bank, Zen Money), used for deduplication of imports
    external_id       varchar(200),
    created_at        timestamp(6) with time zone not null default now(),
    updated_at        timestamp(6) with time zone not null default now(),
    check ((type = 'TRANSFER') = (to_account_id is not null and to_amount is not null))
);

create index idx_transactions_user_date on transactions (user_id, date desc);
create index idx_transactions_account on transactions (account_id);
create index idx_transactions_to_account on transactions (to_account_id);
create unique index uq_transactions_external on transactions (user_id, source, external_id) where external_id is not null;

-- Category breakdown of an EXPENSE or INCOME; the amounts sum up to the transaction amount
create table transaction_splits
(
    id             bigint generated always as identity primary key,
    transaction_id bigint         not null references transactions (id) on delete cascade,
    category_id    bigint references categories (id),
    amount         numeric(19, 4) not null check (amount > 0),
    comment        varchar(500)
);

create index idx_transaction_splits_transaction on transaction_splits (transaction_id);
create index idx_transaction_splits_category on transaction_splits (category_id);

create table transaction_tags
(
    transaction_id bigint not null references transactions (id) on delete cascade,
    tag_id         bigint not null references tags (id) on delete cascade,
    primary key (transaction_id, tag_id)
);

-- Official NBU rate: how many UAH one unit of the currency costs on the date
create table exchange_rates
(
    currency varchar(3)     not null,
    date     date           not null,
    rate     numeric(19, 8) not null,
    primary key (currency, date)
);

-- Bank classification of an operation (e.g. "Супермаркети та продукти" in PrivatBank statements), for rules
alter table transactions add column bank_category varchar(100);

-- "If an imported operation matches, put it into this category". Every given condition must match.
create table categorization_rules
(
    id            bigint generated always as identity primary key,
    user_id       bigint      not null references users (id) on delete cascade,
    category_id   bigint      not null references categories (id) on delete cascade,
    payee_id      bigint references payees (id) on delete cascade,
    -- Case-insensitive part of the payee name
    payee_pattern varchar(200),
    mcc           smallint,
    bank_category varchar(100),
    -- Created automatically when the category of a transaction is changed manually
    learned       boolean     not null default false,
    created_at    timestamp(6) with time zone not null default now(),
    check (payee_id is not null or payee_pattern is not null or mcc is not null or bank_category is not null)
);

create index idx_categorization_rules_user on categorization_rules (user_id);

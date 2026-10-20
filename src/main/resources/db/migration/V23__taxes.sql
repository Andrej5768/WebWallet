-- Tax settings of a user for a year; a null column takes the default of the year.
create table tax_settings
(
    user_id                  bigint        not null references users (id) on delete cascade,
    year                     int           not null check (year between 2000 and 2100),
    -- Group of the sole proprietor (ФОП): 1, 2 or 3; a VAT payer of group 3 pays the lower rate
    fop_group                smallint      check (fop_group between 1 and 3),
    fop_vat                  boolean,
    -- Group 3: shares of the income, in percent
    single_tax_rate          numeric(6, 3) check (single_tax_rate between 0 and 100),
    fop_military_rate        numeric(6, 3) check (fop_military_rate between 0 and 100),
    -- Groups 1 and 2: fixed amounts a month
    single_tax_monthly       numeric(12, 2) check (single_tax_monthly >= 0),
    fop_military_monthly     numeric(12, 2) check (fop_military_monthly >= 0),
    -- The social contribution: a share of the minimum wage a month
    esv_enabled              boolean,
    esv_rate                 numeric(6, 3) check (esv_rate between 0 and 100),
    min_wage                 numeric(12, 2) check (min_wage > 0),
    -- Investments: personal income tax and military levy on dividends and investment profit
    pdfo_rate                numeric(6, 3) check (pdfo_rate between 0 and 100),
    investment_military_rate numeric(6, 3) check (investment_military_rate between 0 and 100),
    -- Whether a dividend without its own mark has the document of the tax withheld abroad
    foreign_document_default boolean,
    primary key (user_id, year)
);

-- Income categories that are income of the sole proprietor on any account, besides the income of ФОП accounts
create table tax_income_categories
(
    user_id     bigint not null references users (id) on delete cascade,
    category_id bigint not null references categories (id) on delete cascade,
    primary key (user_id, category_id)
);

-- A transaction the user added to the income of the sole proprietor (included) or took out of it
create table tax_transaction_marks
(
    transaction_id bigint  not null primary key references transactions (id) on delete cascade,
    user_id        bigint  not null references users (id) on delete cascade,
    included       boolean not null
);

create index idx_tax_transaction_marks_user on tax_transaction_marks (user_id);

-- Quarters whose taxes the user marked as paid
create table tax_fop_payments
(
    user_id bigint   not null references users (id) on delete cascade,
    year    int      not null,
    quarter smallint not null check (quarter between 1 and 4),
    primary key (user_id, year, quarter)
);

-- Whether the user has the document of the tax withheld abroad from a dividend; null takes the default
alter table investment_operations add column foreign_tax_document boolean;

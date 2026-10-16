-- Dividends of market instruments per share, as the dividend data provider reports them: paid and declared ones
create table instrument_dividends
(
    id               bigint generated always as identity primary key,
    instrument_id    bigint         not null references instruments (id) on delete cascade,
    ex_date          date           not null,
    payment_date     date,
    declaration_date date,
    record_date      date,
    amount           numeric(19, 6) not null check (amount > 0),
    unique (instrument_id, ex_date)
);

-- The last time the provider was asked for the dividends of the instrument
alter table instruments add column dividends_checked_at timestamp(6) with time zone;

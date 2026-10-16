-- A security or other asset. Market instruments (user_id null) are shared and priced by the market data provider;
-- instruments entered by hand (government bonds, other assets) belong to a user and are valued by nominal or by hand.
create table instruments
(
    id                bigint generated always as identity primary key,
    user_id           bigint         references users (id) on delete cascade,
    symbol            varchar(30)    not null,
    name              varchar(200)   not null,
    -- STOCK, ETF, FUND, BOND, GOVERNMENT_BOND, CRYPTO, OTHER
    type              varchar(20)    not null,
    currency          varchar(3)     not null,
    exchange          varchar(50),
    -- Market identifier code of the exchange; with the symbol it identifies a market instrument at the provider
    mic_code          varchar(10),
    isin              varchar(12),
    -- MARKET: daily closes from the provider; NOMINAL: nominal plus accrued coupon; MANUAL: manual_price
    valuation         varchar(10)    not null,
    nominal           numeric(19, 4),
    -- Annual coupon rate in percent and coupons per year
    coupon_rate       numeric(9, 4),
    coupon_frequency  int check (coupon_frequency between 1 and 12),
    maturity_date     date,
    manual_price      numeric(19, 6),
    manual_price_date date,
    -- Priced also without operations: a benchmark the portfolio is compared with
    benchmark         boolean        not null default false,
    -- The last time the provider was asked for prices, so that a failing symbol is not retried at once
    prices_checked_at timestamp(6) with time zone,
    created_at        timestamp(6) with time zone not null,
    updated_at        timestamp(6) with time zone not null
);

create unique index uq_instruments_market on instruments (symbol, coalesce(mic_code, '')) where user_id is null;
create index idx_instruments_user on instruments (user_id);

create table instrument_prices
(
    instrument_id bigint         not null references instruments (id) on delete cascade,
    date          date           not null,
    close         numeric(19, 6) not null check (close > 0),
    primary key (instrument_id, date)
);

-- What happened on a brokerage account: trades, income and fees. Cash moves in the currency of the operation; the
-- money brought to or taken from the account are ordinary transfers of the ledger.
create table investment_operations
(
    id            bigint generated always as identity primary key,
    user_id       bigint         not null references users (id) on delete cascade,
    account_id    bigint         not null references accounts (id),
    instrument_id bigint         references instruments (id),
    -- BUY, SELL, DIVIDEND, COUPON, FEE, SPLIT
    type          varchar(10)    not null,
    date          date           not null,
    quantity      numeric(24, 8) check (quantity > 0),
    price         numeric(19, 6) check (price >= 0),
    -- Cash of a trade (quantity times price), the gross dividend or coupon, or the fee
    amount        numeric(19, 4) not null check (amount >= 0),
    fee           numeric(19, 4) not null default 0 check (fee >= 0),
    -- Tax withheld from a dividend or coupon
    tax           numeric(19, 4) not null default 0 check (tax >= 0),
    currency      varchar(3)     not null,
    -- A split of split_from shares into split_to shares, e.g. 1 into 4
    split_from    int check (split_from > 0),
    split_to      int check (split_to > 0),
    comment       varchar(1000),
    -- Id of the operation in a broker report, for deduplication of imports
    external_id   varchar(200),
    created_at    timestamp(6) with time zone not null,
    updated_at    timestamp(6) with time zone not null,
    check ((type in ('BUY', 'SELL')) = (quantity is not null and price is not null)),
    check ((type = 'SPLIT') = (split_from is not null and split_to is not null)),
    check (type = 'FEE' or instrument_id is not null)
);

create index idx_investment_operations_user on investment_operations (user_id, date);
create index idx_investment_operations_account on investment_operations (account_id);
create index idx_investment_operations_instrument on investment_operations (instrument_id);
create unique index uq_investment_operations_external on investment_operations (account_id, external_id)
    where external_id is not null;

-- The sectors (GICS) of market instruments as the fundamental data provider reports them: one for a company, the
-- weights of a fund. Weights may add up to less than one (the cash of a fund).
create table instrument_sectors
(
    instrument_id bigint        not null references instruments (id) on delete cascade,
    sector        varchar(30)   not null,
    weight        numeric(7, 6) not null check (weight > 0 and weight <= 1),
    primary key (instrument_id, sector)
);

-- The last time the provider was asked for the sectors
alter table instruments add column sectors_checked_at timestamp(6) with time zone;

-- Money moved on a brokerage account without the ledger: deposits and withdrawals in any currency (e.g. a card
-- payment at the broker) and currency exchanges, which also bring to_amount in to_currency.
alter table investment_operations
    add column to_amount   numeric(19, 4) check (to_amount > 0),
    add column to_currency varchar(3);

-- Was: check (type = 'FEE' or instrument_id is not null)
alter table investment_operations drop constraint investment_operations_check2;

alter table investment_operations
    add constraint ck_investment_operations_instrument check (
        case
            when type in ('DEPOSIT', 'WITHDRAWAL', 'EXCHANGE') then instrument_id is null
            when type = 'FEE' then true
            else instrument_id is not null
        end),
    add constraint ck_investment_operations_exchange check (
        (type = 'EXCHANGE') = (to_amount is not null and to_currency is not null));

-- Links to records in external systems (Zen Money, banks), so that repeated imports do not create duplicates
alter table accounts add column external_source varchar(20);
alter table accounts add column external_id varchar(200);
-- Last 4 digits of the bank cards of the account, to match bank statements to it
alter table accounts add column card_numbers varchar(4)[];
create unique index uq_accounts_external on accounts (user_id, external_source, external_id) where external_id is not null;

alter table categories add column external_source varchar(20);
alter table categories add column external_id varchar(200);
create unique index uq_categories_external on categories (user_id, external_source, external_id) where external_id is not null;

-- Id of the operation in the bank, e.g. as kept by Zen Money for bank-synced records; used to match bank imports
alter table transactions add column bank_reference varchar(200);
create index idx_transactions_bank_reference on transactions (user_id, bank_reference) where bank_reference is not null;

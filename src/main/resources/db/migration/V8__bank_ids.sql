-- Ids of the bank operations a transaction stands for, without prefixes such as "[monobank][reg]-", so that every
-- importer finds an operation by an exact match: bank_id is the operation on account_id and to_bank_id the one on
-- to_account_id (the incoming half of a transfer)
alter table transactions
    add column bank_id    varchar(200),
    add column to_bank_id varchar(200);

-- Which half of an already merged transfer each id belongs to is not known; both are kept, which is what matters
-- for recognizing the operations again
update transactions
set bank_id    = case
                     when source in ('MONOBANK', 'PRIVATBANK') then external_id
                     else regexp_replace(bank_reference, '^(\[[^]]*\])+-?', '')
    end,
    to_bank_id = case
                     when source in ('MONOBANK', 'PRIVATBANK') and type = 'TRANSFER'
                         then regexp_replace(bank_reference, '^(\[[^]]*\])+-?', '')
        end
where source in ('MONOBANK', 'PRIVATBANK')
   or bank_reference is not null;

alter table transactions
    drop column bank_reference;

create index idx_transactions_bank_id on transactions (user_id, bank_id) where bank_id is not null;
create index idx_transactions_to_bank_id on transactions (user_id, to_bank_id) where to_bank_id is not null;

-- The opening balance of an account created for a bank account is aligned with the bank once, after the history
-- is loaded; later edits by the user are kept
alter table bank_account_links
    add column balance_aligned boolean not null default false;

update bank_account_links
set balance_aligned = true
where gap_to is null
  and bank_balance is not null
  and synced_until > now() - interval '1 day';

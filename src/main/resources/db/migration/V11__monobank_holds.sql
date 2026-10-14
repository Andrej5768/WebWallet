-- Card payments the bank has only reserved; they may still change their amount or be cancelled
alter table transactions
    add column hold boolean not null default false;

-- Start of the imported history of a bank account; synchronization also re-requests recent days, never before it
alter table bank_account_links
    add column history_from timestamp(6) with time zone;

update bank_account_links
set history_from = synced_until;

alter table bank_account_links
    alter column history_from set not null;

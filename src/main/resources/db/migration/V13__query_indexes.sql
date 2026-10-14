-- The transaction list is sorted by date and id
drop index idx_transactions_user_date;
create index idx_transactions_user_date_id on transactions (user_id, date desc, id desc);

-- Transfers into an account in a period (account filter, matching the incoming half of a transfer)
drop index idx_transactions_to_account;
create index idx_transactions_to_account_date on transactions (to_account_id, date);

-- Transactions of a category: the splits lead to their transactions
drop index idx_transaction_splits_category;
create index idx_transaction_splits_category_transaction on transaction_splits (category_id, transaction_id);

-- Balances of all accounts are summed from the index alone
create index idx_transactions_user_account_amount on transactions (user_id, account_id) include (type, amount);

-- Cleanup of expired tokens and unlinking of deleted accounts
create index idx_refresh_tokens_expires on refresh_tokens (expires_at);
create index idx_bank_account_links_account on bank_account_links (account_id);

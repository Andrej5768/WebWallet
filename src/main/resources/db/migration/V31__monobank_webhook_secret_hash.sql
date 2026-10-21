-- The secret in the webhook URL of a Monobank connection is kept as its SHA-256 hash, like the other tokens: a copy of
-- the database must not let anyone post operations into the accounts.
update bank_connections
set webhook_secret = encode(sha256(convert_to(webhook_secret, 'UTF8')), 'hex');

-- The accounts of the bank are read again once a day, for changed credit limits
alter table bank_connections
    add column info_refreshed_at timestamp(6) with time zone;

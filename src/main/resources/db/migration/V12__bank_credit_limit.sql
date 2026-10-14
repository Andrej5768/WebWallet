-- Credit limit of a card at the bank: the bank balance of a credit card includes it. Until the next connect, the
-- limit of the linked account is the best guess.
alter table bank_account_links
    add column bank_credit_limit numeric(19, 4);

update bank_account_links l
set bank_credit_limit = a.credit_limit
from accounts a
where a.id = l.account_id;

-- Foreign keys that are filtered by or checked on delete
create index idx_transaction_tags_tag on transaction_tags (tag_id);
create index idx_categories_parent on categories (parent_id);
create index idx_budget_assignments_category on budget_assignments (category_id);
create index idx_categorization_rules_category on categorization_rules (category_id);
create index idx_categorization_rules_payee on categorization_rules (payee_id);

-- Transactions of an account in a period, and the latest transactions of a payee (category learned from history)
drop index idx_transactions_account;
create index idx_transactions_account_date on transactions (account_id, date);
create index idx_transactions_payee_date on transactions (payee_id, date desc, id desc);

-- Payee names are unique regardless of case: merge the duplicates that differ only in case into the oldest one
with duplicates as (select id, min(id) over (partition by user_id, lower(name)) as keep_id
                    from payees)
update transactions t
set payee_id = d.keep_id
from duplicates d
where t.payee_id = d.id
  and d.id <> d.keep_id;

with duplicates as (select id, min(id) over (partition by user_id, lower(name)) as keep_id
                    from payees)
update categorization_rules r
set payee_id = d.keep_id
from duplicates d
where r.payee_id = d.id
  and d.id <> d.keep_id;

delete
from payees p
    using (select id, min(id) over (partition by user_id, lower(name)) as keep_id from payees) d
where p.id = d.id
  and d.id <> d.keep_id;

create unique index uq_payees_user_lower_name on payees (user_id, lower(name));

-- Transactions and their splits get ids in blocks of 50 from sequences of their own (see the entities), so that
-- inserts can be batched; Hibernate takes the value of the sequence as the end of the block
alter table transactions
    alter column id drop identity;
create sequence transactions_seq increment by 50 owned by transactions.id;
select setval('transactions_seq', coalesce((select max(id) from transactions), 0) + 50, false);
alter table transactions
    alter column id set default nextval('transactions_seq');

alter table transaction_splits
    alter column id drop identity;
create sequence transaction_splits_seq increment by 50 owned by transaction_splits.id;
select setval('transaction_splits_seq', coalesce((select max(id) from transaction_splits), 0) + 50, false);
alter table transaction_splits
    alter column id set default nextval('transaction_splits_seq');

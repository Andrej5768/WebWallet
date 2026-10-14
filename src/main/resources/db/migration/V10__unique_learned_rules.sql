-- V9 merged payees whose names differ only in case; their learned rules may now be duplicated. Keep the latest one.
delete
from categorization_rules r
    using (select id, max(id) over (partition by user_id, payee_id) as keep_id
           from categorization_rules
           where learned
             and payee_id is not null
             and payee_pattern is null
             and mcc is null
             and bank_category is null) d
where r.id = d.id
  and d.id <> d.keep_id;

-- A payee has at most one learned rule
create unique index uq_categorization_rules_learned_payee on categorization_rules (user_id, payee_id)
    where learned and payee_pattern is null and mcc is null and bank_category is null;

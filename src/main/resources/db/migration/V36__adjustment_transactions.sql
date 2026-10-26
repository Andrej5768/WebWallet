-- A balance correction: an expense or income without a category that is counted in the balance of its account only,
-- not in the reports, the budget, the forecast or the taxes
alter table transactions add column adjustment boolean not null default false;

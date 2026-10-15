-- Appearance of categories and tags in the web client: a Lucide icon name and a key of the color palette
alter table categories add column icon varchar(50);
alter table categories add column color varchar(20);
-- NEEDS or WANTS of an expense category for the 50/30/20 analytics; null on a subcategory means that of the parent
alter table categories add column necessity varchar(10);
alter table tags add column color varchar(20);

alter table users alter column last_name drop not null;

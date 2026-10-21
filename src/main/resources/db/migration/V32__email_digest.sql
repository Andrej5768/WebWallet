-- The email digest: the weekly and the monthly report by email, on unless the user switched them off or unsubscribed.
alter table notification_settings
    add column email_weekly  boolean not null default true,
    add column email_monthly boolean not null default true;

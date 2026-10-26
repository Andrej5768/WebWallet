-- The MANUAL mode synchronizes only when the user asks: nothing is scheduled, so the due moment may be empty.
-- The WEBHOOK mode is no longer offered; its connections follow the window schedule from now on.
alter table bank_connections
    alter column next_sync_at drop not null,
    alter column next_sync_at drop default;

update bank_connections set sync_mode = 'INTERVAL' where sync_mode = 'WEBHOOK';

-- When Monobank is asked for new operations, in Kyiv time: INTERVAL is every 15 minutes within the window and once at
-- night, DAILY once a day at daily_at, WEBHOOK has the bank push new operations and checks the accounts at daily_at
alter table bank_connections
    add column sync_mode    varchar(10) not null default 'INTERVAL',
    add column window_start time        not null default '08:00',
    add column window_end   time        not null default '23:00',
    add column daily_at     time        not null default '06:00',
    -- Due moment of the next synchronization: every linked account is then requested up to at least this moment
    add column next_sync_at timestamp(6) with time zone not null default now(),
    add constraint ck_bank_connections_window check (window_start < window_end);

create index idx_bank_connections_next_sync on bank_connections (next_sync_at);

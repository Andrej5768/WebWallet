-- A demo account is a sandbox opened without registration; it is deleted with all its data once this passes.
alter table users add column demo_expires_at timestamp with time zone;

create index idx_users_demo_expires_at on users (demo_expires_at) where demo_expires_at is not null;

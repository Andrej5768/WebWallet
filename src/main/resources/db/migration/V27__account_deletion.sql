-- An account the user asked to delete: blocked until this moment, then deleted with all its data (the foreign keys
-- cascade). Signing in before that offers to cancel.
alter table users
    add column deletion_scheduled_at timestamp(6) with time zone;

create index idx_users_deletion on users (deletion_scheduled_at) where deletion_scheduled_at is not null;

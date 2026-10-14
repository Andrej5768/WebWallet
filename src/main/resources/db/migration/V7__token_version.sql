-- Increased when the password is changed or reset; access tokens issued for an older version are rejected
alter table users
    add column token_version integer not null default 0;

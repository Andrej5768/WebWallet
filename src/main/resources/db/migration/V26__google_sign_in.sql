-- Sign-in with Google. An account created with Google has no password until the user sets one.
alter table users
    alter column password_hash drop not null,
    -- The stable Google account id ("sub" of the ID token); the email of a Google account can change
    add column google_subject varchar(255) unique,
    add column google_email   varchar(320);

-- A started Google sign-in or account link; the browser keeps the state in a cookie, the table its hash.
create table oauth_states
(
    id            bigint generated always as identity primary key,
    state_hash    varchar(64)  not null unique,
    purpose       varchar(20)  not null,
    -- The signed-in user for LINK; null for LOGIN
    user_id       bigint references users (id) on delete cascade,
    code_verifier varchar(128) not null,
    nonce         varchar(64)  not null,
    expires_at    timestamp(6) with time zone not null,
    created_at    timestamp(6) with time zone not null default now()
);

-- What the declarations need about the taxpayer (tax number, name, address, tax office), encrypted as one JSON text
-- like the bank tokens. It goes with the user; "start over" keeps it, as it is part of the profile.
create table tax_profiles
(
    user_id        bigint not null primary key references users (id) on delete cascade,
    encrypted_data text   not null,
    updated_at     timestamp(6) with time zone not null
);

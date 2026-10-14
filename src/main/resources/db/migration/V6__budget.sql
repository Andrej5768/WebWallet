-- Envelope budget: money assigned to an expense category for a month, in the base currency of the user
create table budget_assignments
(
    id          bigint generated always as identity primary key,
    user_id     bigint         not null references users (id) on delete cascade,
    month       date           not null check (extract(day from month) = 1),
    category_id bigint         not null references categories (id) on delete cascade,
    amount      numeric(19, 4) not null,
    unique (user_id, month, category_id)
);

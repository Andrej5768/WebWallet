-- Notifications already sent, so that each is sent once: a reminder of a payment, a budget threshold of a month, a
-- forecast that did not change since. The ref names what the notification was about.
create table notification_log
(
    user_id bigint                      not null references users (id) on delete cascade,
    kind    varchar(20)                 not null,
    ref     varchar(200)                not null,
    sent_at timestamp(6) with time zone not null default now(),
    primary key (user_id, kind, ref)
);

create index idx_notification_log_sent_at on notification_log (sent_at);

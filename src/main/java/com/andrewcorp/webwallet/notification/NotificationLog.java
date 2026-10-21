package com.andrewcorp.webwallet.notification;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Remembers the notifications sent, so that a notification about the same thing is not sent again. */
@Component
public class NotificationLog {

    private final JdbcTemplate jdbc;

    public NotificationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean sent(Long userId, NotificationKind kind, String ref) {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification_log where user_id = ? and kind = ? and ref = ?",
                Integer.class,
                userId,
                kind.name(),
                ref);
        return count != null && count > 0;
    }

    public void markSent(Long userId, NotificationKind kind, String ref) {
        jdbc.update(
                "insert into notification_log (user_id, kind, ref) values (?, ?, ?) on conflict do nothing",
                userId,
                kind.name(),
                ref);
    }

    /** Forgets the notifications about one thing, e.g. a solved problem of a bank, so that a new one is sent. */
    public void forget(Long userId, NotificationKind kind, String ref) {
        jdbc.update(
                "delete from notification_log where user_id = ? and kind = ? and ref = ?", userId, kind.name(), ref);
    }

    public int deleteSentBefore(Instant moment) {
        return jdbc.update("delete from notification_log where sent_at < ?", Timestamp.from(moment));
    }
}

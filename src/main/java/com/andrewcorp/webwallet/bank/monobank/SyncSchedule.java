package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.SyncMode;
import com.andrewcorp.webwallet.common.util.Zones;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * When a connection is synchronized, in Kyiv time. {@link SyncMode#INTERVAL}: every 15 minutes from the start of the
 * window to its end, and once at night, halfway between the end of the window and its next start (03:30 for the
 * default 08:00–23:00). {@link SyncMode#DAILY} and {@link SyncMode#WEBHOOK}: once a day at the daily time.
 * {@link SyncMode#MANUAL}: never, the user asks for a synchronization.
 */
final class SyncSchedule {

    static final ZoneId ZONE = Zones.KYIV;
    static final Duration INTERVAL = Duration.ofMinutes(15);

    private SyncSchedule() {}

    static Instant next(BankConnection connection, Instant after) {
        return next(
                connection.getSyncMode(),
                connection.getWindowStart(),
                connection.getWindowEnd(),
                connection.getDailyAt(),
                after);
    }

    /** The first synchronization strictly after the moment; null when the connection is synchronized by hand only. */
    static Instant next(SyncMode mode, LocalTime windowStart, LocalTime windowEnd, LocalTime dailyAt, Instant after) {
        if (mode == SyncMode.MANUAL) {
            return null;
        }
        LocalDate today = after.atZone(ZONE).toLocalDate();
        Instant next = null;
        // The night run of the day before may fall on today
        for (LocalDate day = today.minusDays(1); !day.isAfter(today.plusDays(1)); day = day.plusDays(1)) {
            for (LocalDateTime run : runs(mode, windowStart, windowEnd, dailyAt, day)) {
                Instant instant = run.atZone(ZONE).toInstant();
                if (instant.isAfter(after) && (next == null || instant.isBefore(next))) {
                    next = instant;
                }
            }
        }
        return next;
    }

    private static List<LocalDateTime> runs(
            SyncMode mode, LocalTime windowStart, LocalTime windowEnd, LocalTime dailyAt, LocalDate day) {
        if (mode != SyncMode.INTERVAL) {
            return List.of(day.atTime(dailyAt));
        }
        List<LocalDateTime> runs = new ArrayList<>();
        LocalDateTime end = day.atTime(windowEnd);
        for (LocalDateTime run = day.atTime(windowStart); !run.isAfter(end); run = run.plus(INTERVAL)) {
            runs.add(run);
        }
        Duration night = Duration.ofDays(1).minus(Duration.between(windowStart, windowEnd));
        runs.add(end.plus(night.dividedBy(2)));
        return runs;
    }
}

package com.andrewcorp.webwallet.bank.monobank;

import static org.assertj.core.api.Assertions.assertThat;

import com.andrewcorp.webwallet.bank.SyncMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SyncScheduleTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);
    private static final LocalTime START = LocalTime.of(8, 0);
    private static final LocalTime END = LocalTime.of(23, 0);
    private static final LocalTime DAILY = LocalTime.of(6, 0);

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(SyncSchedule.ZONE).toInstant();
    }

    private static Instant interval(String after) {
        return SyncSchedule.next(SyncMode.INTERVAL, START, END, DAILY, at(DAY, after));
    }

    @Test
    void intervalRunsEveryFifteenMinutesWithinTheWindow() {
        assertThat(interval("10:07")).isEqualTo(at(DAY, "10:15"));
        assertThat(interval("10:15")).isEqualTo(at(DAY, "10:30"));
        assertThat(interval("07:59")).isEqualTo(at(DAY, "08:00"));
        assertThat(interval("22:59:59")).isEqualTo(at(DAY, "23:00"));
    }

    @Test
    void intervalRunsOnceAtNight() {
        // Halfway between 23:00 and 08:00
        assertThat(interval("23:00")).isEqualTo(at(DAY.plusDays(1), "03:30"));
        assertThat(interval("02:00")).isEqualTo(at(DAY, "03:30"));
        assertThat(interval("03:30")).isEqualTo(at(DAY, "08:00"));
    }

    @Test
    void customWindow() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(18, 0);
        assertThat(SyncSchedule.next(SyncMode.INTERVAL, start, end, DAILY, at(DAY, "20:00")))
                .isEqualTo(at(DAY.plusDays(1), "01:30"));
        // The slots start at the start of the window and do not pass its end
        LocalTime oddStart = LocalTime.of(8, 10);
        LocalTime oddEnd = LocalTime.of(8, 40);
        assertThat(SyncSchedule.next(SyncMode.INTERVAL, oddStart, oddEnd, DAILY, at(DAY, "08:11")))
                .isEqualTo(at(DAY, "08:25"));
        assertThat(SyncSchedule.next(SyncMode.INTERVAL, oddStart, oddEnd, DAILY, at(DAY, "08:30")))
                .isEqualTo(at(DAY, "08:40"));
    }

    @Test
    void dailyAndWebhookRunOnceADay() {
        assertThat(SyncSchedule.next(SyncMode.DAILY, START, END, DAILY, at(DAY, "05:00")))
                .isEqualTo(at(DAY, "06:00"));
        assertThat(SyncSchedule.next(SyncMode.DAILY, START, END, DAILY, at(DAY, "06:00")))
                .isEqualTo(at(DAY.plusDays(1), "06:00"));
        assertThat(SyncSchedule.next(SyncMode.WEBHOOK, START, END, LocalTime.of(21, 15), at(DAY, "10:00")))
                .isEqualTo(at(DAY, "21:15"));
    }

    @Test
    void daylightSavingTimeChange() {
        // Clocks go back from 04:00 to 03:00 on the last Sunday of October: the window keeps its local times
        LocalDate change = LocalDate.of(2026, 10, 25);
        assertThat(SyncSchedule.next(SyncMode.DAILY, START, END, DAILY, at(change, "05:00")))
                .isEqualTo(at(change, "06:00"));
        assertThat(SyncSchedule.next(SyncMode.INTERVAL, START, END, DAILY, at(change, "08:00")))
                .isEqualTo(at(change, "08:15"));
    }
}

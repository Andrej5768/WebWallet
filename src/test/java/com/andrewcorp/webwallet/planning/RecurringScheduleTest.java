package com.andrewcorp.webwallet.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurringScheduleTest {

    private static RecurringPayment recurring(Frequency frequency, int interval, String start, String end) {
        RecurringPayment recurring = new RecurringPayment(1L);
        recurring.setFrequency(frequency);
        recurring.setInterval(interval);
        recurring.setStartDate(LocalDate.parse(start));
        recurring.setEndDate(end != null ? LocalDate.parse(end) : null);
        return recurring;
    }

    private static LocalDate date(String date) {
        return LocalDate.parse(date);
    }

    @Test
    void keepsTheDayOfTheMonthAfterShortMonths() {
        RecurringPayment recurring = recurring(Frequency.MONTHLY, 1, "2026-01-31", null);

        assertThat(RecurringService.dates(recurring, date("2026-01-01"), date("2026-05-31")))
                .containsExactly(
                        date("2026-01-31"),
                        date("2026-02-28"),
                        date("2026-03-31"),
                        date("2026-04-30"),
                        date("2026-05-31"));
    }

    @Test
    void countsEveryIntervalFromTheStart() {
        RecurringPayment weekly = recurring(Frequency.WEEKLY, 2, "2026-10-05", null);
        assertThat(RecurringService.dates(weekly, date("2026-10-20"), date("2026-11-30")))
                .containsExactly(date("2026-11-02"), date("2026-11-16"), date("2026-11-30"));

        RecurringPayment yearly = recurring(Frequency.YEARLY, 1, "2024-02-29", null);
        assertThat(RecurringService.dates(yearly, date("2025-01-01"), date("2028-12-31")))
                .containsExactly(date("2025-02-28"), date("2026-02-28"), date("2027-02-28"), date("2028-02-29"));

        RecurringPayment daily = recurring(Frequency.DAILY, 3, "2026-10-01", null);
        assertThat(RecurringService.dates(daily, date("2026-10-05"), date("2026-10-12")))
                .containsExactly(date("2026-10-07"), date("2026-10-10"));
    }

    @Test
    void stopsAtTheEnd() {
        RecurringPayment recurring = recurring(Frequency.MONTHLY, 1, "2026-10-24", "2026-12-24");

        assertThat(RecurringService.dates(recurring, date("2026-01-01"), date("2027-12-31")))
                .containsExactly(date("2026-10-24"), date("2026-11-24"), date("2026-12-24"));
        assertThat(RecurringService.dates(recurring, date("2027-01-01"), date("2027-12-31")))
                .isEmpty();
    }

    @Test
    void doesNotSkipAnOccurrenceOfAnOldSeries() {
        RecurringPayment recurring = recurring(Frequency.MONTHLY, 1, "2010-01-05", null);

        assertThat(RecurringService.dates(recurring, date("2026-10-16"), date("2026-12-31")))
                .containsExactly(date("2026-11-05"), date("2026-12-05"));
    }
}

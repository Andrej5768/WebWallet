package com.andrewcorp.webwallet.planning;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Unit of the schedule of a recurring payment. */
public enum Frequency {
    DAILY(1),
    WEEKLY(7),
    MONTHLY(30),
    YEARLY(365);

    private final int approximateDays;

    Frequency(int approximateDays) {
        this.approximateDays = approximateDays;
    }

    /**
     * The date of the occurrence with the index, counted from the start, so that a payment on the 31st falls on the
     * last day of shorter months and comes back to the 31st after them.
     */
    public LocalDate occurrence(LocalDate start, int interval, long index) {
        long steps = index * interval;
        return switch (this) {
            case DAILY -> start.plusDays(steps);
            case WEEKLY -> start.plusWeeks(steps);
            case MONTHLY -> start.plusMonths(steps);
            case YEARLY -> start.plusYears(steps);
        };
    }

    /** Whole units between the dates, e.g. full months. */
    public long unitsBetween(LocalDate from, LocalDate to) {
        return switch (this) {
            case DAILY -> ChronoUnit.DAYS.between(from, to);
            case WEEKLY -> ChronoUnit.WEEKS.between(from, to);
            case MONTHLY -> ChronoUnit.MONTHS.between(from, to);
            case YEARLY -> ChronoUnit.YEARS.between(from, to);
        };
    }

    /** Length of one step of the schedule in days, roughly. */
    public int approximateDays(int interval) {
        return approximateDays * interval;
    }
}

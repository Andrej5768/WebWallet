package com.andrewcorp.webwallet.planning;

import java.time.LocalDate;

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

    /** Length of one step of the schedule in days, roughly. */
    public int approximateDays(int interval) {
        return approximateDays * interval;
    }
}

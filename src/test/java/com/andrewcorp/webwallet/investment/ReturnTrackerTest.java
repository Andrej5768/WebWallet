package com.andrewcorp.webwallet.investment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReturnTrackerTest {

    private static final LocalDate START = LocalDate.of(2029, 1, 1);

    private static void day(ReturnTracker tracker, int day, double value, double deposits) {
        tracker.add(START.plusDays(day), BigDecimal.valueOf(value), BigDecimal.valueOf(deposits));
    }

    @Test
    void equalsTheGrowthWithoutDeposits() {
        ReturnTracker tracker = new ReturnTracker();
        day(tracker, 0, 100, 100);
        day(tracker, 200, 120, 0);
        day(tracker, 365, 110, 0);

        assertThat(tracker.twr()).isEqualByComparingTo("0.1");
        assertThat(tracker.twrAnnualized()).isEqualByComparingTo("0.1");
        assertThat(tracker.xirr()).isEqualByComparingTo("0.1");
    }

    @Test
    void separatesTheInvestmentsFromTheTimingOfTheDeposits() {
        ReturnTracker tracker = new ReturnTracker();
        day(tracker, 0, 100, 0);
        day(tracker, 182, 105, 0);
        // 100 brought in at the start of the day, the value unchanged
        day(tracker, 183, 205, 100);
        day(tracker, 365, 215.25, 0);

        // 5 % in each half
        assertThat(tracker.twr()).isEqualByComparingTo("0.1025");
        // The second 100 earned for half a year only
        assertThat(tracker.xirr()).isEqualByComparingTo("0.102595");
    }

    @Test
    void skipsWhatCannotBeTold() {
        ReturnTracker empty = new ReturnTracker();
        assertThat(empty.twr()).isNull();
        assertThat(empty.xirr()).isNull();

        ReturnTracker month = new ReturnTracker();
        day(month, 0, 100, 100);
        day(month, 10, 101, 0);
        assertThat(month.twr()).isEqualByComparingTo("0.01");
        // Too short for an annual rate
        assertThat(month.xirr()).isNull();
        assertThat(month.twrAnnualized()).isNull();

        // Nothing on the accounts before the first deposit: the empty days do not count
        ReturnTracker late = new ReturnTracker();
        day(late, 0, 0, 0);
        day(late, 100, 50, 50);
        day(late, 465, 55, 0);
        assertThat(late.twr()).isEqualByComparingTo("0.1");
    }
}

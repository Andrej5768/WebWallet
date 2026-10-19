package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The returns of a series of daily values with the money brought in each day, both in one currency, fed day by day:
 *
 * <ul>
 *   <li>time-weighted (TWR): the daily returns chained, so the deposits and their timing do not count — how the
 *       investments did;
 *   <li>money-weighted (XIRR): the annual rate at which the value at the start and every deposit grow to the value at
 *       the end — how the investor did, timing included.
 * </ul>
 *
 * The value at the end of the first day is the starting stake; deposits are taken to arrive at the start of their day.
 */
final class ReturnTracker {

    /** Annual rates of shorter periods say little and grow huge. */
    static final int MIN_DAYS_FOR_XIRR = 30;

    private static final int SCALE = 6;

    private LocalDate first;
    private LocalDate last;
    private double previous;
    private double factor = 1;
    private boolean measured;
    private final List<Flow> flows = new ArrayList<>();

    private record Flow(LocalDate date, double amount) {}

    /**
     * @param value    at the end of the day
     * @param deposits brought in on the day, negative when taken away
     */
    void add(LocalDate day, BigDecimal value, BigDecimal deposits) {
        double v = value.doubleValue();
        double f = deposits.doubleValue();
        if (first == null) {
            first = day;
            flows.add(new Flow(day, -v));
        } else {
            if (previous > 0) {
                factor *= (v - f) / previous;
                measured = true;
            }
            if (f != 0) {
                flows.add(new Flow(day, -f));
            }
        }
        previous = v;
        last = day;
    }

    /** The time-weighted return over the days, e.g. 0.12 for 12 %; null before two days with value. */
    BigDecimal twr() {
        return measured ? round(factor - 1) : null;
    }

    /** The time-weighted return per year, for periods of a year or longer; null for shorter ones. */
    BigDecimal twrAnnualized() {
        long days = days();
        if (!measured || days < 365 || factor <= 0) {
            return null;
        }
        return round(Math.pow(factor, 365.0 / days) - 1);
    }

    /** The money-weighted annual return; null for short periods or when there is no answer. */
    BigDecimal xirr() {
        if (first == null || days() < MIN_DAYS_FOR_XIRR) {
            return null;
        }
        List<Flow> all = new ArrayList<>(flows);
        all.add(new Flow(last, previous));
        boolean out = all.stream().anyMatch(flow -> flow.amount() < 0);
        boolean in = all.stream().anyMatch(flow -> flow.amount() > 0);
        if (!out || !in) {
            return null;
        }
        Double rate = solve(all);
        return rate != null ? round(rate) : null;
    }

    private long days() {
        return first == null ? 0 : ChronoUnit.DAYS.between(first, last);
    }

    /** The rate where the value of the flows at the first day is zero: bisection, as the value falls with the rate. */
    private Double solve(List<Flow> all) {
        double low = -0.9999;
        double high = 10;
        double atLow = npv(all, low);
        double atHigh = npv(all, high);
        if (Double.isNaN(atLow) || Double.isNaN(atHigh) || Math.signum(atLow) == Math.signum(atHigh)) {
            return null;
        }
        for (int i = 0; i < 200 && high - low > 1e-10; i++) {
            double middle = (low + high) / 2;
            double atMiddle = npv(all, middle);
            if (Math.signum(atMiddle) == Math.signum(atLow)) {
                low = middle;
                atLow = atMiddle;
            } else {
                high = middle;
            }
        }
        return (low + high) / 2;
    }

    private double npv(List<Flow> all, double rate) {
        double sum = 0;
        for (Flow flow : all) {
            double years = ChronoUnit.DAYS.between(first, flow.date()) / 365.0;
            sum += flow.amount() / Math.pow(1 + rate, years);
        }
        return sum;
    }

    private static BigDecimal round(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP) : null;
    }
}

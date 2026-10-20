package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bonds held to maturity: their coupons and their value, the nominal plus the coupon accrued since the last payment.
 * Coupons are paid {@code couponFrequency} times a year (a divisor of 12) on the dates counted back from the maturity,
 * each date from the maturity itself, so a bond maturing on the 31st pays on the last day of shorter months. The dates
 * are computed, not stepped through, so a far maturity costs nothing.
 */
public final class BondValuation {

    /** Coupons a year that divide the year into whole months. */
    public static final Set<Integer> FREQUENCIES = Set.of(1, 2, 3, 4, 6, 12);

    private BondValuation() {}

    /** Whether the coupons of the bond are known: rate, frequency, maturity and nominal. */
    public static boolean hasCoupons(Instrument bond) {
        return bond.getNominal() != null
                && bond.getCouponRate() != null
                && bond.getCouponFrequency() != null
                && FREQUENCIES.contains(bond.getCouponFrequency())
                && bond.getMaturityDate() != null;
    }

    /** The coupon of one bond, in the currency of the bond. */
    public static BigDecimal coupon(Instrument bond) {
        return bond.getNominal()
                .multiply(bond.getCouponRate())
                .divide(BigDecimal.valueOf(100L * bond.getCouponFrequency()), 10, RoundingMode.HALF_UP);
    }

    /** The coupon dates in the period, both ends inclusive, oldest first; none without known coupons. */
    public static List<LocalDate> couponDates(Instrument bond, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        if (!hasCoupons(bond) || from.isAfter(to)) {
            return dates;
        }
        LocalDate maturity = bond.getMaturityDate();
        int months = months(bond);
        // Coupon k is paid k periods before the maturity; the range of k covering the dates, a step wider each way
        long latest = Math.max(0, ChronoUnit.MONTHS.between(to, maturity) / months - 1);
        long earliest = Math.max(0, ChronoUnit.MONTHS.between(from, maturity) / months + 1);
        for (long k = earliest; k >= latest; k--) {
            LocalDate date = maturity.minusMonths(k * months);
            if (!date.isBefore(from) && !date.isAfter(to)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** The price of one bond on the date, or the nominal when the coupon is not known. */
    public static BigDecimal price(Instrument bond, LocalDate date) {
        BigDecimal nominal = bond.getNominal();
        if (nominal == null) {
            return null;
        }
        if (!hasCoupons(bond) || !date.isBefore(bond.getMaturityDate())) {
            return nominal;
        }
        LocalDate maturity = bond.getMaturityDate();
        int months = months(bond);
        // The coupon period holding the date: previous <= date < next
        long k = ChronoUnit.MONTHS.between(date, maturity) / months;
        LocalDate next = maturity.minusMonths(k * months);
        while (!next.isAfter(date)) {
            k--;
            next = maturity.minusMonths(k * months);
        }
        LocalDate previous = maturity.minusMonths((k + 1) * months);
        while (previous.isAfter(date)) {
            k++;
            next = previous;
            previous = maturity.minusMonths((k + 1) * months);
        }
        long elapsed = ChronoUnit.DAYS.between(previous, date);
        long period = ChronoUnit.DAYS.between(previous, next);
        BigDecimal accrued = coupon(bond)
                .multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        return nominal.add(accrued);
    }

    private static int months(Instrument bond) {
        return 12 / bond.getCouponFrequency();
    }
}

package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Value of a bond held to maturity: the nominal plus the coupon accrued since the last payment. Coupons are paid
 * {@code couponFrequency} times a year on the dates counted back from the maturity.
 */
public final class BondValuation {

    private BondValuation() {}

    /** The price of one bond on the date, or the nominal when the coupon is not known. */
    public static BigDecimal price(Instrument bond, LocalDate date) {
        BigDecimal nominal = bond.getNominal();
        if (nominal == null) {
            return null;
        }
        if (bond.getCouponRate() == null
                || bond.getCouponFrequency() == null
                || bond.getMaturityDate() == null
                || !date.isBefore(bond.getMaturityDate())) {
            return nominal;
        }
        int months = 12 / bond.getCouponFrequency();
        LocalDate next = bond.getMaturityDate();
        LocalDate previous = next.minusMonths(months);
        for (int step = 1; previous.isAfter(date); step++) {
            next = bond.getMaturityDate().minusMonths((long) months * step);
            previous = bond.getMaturityDate().minusMonths((long) months * (step + 1));
        }
        long elapsed = ChronoUnit.DAYS.between(previous, date);
        long period = ChronoUnit.DAYS.between(previous, next);
        BigDecimal coupon = nominal.multiply(bond.getCouponRate())
                .divide(BigDecimal.valueOf(100L * bond.getCouponFrequency()), 10, RoundingMode.HALF_UP);
        BigDecimal accrued = coupon.multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        return nominal.add(accrued);
    }
}

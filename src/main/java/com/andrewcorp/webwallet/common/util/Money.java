package com.andrewcorp.webwallet.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small helpers for amounts, so that rounding and shares work the same everywhere. */
public final class Money {

    private Money() {}

    /** The amount, zero for none (e.g. a conversion without a rate). */
    public static BigDecimal orZero(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    /** Rounded to cents, as amounts are shown. */
    public static BigDecimal round2(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Rounded to the scale of the amounts stored in the database. */
    public static BigDecimal round4(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    /** {@code part / whole} with 4 decimals, null when the whole is missing or zero. */
    public static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.divide(whole, 4, RoundingMode.HALF_UP);
    }

    /** {@code part / whole} with 4 decimals, null when the whole is not positive (e.g. a rate of no income). */
    public static BigDecimal positiveShare(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() <= 0 ? null : part.divide(whole, 4, RoundingMode.HALF_UP);
    }
}

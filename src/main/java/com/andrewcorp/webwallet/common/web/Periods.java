package com.andrewcorp.webwallet.common.web;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Checks of the periods requested from the API: in the plausible dates, the start not after the end, and not longer
 * than a limit, so that one request cannot make the server loop over millennia.
 */
public final class Periods {

    private Periods() {}

    /** A period of whole days, both ends inclusive, at most {@code maxDays} days long. */
    public static void checkDays(LocalDate from, LocalDate to, long maxDays, String field) {
        checkRange(from, to, field);
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw ApiException.invalid(field, "error.period.tooManyDays", maxDays);
        }
    }

    /** A period of whole days, both ends inclusive, at most {@code maxYears} years long. */
    public static void checkYears(LocalDate from, LocalDate to, int maxYears, String field) {
        checkRange(from, to, field);
        if (from.isBefore(to.minusYears(maxYears))) {
            throw ApiException.invalid(field, "error.period.tooManyYears", maxYears);
        }
    }

    /** A period of whole months, both ends inclusive, at most {@code maxMonths} months long. */
    public static void checkMonths(YearMonth from, YearMonth to, int maxMonths, String field) {
        checkRange(from.atDay(1), to.atEndOfMonth(), field);
        if (ChronoUnit.MONTHS.between(from, to) + 1 > maxMonths) {
            throw ApiException.invalid(field, "error.period.tooManyMonths", maxMonths);
        }
    }

    /** A date in the range the ledger accepts. */
    public static void checkDate(LocalDate date, String field) {
        if (date.isBefore(Transaction.MIN_DATE) || date.isAfter(Transaction.MAX_DATE)) {
            throw ApiException.invalid(field, "error.transaction.date");
        }
    }

    private static void checkRange(LocalDate from, LocalDate to, String field) {
        checkDate(from, "from");
        checkDate(to, "to");
        if (from.isAfter(to)) {
            throw ApiException.invalid(field, "error.period.order");
        }
    }
}

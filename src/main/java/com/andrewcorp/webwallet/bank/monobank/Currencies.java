package com.andrewcorp.webwallet.bank.monobank;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Banks use ISO 4217 numeric codes and amounts in minor units.
 */
final class Currencies {

    private static final Map<Integer, String> BY_NUMERIC_CODE = Currency.getAvailableCurrencies().stream()
            .filter(currency -> currency.getNumericCode() > 0)
            .collect(Collectors.toMap(Currency::getNumericCode, Currency::getCurrencyCode, (first, second) -> first));

    private Currencies() {
    }

    /** ISO alphabetic code, or null for an unknown numeric code. */
    static String byNumericCode(int numericCode) {
        return BY_NUMERIC_CODE.get(numericCode);
    }

    static BigDecimal fromMinorUnits(long minorUnits, String currencyCode) {
        int digits = Math.max(Currency.getInstance(currencyCode).getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(minorUnits, digits);
    }
}

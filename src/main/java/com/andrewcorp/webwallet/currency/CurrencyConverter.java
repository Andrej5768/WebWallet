package com.andrewcorp.webwallet.currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts many amounts into one target currency, looking up each rate only once. Create one per request.
 */
public class CurrencyConverter {

    private final ExchangeRateService rates;
    private final String target;
    private final Map<String, BigDecimal> cache = new HashMap<>();

    CurrencyConverter(ExchangeRateService rates, String target) {
        this.rates = rates;
        this.target = target;
    }

    public String target() {
        return target;
    }

    /** The amount in the target currency at the rates of the date, rounded to 4 decimal places. */
    public BigDecimal convert(BigDecimal amount, String currency, LocalDate date) {
        if (currency.equals(target) || amount.signum() == 0) {
            return amount;
        }
        BigDecimal uah = amount.multiply(rate(currency, date));
        return uah.divide(rate(target, date), MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_EVEN);
    }

    private BigDecimal rate(String currency, LocalDate date) {
        return cache.computeIfAbsent(currency + "|" + date, key -> rates.rate(currency, date));
    }
}

package com.andrewcorp.webwallet.currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Converts many amounts into one target currency, looking up each rate only once. Create one per request. Amounts
 * in a currency without a known rate are left out (converted to zero) and reported by {@link #unconverted()}.
 */
public class CurrencyConverter {

    private final ExchangeRateService rates;
    private final String target;
    private final Map<String, Optional<BigDecimal>> cache = new HashMap<>();
    private final Set<String> unconverted = new TreeSet<>();

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
        Optional<BigDecimal> from = rate(currency, date);
        Optional<BigDecimal> to = rate(target, date);
        if (from.isEmpty() || to.isEmpty()) {
            unconverted.add(from.isEmpty() ? currency : target);
            return BigDecimal.ZERO;
        }
        return amount.multiply(from.get())
                .divide(to.get(), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Currencies whose amounts were left out for lack of an exchange rate, sorted. */
    public List<String> unconverted() {
        return List.copyOf(unconverted);
    }

    private Optional<BigDecimal> rate(String currency, LocalDate date) {
        return cache.computeIfAbsent(currency + "|" + date, key -> rates.findRate(currency, date));
    }
}

package com.andrewcorp.webwallet.currency;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Converts many amounts into one target currency. Create one per request: it reads the stored rates of each
 * currency once and asks the NBU only a few times per currency, however many days the amounts span. Amounts in a
 * currency without a known rate are left out (converted to zero) and reported by {@link #unconverted()}.
 */
public class CurrencyConverter {

    /** NBU does not publish rates on some days; an older rate is used for up to this many days. */
    private static final int MAX_RATE_AGE_DAYS = 7;
    /** Each load covers about a year, so this bounds the NBU requests of one conversion of a long history. */
    private static final int MAX_LOADS_PER_CURRENCY = 10;
    /** Bounds the NBU requests of one conversion over all currencies, e.g. of accounts in many currencies. */
    private static final int MAX_LOADS = 20;

    private final ExchangeRateService rates;
    private final String target;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> stored = new HashMap<>();
    private final Map<String, Integer> loads = new HashMap<>();
    private int totalLoads;
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
        BigDecimal converted = convertIfKnown(amount, currency, date);
        return converted != null ? converted : BigDecimal.ZERO;
    }

    /** Like {@link #convert}, but null instead of zero when a rate is missing. */
    public BigDecimal convertIfKnown(BigDecimal amount, String currency, LocalDate date) {
        if (currency.equals(target) || amount.signum() == 0) {
            return amount;
        }
        Optional<BigDecimal> from = rate(currency, date);
        Optional<BigDecimal> to = rate(target, date);
        if (from.isEmpty() || to.isEmpty()) {
            unconverted.add(from.isEmpty() ? currency : target);
            return null;
        }
        return amount.multiply(from.get())
                .divide(to.get(), MathContext.DECIMAL64)
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Currencies whose amounts were left out for lack of an exchange rate, sorted. */
    public List<String> unconverted() {
        return List.copyOf(unconverted);
    }

    /**
     * UAH per one unit of the currency on the date (today for a future date), or the latest earlier rate. When no
     * recent rate is stored, the NBU is asked; if it has none, an older rate is better than none.
     */
    Optional<BigDecimal> rate(String currency, LocalDate date) {
        if (ExchangeRateService.UAH.equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        LocalDate today = rates.today();
        LocalDate day = date.isAfter(today) ? today : date;
        NavigableMap<LocalDate, BigDecimal> known = stored.computeIfAbsent(currency, rates::storedRates);
        Map.Entry<LocalDate, BigDecimal> rate = known.floorEntry(day);
        boolean recent = rate != null && ChronoUnit.DAYS.between(rate.getKey(), day) <= MAX_RATE_AGE_DAYS;
        if (!recent && mayLoad(currency) && rates.loadFrom(currency, day)) {
            known = rates.storedRates(currency);
            stored.put(currency, known);
            rate = known.floorEntry(day);
        }
        return Optional.ofNullable(rate).map(Map.Entry::getValue);
    }

    private boolean mayLoad(String currency) {
        if (totalLoads >= MAX_LOADS || loads.merge(currency, 1, Integer::sum) > MAX_LOADS_PER_CURRENCY) {
            return false;
        }
        totalLoads++;
        return true;
    }
}

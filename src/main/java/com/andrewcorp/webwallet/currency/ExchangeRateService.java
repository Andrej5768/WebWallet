package com.andrewcorp.webwallet.currency;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Converts amounts between currencies with the official NBU rates. Rates are cached in the database and loaded from
 * the NBU on demand.
 */
@Service
public class ExchangeRateService {

    public static final String UAH = "UAH";

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    /** NBU does not publish rates on some days; an older rate is used for up to this many days. */
    private static final int MAX_RATE_AGE_DAYS = 7;

    private static final int HISTORY_DAYS_TO_LOAD = 31;
    /** How long the NBU is not asked again for a rate it did not have, e.g. of a currency it does not publish. */
    private static final Duration MISSING_RATE_TTL = Duration.ofMinutes(15);

    private final ExchangeRateRepository rates;
    private final NbuClient nbu;
    private final Clock clock;
    /** Loaded rates are stored in a transaction of their own: the caller's may be read-only or fail later. */
    private final TransactionTemplate newTransaction;

    private final Cache<String, Boolean> missingRates = Caffeine.newBuilder()
            .expireAfterWrite(MISSING_RATE_TTL)
            .maximumSize(10_000)
            .build();

    public ExchangeRateService(
            ExchangeRateRepository rates, NbuClient nbu, Clock clock, PlatformTransactionManager transactionManager) {
        this.rates = rates;
        this.nbu = nbu;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A converter for many amounts into one currency within one request. */
    public CurrencyConverter converterTo(String currency) {
        return new CurrencyConverter(this, currency);
    }

    /** Converts with the rates of the date, rounded to 4 decimal places. */
    @Transactional
    public BigDecimal convert(BigDecimal amount, String from, String to, LocalDate date) {
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal uah = amount.multiply(rate(from, date));
        return uah.divide(rate(to, date), MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** UAH per one unit of the currency on the date, see {@link #findRate}. */
    @Transactional
    public BigDecimal rate(String currency, LocalDate date) {
        return findRate(currency, date).orElseThrow(() -> new ApiException(ErrorCode.RATE_UNAVAILABLE));
    }

    /**
     * UAH per one unit of the currency on the date, or the latest earlier rate. When no recent rate is known and the
     * NBU is not reachable, an older rate is used rather than failing. Empty for a currency without any rate.
     */
    @Transactional
    public Optional<BigDecimal> findRate(String currency, LocalDate date) {
        if (UAH.equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        LocalDate effectiveDate = date.isAfter(today()) ? today() : date;
        return usableRate(currency, effectiveDate)
                .or(() -> {
                    String key = currency + "|" + effectiveDate;
                    if (missingRates.getIfPresent(key) != null) {
                        return Optional.empty();
                    }
                    load(currency, effectiveDate);
                    Optional<ExchangeRate> loaded = usableRate(currency, effectiveDate);
                    if (loaded.isEmpty()) {
                        missingRates.put(key, true);
                    }
                    return loaded;
                })
                .or(() -> rates.findFirstByCurrencyAndDateLessThanEqualOrderByDateDesc(currency, effectiveDate))
                .map(ExchangeRate::getRate);
    }

    /** Loads the rates of all currencies for today. */
    public int loadToday() {
        List<ExchangeRate> loaded = nbu.ratesOn(today());
        store(loaded);
        return loaded.size();
    }

    /** Forgets which rates the NBU did not have, so that they are requested again. */
    public void forgetMissingRates() {
        missingRates.invalidateAll();
    }

    private Optional<ExchangeRate> usableRate(String currency, LocalDate date) {
        return rates.findFirstByCurrencyAndDateLessThanEqualOrderByDateDesc(currency, date)
                .filter(rate -> ChronoUnit.DAYS.between(rate.getDate(), date) <= MAX_RATE_AGE_DAYS);
    }

    private void load(String currency, LocalDate date) {
        try {
            store(nbu.history(currency, date.minusDays(HISTORY_DAYS_TO_LOAD), date));
        } catch (RestClientException e) {
            log.warn("Failed to load {} rates for {} from NBU", currency, date, e);
        }
    }

    private void store(List<ExchangeRate> loaded) {
        newTransaction.executeWithoutResult(status ->
                loaded.forEach(rate -> rates.insertIfAbsent(rate.getCurrency(), rate.getDate(), rate.getRate())));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}

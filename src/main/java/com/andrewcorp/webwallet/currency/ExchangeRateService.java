package com.andrewcorp.webwallet.currency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    /** Days before the requested date that are loaded too, so that the latest earlier rate is there. */
    private static final int DAYS_BEFORE_TO_LOAD = 31;
    /** One NBU request loads about a year of daily rates. */
    private static final int DAYS_TO_LOAD = 366;
    /** How long the NBU is not asked again for rates it did not have, e.g. of a currency it does not publish. */
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

    /** UAH per one unit of the currency on the date, see {@link CurrencyConverter#rate}. */
    public Optional<BigDecimal> findRate(String currency, LocalDate date) {
        return converterTo(UAH).rate(currency, date);
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

    /** All stored rates of the currency by date. */
    NavigableMap<LocalDate, BigDecimal> storedRates(String currency) {
        NavigableMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
        rates.findRatesOf(currency).forEach(rate -> byDate.put(rate.getDate(), rate.getRate()));
        return byDate;
    }

    /**
     * Loads about a year of rates of the currency from the NBU, starting a month before the date.
     *
     * @return false when the NBU had none or is not reachable; it is then not asked again for a while
     */
    boolean loadFrom(String currency, LocalDate date) {
        // Per currency, not per date: otherwise every other date of a currency the NBU does not publish, or of a
        // period without rates, would be another request
        if (missingRates.getIfPresent(currency) != null) {
            return false;
        }
        LocalDate until = date.plusDays(DAYS_TO_LOAD);
        List<ExchangeRate> loaded;
        try {
            loaded = nbu.history(
                    currency, date.minusDays(DAYS_BEFORE_TO_LOAD), until.isAfter(today()) ? today() : until);
        } catch (RestClientException e) {
            log.warn("Failed to load {} rates for {} from NBU", currency, date, e);
            loaded = List.of();
        }
        if (loaded.isEmpty()) {
            missingRates.put(currency, true);
            return false;
        }
        store(loaded);
        return true;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    private void store(List<ExchangeRate> loaded) {
        newTransaction.executeWithoutResult(status ->
                loaded.forEach(rate -> rates.insertIfAbsent(rate.getCurrency(), rate.getDate(), rate.getRate())));
    }
}

package com.andrewcorp.webwallet.currency;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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

    private final ExchangeRateRepository rates;
    private final NbuClient nbu;
    private final Clock clock;

    public ExchangeRateService(ExchangeRateRepository rates, NbuClient nbu, Clock clock) {
        this.rates = rates;
        this.nbu = nbu;
        this.clock = clock;
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

    /** UAH per one unit of the currency on the date, or the latest earlier rate. */
    @Transactional
    public BigDecimal rate(String currency, LocalDate date) {
        if (UAH.equals(currency)) {
            return BigDecimal.ONE;
        }
        LocalDate effectiveDate = date.isAfter(today()) ? today() : date;
        return usableRate(currency, effectiveDate)
                .or(() -> {
                    load(currency, effectiveDate);
                    return usableRate(currency, effectiveDate);
                })
                .map(ExchangeRate::getRate)
                .orElseThrow(() -> new ApiException(ErrorCode.RATE_UNAVAILABLE));
    }

    /** Loads the rates of all currencies for today. */
    @Transactional
    public int loadToday() {
        List<ExchangeRate> loaded = nbu.ratesOn(today());
        rates.saveAll(loaded);
        return loaded.size();
    }

    private Optional<ExchangeRate> usableRate(String currency, LocalDate date) {
        return rates.findFirstByCurrencyAndDateLessThanEqualOrderByDateDesc(currency, date)
                .filter(rate -> ChronoUnit.DAYS.between(rate.getDate(), date) <= MAX_RATE_AGE_DAYS);
    }

    private void load(String currency, LocalDate date) {
        try {
            rates.saveAll(nbu.history(currency, date.minusDays(HISTORY_DAYS_TO_LOAD), date));
        } catch (RestClientException e) {
            log.warn("Failed to load {} rates for {} from NBU", currency, date, e);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}

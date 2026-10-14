package com.andrewcorp.webwallet.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;

class ExchangeRateServiceTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    @Autowired
    private ExchangeRateService service;

    @Autowired
    private ExchangeRateRepository rates;

    @Autowired
    private ExchangeRateTask task;

    @Test
    void uahRateIsOne() {
        assertThat(service.findRate("UAH", DATE).orElseThrow()).isEqualByComparingTo("1");
        assertThat(service.converterTo("USD").convert(new BigDecimal("10"), "USD", DATE))
                .isEqualByComparingTo("10");
    }

    @Test
    void usesStoredRateOfTheDateOrAFewDaysBefore() {
        rates.save(new ExchangeRate("USD", DATE.minusDays(2), new BigDecimal("41.5")));

        assertThat(service.findRate("USD", DATE).orElseThrow()).isEqualByComparingTo("41.5");
        verify(nbuClient, never()).history(any(), any(), any());
    }

    @Test
    void loadsHistoryWhenRateIsMissingOrTooOld() {
        rates.save(new ExchangeRate("EUR", DATE.minusDays(30), new BigDecimal("40")));
        when(nbuClient.history(eq("EUR"), any(), any()))
                .thenReturn(List.of(
                        new ExchangeRate("EUR", DATE.minusDays(1), new BigDecimal("48.1")),
                        new ExchangeRate("EUR", DATE, new BigDecimal("48.2"))));

        assertThat(service.findRate("EUR", DATE).orElseThrow()).isEqualByComparingTo("48.2");
        assertThat(rates.count()).isEqualTo(3);
    }

    @Test
    void convertsThroughUah() {
        rates.save(new ExchangeRate("USD", DATE, new BigDecimal("41.5")));
        rates.save(new ExchangeRate("EUR", DATE, new BigDecimal("48")));

        assertThat(service.converterTo("UAH").convert(new BigDecimal("100"), "USD", DATE))
                .isEqualByComparingTo("4150");
        assertThat(service.converterTo("USD").convert(new BigDecimal("4150"), "UAH", DATE))
                .isEqualByComparingTo("100");
        assertThat(service.converterTo("EUR").convert(new BigDecimal("100"), "USD", DATE))
                .isEqualByComparingTo("86.4583");
    }

    @Test
    void oldRateIsUsedWhenNbuIsNotReachable() {
        rates.save(new ExchangeRate("GBP", DATE.minusDays(60), new BigDecimal("55")));
        when(nbuClient.history(any(), any(), any())).thenThrow(new ResourceAccessException("NBU is down"));

        assertThat(service.findRate("GBP", DATE).orElseThrow()).isEqualByComparingTo("55");
    }

    @Test
    void futureDateUsesTodaysRate() {
        LocalDate today = LocalDate.now();
        rates.save(new ExchangeRate("USD", today, new BigDecimal("42")));

        assertThat(service.findRate("USD", today.plusDays(10)).orElseThrow()).isEqualByComparingTo("42");
    }

    @Test
    void missingRateIsReported() {
        when(nbuClient.history(any(), any(), any())).thenThrow(new ResourceAccessException("NBU is down"));

        assertThat(service.findRate("PLN", DATE)).isEmpty();
    }

    @Test
    void converterLeavesOutCurrenciesWithoutRateAndAsksNbuOnce() {
        rates.save(new ExchangeRate("USD", DATE, new BigDecimal("41.5")));
        CurrencyConverter converter = service.converterTo("UAH");

        assertThat(converter.convert(new BigDecimal("10"), "XAU", DATE)).isEqualByComparingTo("0");
        assertThat(converter.convert(new BigDecimal("10"), "USD", DATE)).isEqualByComparingTo("415");
        assertThat(converter.unconverted()).containsExactly("XAU");
        // Another request does not ask the NBU again for a rate it did not have
        assertThat(service.converterTo("UAH").convert(BigDecimal.ONE, "XAU", DATE))
                .isEqualByComparingTo("0");
        verify(nbuClient, times(1)).history(eq("XAU"), any(), any());
    }

    @Test
    void concurrentlyLoadedRatesDoNotConflict() {
        rates.save(new ExchangeRate("CHF", DATE, new BigDecimal("50")));
        when(nbuClient.history(eq("CHF"), any(), any()))
                .thenReturn(List.of(
                        new ExchangeRate("CHF", DATE, new BigDecimal("50")),
                        new ExchangeRate("CHF", DATE.plusDays(10), new BigDecimal("51"))));

        // The stored rate is too old for that date, so the history is loaded; one of its rates is already stored
        assertThat(service.findRate("CHF", DATE.plusDays(10)).orElseThrow()).isEqualByComparingTo("51");
        assertThat(rates.count()).isEqualTo(2);
    }

    @Test
    void dailyTaskStoresTodaysRates() {
        when(nbuClient.ratesOn(any()))
                .thenReturn(List.of(
                        new ExchangeRate("USD", LocalDate.now(), new BigDecimal("42")),
                        new ExchangeRate("EUR", LocalDate.now(), new BigDecimal("49"))));

        task.loadToday();

        assertThat(rates.count()).isEqualTo(2);
    }

    @Test
    void dailyTaskSurvivesNbuErrors() {
        when(nbuClient.ratesOn(any())).thenThrow(new ResourceAccessException("NBU is down"));

        task.loadToday();

        assertThat(rates.count()).isZero();
    }
}

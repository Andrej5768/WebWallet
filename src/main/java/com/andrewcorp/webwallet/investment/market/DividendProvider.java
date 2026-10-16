package com.andrewcorp.webwallet.investment.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source of the dividends of instruments: paid and declared ones. The implementation is chosen with
 * {@code app.market-data.dividends-provider} ({@code alpha-vantage} by default); another service is added as another
 * implementation.
 */
public interface DividendProvider {

    /** Whether the provider is configured. */
    boolean enabled();

    /** Dividends per share of the symbol (a US listing), declared future ones included. */
    List<Dividend> dividends(String symbol);

    /** Dates the provider does not know are null. */
    record Dividend(
            LocalDate exDate,
            LocalDate paymentDate,
            LocalDate declarationDate,
            LocalDate recordDate,
            BigDecimal amount) {}
}

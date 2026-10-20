package com.andrewcorp.webwallet.investment.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source of instruments and their daily closing prices. The implementation is chosen with
 * {@code app.market-data.prices-provider} ({@code twelve-data} by default); another service is added as another
 * implementation. Instruments are identified by the ticker and the ISO 10383 market identifier code of the exchange,
 * which other services understand as well.
 */
public interface MarketDataProvider {

    /** Whether the provider is configured; without it instruments are entered and valued by hand. */
    boolean enabled();

    /** Instruments matching a ticker, a name or an ISIN. */
    List<Listing> search(String query);

    /** Daily closes of the instrument in the period, both ends inclusive, oldest first. */
    List<DailyClose> closes(String symbol, String micCode, LocalDate from, LocalDate to);

    /**
     * @param type    the kind of the instrument as the provider names it, e.g. "Common Stock" or "ETF"
     * @param micCode the market identifier code of the exchange
     */
    record Listing(
            String symbol,
            String name,
            String exchange,
            String micCode,
            String country,
            String currency,
            String type) {}

    record DailyClose(LocalDate date, BigDecimal close) {}
}

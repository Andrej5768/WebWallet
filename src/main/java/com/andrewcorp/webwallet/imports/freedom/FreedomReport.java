package com.andrewcorp.webwallet.imports.freedom;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The reports of Freedom Finance (Tradernet, Freedom24) as exported to XLSX: the trades and the cash flows. Either
 * may be missing; the cash flows give the currency of the fees of the trades.
 */
public record FreedomReport(List<Trade> trades, List<CashFlow> cashFlows) {

    /**
     * A row of the trades report.
     *
     * @param ticker   e.g. {@code SCHD.US}, {@code FXUS} (Moscow exchange), or a currency pair such as {@code EUR/USD}
     * @param buy      buy or sell; of a currency pair, buying or selling the first currency for the second
     * @param amount   quantity times price, in the currency of the trade (of a pair, in the second currency)
     * @param fee      in a currency the report does not give
     */
    public record Trade(
            long number,
            LocalDateTime time,
            String ticker,
            boolean buy,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal fee) {}

    /**
     * A row of the cash flows report.
     *
     * @param kind    the "Direction" column, trimmed, e.g. {@code Dividends}, {@code Trading fee}, {@code Card payment}
     * @param amount  signed: positive brings money
     */
    public record CashFlow(
            long number, LocalDateTime time, String kind, String comment, BigDecimal amount, String currency) {}
}

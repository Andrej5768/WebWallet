package com.andrewcorp.webwallet.imports.broker;

import com.andrewcorp.webwallet.investment.OperationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The operations of the reports of a broker as they would be imported to one brokerage account.
 *
 * @param broker   the name of a new account for them
 * @param currency the currency of a new account: the one most money moved in
 * @param merged   rows of the reports folded into other operations: taxes into dividends, fees into trades
 */
public record BrokerStatement(
        String broker, String currency, List<BrokerOperation> operations, List<BrokerTicker> tickers, int merged) {

    /** Why a row of a report is shown but not imported. */
    public enum Skip {
        /** A row that moves no money, e.g. blocking and unblocking a withdrawal. */
        SERVICE,
        /** A row the import does not recognize. */
        UNKNOWN
    }

    /**
     * An operation of the reports.
     *
     * @param key         unique in the reports of the broker, kept as the external id of the operation
     * @param type        null for a skipped row
     * @param ticker      the instrument as the report names it; null for money movements and fees
     * @param description what the report says, e.g. the comment of a fee
     * @param skip        non-null: shown, not imported
     */
    public record BrokerOperation(
            String key,
            LocalDateTime time,
            @Schema(nullable = true) OperationType type,
            @Schema(nullable = true) String ticker,
            @Schema(nullable = true) BigDecimal quantity,
            @Schema(nullable = true) BigDecimal price,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal tax,
            String currency,
            @Schema(nullable = true) BigDecimal toAmount,
            @Schema(nullable = true) String toCurrency,
            String description,
            @Schema(nullable = true) Skip skip) {}

    /**
     * An instrument as the reports name it.
     *
     * @param symbol   the ticker without the suffix of its market, e.g. {@code SCHD} of {@code SCHD.US}
     * @param market   {@code US}, {@code EU}, {@code KZ}, {@code RU}, or null when unknown
     * @param currency of its trades
     */
    public record BrokerTicker(
            String ticker,
            String symbol,
            @Schema(nullable = true) String market,
            String currency) {}
}

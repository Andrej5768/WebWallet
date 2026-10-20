package com.andrewcorp.webwallet.imports.broker;

import com.andrewcorp.webwallet.imports.broker.BrokerStatement.Skip;
import com.andrewcorp.webwallet.imports.preview.ImportSource;
import com.andrewcorp.webwallet.investment.InstrumentType;
import com.andrewcorp.webwallet.investment.OperationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BrokerImportDtos {

    private BrokerImportDtos() {}

    /**
     * The reports of a broker as they would be imported; nothing is saved until the confirmation.
     *
     * @param id          to confirm the import within an hour, until {@code expiresAt}
     * @param account     the brokerage account the operations go to
     * @param from        the first day of the reports; null when they have no rows
     * @param instruments the instruments of the reports and what they become
     * @param operations  every row of the reports, newest first; skipped and duplicate ones are not imported
     * @param cash        the money on the account after the import, per currency
     * @param merged      rows folded into other operations: taxes into dividends, fees into trades
     * @param providerUnavailable the market data provider did not answer: the tickers it would have found are
     *                    suggested to be created by hand
     */
    public record BrokerPreviewResponse(
            UUID id,
            ImportSource source,
            Instant expiresAt,
            PreviewAccount account,
            @Schema(nullable = true) LocalDate from,
            @Schema(nullable = true) LocalDate to,
            List<TickerPreview> instruments,
            List<OperationPreview> operations,
            List<CashPreview> cash,
            int merged,
            boolean providerUnavailable) {}

    /** @param id null when the import creates the account */
    public record PreviewAccount(@Schema(nullable = true) Long id, String name, String currency) {}

    /**
     * An instrument of the reports.
     *
     * @param ticker        as the reports name it, e.g. {@code SCHD.US}
     * @param currency      of its trades
     * @param suggestion    what it becomes unless the confirmation chooses otherwise
     * @param quantityAfter held on the account after the import
     * @param shortfall     the reports sell more than they buy before: the earlier history is missing
     */
    public record TickerPreview(
            String ticker, String currency, InstrumentChoice suggestion, BigDecimal quantityAfter, boolean shortfall) {}

    /**
     * An instrument for a ticker: a known one ({@code instrumentId}), a market one to add ({@code symbol} and
     * {@code micCode}), or one to create by hand ({@code manual}) with the symbol, valued by hand.
     */
    public record InstrumentChoice(
            @Schema(nullable = true) Long instrumentId,
            String symbol,
            @Schema(nullable = true) String micCode,
            String name,
            InstrumentType type,
            String currency,
            @Schema(nullable = true) String exchange,
            boolean manual) {}

    public enum OperationStatus {
        NEW,
        /** Imported before: skipped. */
        DUPLICATE,
        /** Moves no money or is not recognized: shown, not imported. */
        SKIPPED
    }

    /**
     * A row of the reports.
     *
     * @param key   to leave the operation out of the import
     * @param cash  how it changes the money of the account in {@code currency}
     * @param skip  why a skipped row is skipped
     */
    public record OperationPreview(
            String key,
            LocalDate date,
            @Schema(nullable = true) OperationType type,
            @Schema(nullable = true) String ticker,
            @Schema(nullable = true) BigDecimal quantity,
            @Schema(nullable = true) BigDecimal price,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal tax,
            String currency,
            BigDecimal cash,
            @Schema(nullable = true) BigDecimal toAmount,
            @Schema(nullable = true) String toCurrency,
            String description,
            OperationStatus status,
            @Schema(nullable = true) Skip skip) {}

    public record CashPreview(String currency, BigDecimal amount) {}

    /**
     * @param instruments other instruments than the suggested ones, per ticker
     * @param excluded    keys of operations to leave out
     */
    public record BrokerConfirmRequest(
            @Size(max = 1000) List<@Valid TickerChoice> instruments,
            @Size(max = 100_000) List<@NotBlank @Size(max = 200) String> excluded) {}

    /**
     * The instrument for a ticker: {@code instrumentId} of a known one, or {@code symbol} (with {@code micCode}) of a
     * market one found by a search, or {@code manual} to create one by hand.
     */
    public record TickerChoice(
            @NotBlank @Size(max = 50) String ticker,
            Long instrumentId,
            @Size(max = 30) String symbol,
            @Size(max = 10) String micCode,
            Boolean manual) {}

    /**
     * @param instrumentsCreated instruments created by hand for tickers the provider does not know
     * @param duplicates         operations imported before, skipped
     */
    public record BrokerImportResult(
            Long accountId, boolean accountCreated, int instrumentsCreated, int operationsImported, int duplicates) {}
}

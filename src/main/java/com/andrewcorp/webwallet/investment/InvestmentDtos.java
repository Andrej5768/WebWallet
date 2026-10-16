package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InvestmentDtos {

    private InvestmentDtos() {}

    /**
     * @param own     entered by the user (valued by nominal or by hand) rather than a market instrument
     * @param price   the latest known price per unit in the currency of the instrument, null when none is known yet
     */
    public record InstrumentResponse(
            Long id,
            String symbol,
            String name,
            InstrumentType type,
            String currency,
            @Schema(nullable = true) String exchange,
            @Schema(nullable = true) String micCode,
            @Schema(nullable = true) String isin,
            Valuation valuation,
            boolean own,
            @Schema(nullable = true) BigDecimal nominal,
            @Schema(nullable = true) BigDecimal couponRate,
            @Schema(nullable = true) Integer couponFrequency,
            @Schema(nullable = true) LocalDate maturityDate,
            @Schema(nullable = true) BigDecimal price,
            @Schema(nullable = true) LocalDate priceDate) {}

    /**
     * An instrument found by a search: one already known (with {@code id}) or one of the market data provider, which
     * is added with {@code POST /api/v1/investments/instruments/market}.
     */
    public record SearchResult(
            @Schema(nullable = true) Long id,
            String symbol,
            String name,
            InstrumentType type,
            String currency,
            @Schema(nullable = true) String exchange,
            @Schema(nullable = true) String micCode,
            @Schema(nullable = true) String country) {}

    /**
     * @param providerEnabled whether the market data provider is configured; without it only own instruments are
     *                        found and prices are entered by hand
     */
    public record SearchResponse(boolean providerEnabled, List<SearchResult> results) {}

    /** A market instrument found by a search, identified by its symbol and the code of its exchange. */
    public record MarketInstrumentRequest(
            @NotBlank @Size(max = 30) String symbol,
            @Size(max = 10) String micCode) {}

    /**
     * An instrument entered by hand, e.g. a Ukrainian government bond; {@code PUT} replaces all fields.
     *
     * @param valuation      {@code NOMINAL} (nominal plus accrued coupon) or {@code MANUAL} ({@code manualPrice})
     * @param couponRate     annual, in percent
     * @param couponFrequency coupons per year: 1, 2, 4 or 12
     * @param manualPrice    the price of one unit, required for {@code MANUAL}
     */
    public record OwnInstrumentRequest(
            @NotBlank @Size(max = 30) String symbol,
            @NotBlank @Size(max = 200) String name,
            @NotNull InstrumentType type,
            @NotNull @CurrencyCode String currency,
            @Pattern(regexp = "[A-Z]{2}[A-Z0-9]{9}[0-9]") String isin,
            @NotNull Valuation valuation,
            @Positive @Digits(integer = 15, fraction = 4) BigDecimal nominal,

            @PositiveOrZero @Digits(integer = 5, fraction = 4) BigDecimal couponRate,

            @Min(1) @Max(12) Integer couponFrequency,
            LocalDate maturityDate,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal manualPrice,
            LocalDate manualPriceDate) {}

    public record PricePoint(LocalDate date, BigDecimal close) {}

    /**
     * An operation of a brokerage account; {@code PUT} replaces all fields.
     *
     * @param accountId    an account of type {@code INVESTMENT}
     * @param instrumentId required except for a {@code FEE}
     * @param quantity     units bought or sold
     * @param price        per unit, in {@code currency}
     * @param amount       a trade: quantity times price by default; a dividend or coupon: the gross amount; a fee: its
     *                     amount
     * @param fee          the fee of a trade, added to the cost of a purchase and taken from the proceeds of a sale
     * @param tax          tax withheld from a dividend or coupon
     * @param currency     by default that of the instrument (of the account for a fee)
     * @param splitFrom    a split of {@code splitFrom} units into {@code splitTo}, e.g. 1 into 4
     */
    public record OperationRequest(
            @NotNull OperationType type,
            @NotNull Long accountId,
            Long instrumentId,
            @NotNull LocalDate date,
            @Positive @Digits(integer = 16, fraction = 8) BigDecimal quantity,

            @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal price,

            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal amount,

            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal fee,

            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal tax,

            @CurrencyCode String currency,
            @Min(1) Integer splitFrom,
            @Min(1) Integer splitTo,
            @Size(max = 1000) String comment) {}

    /** @param cash how the operation changed the cash of the account in {@code currency} */
    public record OperationResponse(
            Long id,
            OperationType type,
            Long accountId,
            @Schema(nullable = true) Long instrumentId,
            @Schema(nullable = true) String symbol,
            LocalDate date,
            @Schema(nullable = true) BigDecimal quantity,
            @Schema(nullable = true) BigDecimal price,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal tax,
            String currency,
            BigDecimal cash,
            @Schema(nullable = true) Integer splitFrom,
            @Schema(nullable = true) Integer splitTo,
            @Schema(nullable = true) String comment) {

        static OperationResponse from(InvestmentOperation operation) {
            Instrument instrument = operation.getInstrument();
            return new OperationResponse(
                    operation.getId(),
                    operation.getType(),
                    operation.getAccount().getId(),
                    instrument != null ? instrument.getId() : null,
                    instrument != null ? instrument.getSymbol() : null,
                    operation.getDate(),
                    operation.getQuantity(),
                    operation.getPrice(),
                    operation.getAmount(),
                    operation.getFee(),
                    operation.getTax(),
                    operation.getCurrency(),
                    operation.cash(),
                    operation.getSplitFrom(),
                    operation.getSplitTo(),
                    operation.getComment());
        }
    }

    /**
     * The portfolio in the base currency at today's rates.
     *
     * @param invested           the cost of the positions held
     * @param unrealized         value minus cost of the positions held
     * @param realizedThisYear   profit of the sales this calendar year, by FIFO, at the rates of the sale dates
     * @param dividendsLast12Months dividends and coupons after withheld tax over the last 12 months, at the rates of
     *                           their dates
     * @param dayChange          the change of the market positions since the close before the last one
     * @param pricesAsOf         the date of the latest close used
     */
    public record PortfolioResponse(
            String currency,
            BigDecimal value,
            BigDecimal invested,
            BigDecimal unrealized,
            @Schema(nullable = true) BigDecimal unrealizedShare,
            BigDecimal realizedThisYear,
            BigDecimal dividendsLast12Months,
            BigDecimal dayChange,
            @Schema(nullable = true) LocalDate pricesAsOf,
            List<PositionResponse> positions,
            List<CashResponse> cash,
            List<AllocationPart> byType,
            List<AllocationPart> byCurrency,
            List<String> unconvertedCurrencies) {}

    /**
     * A position held on an account, in the currency of the instrument unless named "InBase".
     *
     * @param averagePrice the cost of a unit held, fees included
     * @param price        null when no price is known yet; the value is then the cost
     * @param dayChange    the change since the previous close, null when unknown
     * @param share        part of the portfolio value, 0..1
     */
    public record PositionResponse(
            Long accountId,
            InstrumentResponse instrument,
            BigDecimal quantity,
            BigDecimal averagePrice,
            @Schema(nullable = true) BigDecimal price,
            @Schema(nullable = true) LocalDate priceDate,
            @Schema(nullable = true) BigDecimal dayChange,
            BigDecimal value,
            BigDecimal cost,
            BigDecimal unrealized,
            @Schema(nullable = true) BigDecimal unrealizedShare,
            @Schema(nullable = true) BigDecimal valueInBase,
            @Schema(nullable = true) BigDecimal share) {}

    /**
     * Money on a brokerage account in one currency: in the account currency what the transfers brought, plus in every
     * currency what the operations moved.
     */
    public record CashResponse(
            Long accountId,
            String currency,
            BigDecimal amount,
            @Schema(nullable = true) BigDecimal amountInBase) {}

    /** @param key an instrument type, {@code CASH}, or a currency code */
    public record AllocationPart(String key, BigDecimal value, BigDecimal share) {}

    /**
     * One instrument on one account: the position, its lots (FIFO), the sales and all operations.
     *
     * @param realized profit of all sales, by FIFO, in the currency of the instrument
     */
    public record PositionDetailResponse(
            InstrumentResponse instrument,
            Long accountId,
            BigDecimal quantity,
            @Schema(nullable = true) BigDecimal averagePrice,
            @Schema(nullable = true) BigDecimal price,
            BigDecimal value,
            BigDecimal cost,
            BigDecimal unrealized,
            BigDecimal realized,
            BigDecimal dividends,
            List<LotResponse> lots,
            List<SaleResponse> sales,
            List<OperationResponse> operations) {}

    /**
     * A purchase, after the splits since then.
     *
     * @param remaining  what is not sold yet; zero for a closed lot
     * @param unitCost   the cost of a unit, the fee included
     * @param unrealized of the remaining units at the current price, null when the lot is closed or no price is known
     */
    public record LotResponse(
            Long operationId,
            LocalDate date,
            BigDecimal quantity,
            BigDecimal remaining,
            BigDecimal unitCost,
            @Schema(nullable = true) BigDecimal unrealized) {}

    public record SaleResponse(
            Long operationId,
            LocalDate date,
            BigDecimal quantity,
            BigDecimal proceeds,
            BigDecimal cost,
            BigDecimal realized,
            List<SalePart> parts) {}

    /** The part of a lot a sale took. */
    public record SalePart(Long lotOperationId, LocalDate lotDate, BigDecimal quantity, BigDecimal unitCost) {}
}

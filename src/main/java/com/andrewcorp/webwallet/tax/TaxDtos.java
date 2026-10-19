package com.andrewcorp.webwallet.tax;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class TaxDtos {

    private TaxDtos() {}

    /**
     * The tax settings of a year.
     *
     * @param values             what applies: the user's own values over the defaults
     * @param defaults           the defaults of the year for the chosen group, to show what was changed
     * @param sources            where the current rates are published: {@code taxCode}, {@code esv},
     *                           {@code taxService}
     * @param incomeCategoryIds  income categories whose income is income of the sole proprietor on any account (the
     *                           same for every year)
     */
    public record TaxSettingsResponse(
            int year, TaxRates values, TaxRates defaults, Map<String, String> sources, List<Long> incomeCategoryIds) {}

    /** The user's own values of a year; null takes the default. Rates are in percent. */
    public record TaxSettingsRequest(
            @Min(1) @Max(3) Integer fopGroup,
            Boolean fopVat,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal singleTaxRate,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal fopMilitaryRate,

            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal singleTaxMonthly,

            @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal fopMilitaryMonthly,

            Boolean esvEnabled,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal esvRate,

            @Positive @Digits(integer = 10, fraction = 2) BigDecimal minWage,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal pdfoRate,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal investmentMilitaryRate,

            Boolean foreignDocumentDefault) {}

    public record IncomeCategoriesRequest(
            @NotNull @Size(max = 500) List<@NotNull Long> categoryIds) {}

    /** @param included true adds the transaction to the income of the sole proprietor, false takes it out, null forgets */
    public record TransactionMarkRequest(Boolean included) {}

    public record QuarterPaymentRequest(boolean paid) {}

    /**
     * Whether a transaction is income of the sole proprietor.
     *
     * @param mark          the user's mark: true added, false taken out, null none
     * @param counted       whether it counts now
     * @param reason        why it is income, also when it was taken out; null when it is not
     * @param ruleReason    why the rules alone (the account, the categories) would count it, without the mark
     */
    public record TransactionTaxResponse(
            @Schema(nullable = true) Boolean mark,
            boolean counted,
            @Schema(nullable = true) IncomeReason reason,
            @Schema(nullable = true) IncomeReason ruleReason) {}

    /** @param document whether the document of the tax withheld abroad exists; null takes the default of the year */
    public record DividendDocumentRequest(Boolean document) {}

    /**
     * The taxes of investment income for a year, in hryvnias at the NBU rates of the dates: dividends and coupons
     * (those of government bonds are exempt), and the profit of the sales — proceeds at the rate of the sale, the lots
     * sold (FIFO, their fees included) at the rates of their purchases, so a change of the exchange rate is profit too.
     * A loss carries over to the following years.
     *
     * @param dividends      newest first
     * @param sales          newest first; those of government bonds are left out
     * @param profit         the result of the year's sales; negative for a loss
     * @param carriedIn      the loss of earlier years not used yet
     * @param taxableProfit  the profit after the carried loss
     * @param carriedOut     the loss left for the following years
     * @param declarationDue the yearly declaration is filed by 1 May of the next year
     * @param paymentDue     the tax is paid by 31 July of the next year
     */
    public record InvestmentTaxReport(
            int year,
            TaxRates rates,
            List<DividendTax> dividends,
            List<SaleTax> sales,
            BigDecimal dividendsUah,
            BigDecimal creditUah,
            BigDecimal profit,
            BigDecimal carriedIn,
            BigDecimal taxableProfit,
            BigDecimal carriedOut,
            BigDecimal pdfo,
            BigDecimal military,
            BigDecimal total,
            LocalDate declarationDue,
            LocalDate paymentDue,
            List<String> unconvertedCurrencies) {}

    /**
     * A dividend or coupon.
     *
     * @param document    whether the document of the tax withheld abroad exists: the mark of the dividend, else the
     *                    default of the year
     * @param documentSet whether the dividend has its own mark
     * @param credit      the tax withheld abroad that reduces the personal income tax, at most that tax
     * @param pdfo        the personal income tax still due after the credit
     */
    public record DividendTax(
            Long operationId,
            LocalDate date,
            Long accountId,
            String symbol,
            String type,
            BigDecimal amount,
            BigDecimal withheld,
            String currency,
            @Schema(nullable = true) BigDecimal rate,
            BigDecimal amountUah,
            BigDecimal withheldUah,
            boolean document,
            boolean documentSet,
            BigDecimal credit,
            BigDecimal pdfo,
            BigDecimal military) {}

    /**
     * A sale, in hryvnias.
     *
     * @param proceeds    without the fee of the sale, in the currency of the instrument
     * @param cost        of the lots sold, in the currency of the instrument
     * @param incomeUah   the proceeds at the rate of the sale
     * @param costUah     each lot at the rate of its purchase
     * @param resultUah   income minus cost
     * @param resultInCurrency the result in the currency of the instrument, to see what the exchange rate made
     */
    public record SaleTax(
            Long operationId,
            LocalDate date,
            Long accountId,
            String symbol,
            BigDecimal quantity,
            String currency,
            BigDecimal proceeds,
            BigDecimal cost,
            BigDecimal incomeUah,
            BigDecimal costUah,
            BigDecimal resultUah,
            BigDecimal resultInCurrency) {}

    /** Why a transaction is income of the sole proprietor. */
    public enum IncomeReason {
        /** An income of an account of the sole proprietor. */
        ACCOUNT,
        /** An income of one of the chosen categories, on any account; only the amount of those categories counts. */
        CATEGORY,
        /** Added by the user. */
        MANUAL
    }

    /**
     * The taxes of the sole proprietor for a year, in hryvnias. Income in a foreign currency counts at the NBU rate of
     * the day it was credited.
     *
     * @param incomes every income of the year that is or was income of the sole proprietor, newest first; those taken
     *                out by the user are listed with {@code included} false
     */
    public record FopReport(
            int year,
            TaxRates rates,
            List<Long> fopAccountIds,
            List<FopQuarter> quarters,
            FopTotals totals,
            BigDecimal incomeLimit,
            List<FopIncome> incomes,
            List<String> unconvertedCurrencies) {}

    /**
     * @param declarationDue the last day to file the declaration: 40 days after the quarter for group 3, 60 days after
     *                       the year for groups 1 and 2 (in the fourth quarter only); a weekend moves it to Monday,
     *                       public holidays are not known
     * @param taxDue         the last day to pay the single tax and the military levy: 10 days after the declaration
     *                       for group 3; the 20th of the first month of the quarter for groups 1 and 2, which pay
     *                       each month in advance
     * @param esvDue         the last day to pay the social contribution: the 20th of the month after the quarter
     * @param paid           marked as paid by the user
     */
    public record FopQuarter(
            int quarter,
            BigDecimal income,
            BigDecimal singleTax,
            BigDecimal military,
            BigDecimal esv,
            BigDecimal total,
            @Schema(nullable = true) LocalDate declarationDue,
            LocalDate taxDue,
            LocalDate esvDue,
            boolean paid) {}

    public record FopTotals(
            BigDecimal income, BigDecimal singleTax, BigDecimal military, BigDecimal esv, BigDecimal total) {}

    /**
     * @param amount    in the currency of the account: the whole income, or the part of the chosen categories
     * @param rate      hryvnias for one unit of the currency on the date; null for hryvnias or when unknown
     * @param amountUah null when the rate is unknown
     */
    public record FopIncome(
            Long transactionId,
            LocalDate date,
            int quarter,
            Long accountId,
            String accountName,
            @Schema(nullable = true) String payee,
            @Schema(nullable = true) String comment,
            BigDecimal amount,
            String currency,
            @Schema(nullable = true) BigDecimal rate,
            @Schema(nullable = true) BigDecimal amountUah,
            boolean included,
            IncomeReason reason) {}
}

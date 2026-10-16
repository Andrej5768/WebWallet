package com.andrewcorp.webwallet.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Analytics for the reports, in the base currency of the user. Transfers between own accounts are left out. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {}

    /**
     * Plan vs actual of the envelopes of a month.
     *
     * @param daysElapsed days of the month up to today: all of them for a past month, 0 for a future one
     * @param planned     money assigned to the envelopes
     * @param actual      spending in the envelopes (refunds subtracted), positive
     * @param difference  {@code planned - actual}; negative is overspending
     * @param forecast    expected spending at the end of the month
     * @param categories  top-level expense categories with a plan or spending, subcategories nested
     */
    public record PlanResponse(
            YearMonth month,
            String currency,
            int daysElapsed,
            int daysInMonth,
            BigDecimal planned,
            BigDecimal actual,
            BigDecimal difference,
            BigDecimal forecast,
            List<PlanRow> categories,
            List<String> unconvertedCurrencies) {}

    /**
     * An envelope; the values of a parent include its subcategories.
     *
     * @param completion       {@code actual / planned}, null without a plan
     * @param plannedRemaining pending occurrences of recurring payments in the category this month (overdue ones
     *                         included), at today's rates
     * @param forecast         for the current month the spending so far, plus the everyday spending (without the
     *                         payments of recurring payments) continued at its pace to the end of the month, plus
     *                         {@code plannedRemaining}; for a past month the actual spending, for a future month the
     *                         plan
     */
    public record PlanRow(
            Long categoryId,
            String name,
            BigDecimal planned,
            BigDecimal actual,
            BigDecimal difference,
            @Schema(nullable = true) BigDecimal completion,
            BigDecimal plannedRemaining,
            BigDecimal forecast,
            List<PlanRow> children) {}

    /**
     * Spending of period A compared with period B.
     *
     * @param days        cumulative spending from the first day of each period; {@code a} or {@code b} is null after
     *                    the end of its period
     * @param change      {@code totalA - totalB}
     * @param changeShare {@code change / totalB}, null when B has no spending
     * @param categories  top-level expense categories by the size of the change, largest first
     */
    public record CompareResponse(
            LocalDate fromA,
            LocalDate toA,
            LocalDate fromB,
            LocalDate toB,
            String currency,
            BigDecimal totalA,
            BigDecimal totalB,
            BigDecimal change,
            @Schema(nullable = true) BigDecimal changeShare,
            List<CompareDay> days,
            List<CategoryChange> categories,
            List<String> unconvertedCurrencies) {}

    /** @param day day of the period, from 1 */
    public record CompareDay(
            int day,
            @Schema(nullable = true) BigDecimal a,
            @Schema(nullable = true) BigDecimal b) {}

    /**
     * @param categoryId null for spending without a category
     * @param name       null for spending without a category
     */
    public record CategoryChange(
            @Schema(nullable = true) Long categoryId,
            @Schema(nullable = true) String name,
            BigDecimal a,
            BigDecimal b,
            BigDecimal change,
            @Schema(nullable = true) BigDecimal changeShare) {}

    /**
     * Monthly spending per top-level expense category.
     *
     * @param months     every month of the range with the total spending
     * @param average    average monthly total
     * @param categories categories with spending, largest total first
     */
    public record TrendsResponse(
            YearMonth from,
            YearMonth to,
            String currency,
            List<MonthTotal> months,
            BigDecimal average,
            List<CategoryTrend> categories,
            List<String> unconvertedCurrencies) {}

    public record MonthTotal(YearMonth month, BigDecimal total) {}

    /**
     * @param categoryId null for spending without a category
     * @param name       null for spending without a category
     * @param amounts    spending per month, in the order of the months of the response
     * @param anomalies  months that spent much more than the average of the other months
     */
    public record CategoryTrend(
            @Schema(nullable = true) Long categoryId,
            @Schema(nullable = true) String name,
            List<BigDecimal> amounts,
            BigDecimal total,
            BigDecimal average,
            List<Anomaly> anomalies) {}

    /**
     * @param average average of the other months of the range
     * @param ratio   {@code amount / average}
     */
    public record Anomaly(YearMonth month, BigDecimal amount, BigDecimal average, BigDecimal ratio) {}

    /**
     * Money saved per month: income minus expenses.
     *
     * @param target      the wanted savings rate
     * @param averageRate savings rate of the complete months together, null without their income
     * @param totalSaved  net of all months
     * @param bestMonth   the month that saved the most, null when no month saved money
     */
    public record SavingsResponse(
            YearMonth from,
            YearMonth to,
            String currency,
            BigDecimal target,
            @Schema(nullable = true) BigDecimal averageRate,
            BigDecimal totalSaved,
            @Schema(nullable = true) YearMonth bestMonth,
            List<MonthSavings> months,
            List<String> unconvertedCurrencies) {}

    /**
     * @param savingsRate  {@code net / income}, null without income
     * @param cumulative   net of the range up to and including this month
     * @param complete     false for the current month and later
     * @param destinations money moved from budget accounts to accounts outside the budget, minus money moved back, per
     *                     account outside the budget; largest first
     */
    public record MonthSavings(
            YearMonth month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal net,
            @Schema(nullable = true) BigDecimal savingsRate,
            BigDecimal cumulative,
            boolean complete,
            List<Destination> destinations) {}

    public record Destination(Long accountId, String name, BigDecimal amount) {}

    /**
     * 50/30/20: spending on needs and wants and the savings, as parts of the income. Expense categories are classified
     * by their {@code necessity}; a subcategory without one takes that of its parent.
     *
     * @param unclassified spending without a category or in a category without a necessity
     */
    public record NeedsWantsResponse(
            LocalDate from,
            LocalDate to,
            String currency,
            BigDecimal income,
            Part needs,
            Part wants,
            Part savings,
            Part unclassified,
            List<String> unconvertedCurrencies) {}

    /**
     * @param share  part of the income, null without income
     * @param target the recommended share: 0.5 for needs, 0.3 for wants, 0.2 for savings; null for unclassified
     */
    public record Part(
            BigDecimal amount,
            @Schema(nullable = true) BigDecimal share,
            @Schema(nullable = true) BigDecimal target) {}
}

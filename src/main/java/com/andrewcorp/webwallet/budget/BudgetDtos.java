package com.andrewcorp.webwallet.budget;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class BudgetDtos {

    private BudgetDtos() {}

    /**
     * @param assigned plan of the category this month, in the base currency: money for the envelope of an expense
     *                 category or the income expected in an income category; 0 removes it
     */
    public record AssignRequest(
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal assigned) {}

    /**
     * Plans of several categories of one month, saved together: all of them or none.
     *
     * @param plans at most one entry per category
     */
    public record AssignManyRequest(
            @NotEmpty @Size(max = 500) List<@Valid @NotNull PlanEntry> plans) {}

    /**
     * @param categoryId an expense or income category
     * @param assigned   like {@link AssignRequest#assigned()}
     */
    public record PlanEntry(
            @NotNull Long categoryId,
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal assigned) {}

    /**
     * Moves money between the own envelopes of two expense categories in one step.
     *
     * @param fromCategoryId the envelope whose own assignment is lowered
     * @param toCategoryId   the envelope whose own assignment is raised; another expense category
     * @param amount         positive, in the base currency
     */
    public record MoveRequest(
            @NotNull Long fromCategoryId,
            @NotNull Long toCategoryId,

            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount) {}

    /**
     * Budget of a month in the base currency of the user.
     *
     * @param readyToAssign free money of the month: its inflow minus its plan, minus spending without a category and
     *                      minus the overspending that envelopes with carry-over bring from the previous month; negative
     *                      means the plan is bigger than the income
     * @param inflow        income of the budget accounts in the month, plus transfers into the budget from other
     *                      accounts, minus transfers out of it
     * @param assigned      plan of the expense categories
     * @param categories    expense categories as a tree; a parent row includes its subcategories
     * @param plannedIncome income planned in the income categories; not part of {@code readyToAssign}, which counts
     *                      the income actually received
     * @param income        income received on the budget accounts in the month, with and without a category
     * @param uncategorizedIncome  the part of {@code income} without a category
     * @param incomeCategories     income categories as a tree; a parent row includes its subcategories
     */
    public record MonthResponse(
            YearMonth month,
            String currency,
            BigDecimal readyToAssign,
            BigDecimal inflow,
            BigDecimal assigned,
            BigDecimal activity,
            BigDecimal available,
            List<CategoryRow> categories,
            BigDecimal plannedIncome,
            BigDecimal income,
            BigDecimal uncategorizedIncome,
            List<IncomeRow> incomeCategories,
            List<String> unconvertedCurrencies) {}

    /**
     * Plan and income of an income category in the month. The values of a parent include its subcategories, the
     * {@code own} values are those of the parent alone.
     *
     * @param planned  income expected this month
     * @param received income received this month on the budget accounts
     */
    public record IncomeRow(
            Long categoryId,
            String name,
            @Schema(nullable = true) Long parentId,
            boolean archived,
            BigDecimal planned,
            BigDecimal received,
            BigDecimal ownPlanned,
            BigDecimal ownReceived,
            List<IncomeRow> children) {}

    /**
     * Plan and actual values of several months side by side, in the base currency of the user.
     *
     * @param months        the months of the range, in order; every {@link BudgetPlanRow#months()} follows them
     * @param averageMonths the three full months before the current one that {@link BudgetPlanRow#average()} is taken from
     * @param income        income categories as a tree
     * @param expenses      expense categories as a tree
     * @param totals        per month, in the order of {@code months}
     */
    public record BudgetPlanResponse(
            String currency,
            List<YearMonth> months,
            List<YearMonth> averageMonths,
            List<BudgetPlanRow> income,
            List<BudgetPlanRow> expenses,
            List<BudgetPlanTotals> totals,
            List<String> unconvertedCurrencies) {}

    /**
     * A category with its plan and actual value in every month of the range. The values of a parent include its
     * subcategories; the {@code own} values are those of the parent alone.
     *
     * @param carryOver  an expense category whose rest moves to the next month
     * @param average    actual value per month over {@link BudgetPlanResponse#averageMonths()}
     * @param ownAverage {@code average} without the subcategories
     */
    public record BudgetPlanRow(
            Long categoryId,
            String name,
            @Schema(nullable = true) Long parentId,
            boolean archived,
            boolean carryOver,
            BigDecimal average,
            BigDecimal ownAverage,
            List<BudgetPlanCell> months,
            List<BudgetPlanRow> children) {}

    /**
     * @param plan   the plan of the month: money for an expense category, expected income for an income category
     * @param actual spending of the month (positive, refunds subtracted) or income received
     */
    public record BudgetPlanCell(
            YearMonth month, BigDecimal plan, BigDecimal actual, BigDecimal ownPlan, BigDecimal ownActual) {}

    /**
     * Sums of a month. The actual values include operations without a category, which are also given apart.
     *
     * @param income   income received
     * @param expenses spending, positive
     */
    public record BudgetPlanTotals(
            YearMonth month,
            BigDecimal plannedIncome,
            BigDecimal income,
            BigDecimal plannedExpenses,
            BigDecimal expenses,
            BigDecimal uncategorizedIncome,
            BigDecimal uncategorizedExpenses) {}

    /**
     * An envelope. The values of a parent include its subcategories: {@code assigned}, {@code activity} and
     * {@code available} are its own values plus those of its children; the {@code own} values are those of the parent
     * category alone. For a subcategory both are the same.
     *
     * @param assigned     money put into the envelope this month
     * @param activity     spending (negative) and refunds this month
     * @param available    what is left of the plan: assigned plus activity, plus the positive rest of previous months
     *                     for a category with carry-over; negative is overspending
     * @param ownAssigned  {@code assigned} without the children
     * @param ownActivity  {@code activity} without the children
     * @param ownAvailable {@code available} without the children
     */
    public record CategoryRow(
            Long categoryId,
            String name,
            @Schema(nullable = true) Long parentId,
            boolean archived,
            BigDecimal assigned,
            BigDecimal activity,
            BigDecimal available,
            BigDecimal ownAssigned,
            BigDecimal ownActivity,
            BigDecimal ownAvailable,
            List<CategoryRow> children) {}
}

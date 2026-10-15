package com.andrewcorp.webwallet.budget;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class BudgetDtos {

    private BudgetDtos() {}

    /** @param assigned money for the envelope this month, in the base currency; 0 removes the assignment */
    public record AssignRequest(
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
     * Envelope budget of a month in the base currency of the user.
     *
     * @param readyToAssign money of the budget accounts that is not assigned to any envelope yet; negative means more
     *                      was assigned than there is
     * @param categories    expense categories as a tree; a parent row includes its subcategories
     */
    public record MonthResponse(
            YearMonth month,
            String currency,
            BigDecimal readyToAssign,
            BigDecimal assigned,
            BigDecimal activity,
            BigDecimal available,
            List<CategoryRow> categories,
            List<String> unconvertedCurrencies) {}

    /**
     * An envelope. The values of a parent include its subcategories: {@code assigned}, {@code activity} and
     * {@code available} are its own values plus those of its children; the {@code own} values are those of the parent
     * category alone. For a subcategory both are the same.
     *
     * @param assigned     money put into the envelope this month
     * @param activity     spending (negative) and refunds this month
     * @param available    what is left: the positive rest of previous months plus assigned plus activity; negative is
     *                     overspending, which is taken from the money ready to assign next month
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

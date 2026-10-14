package com.andrewcorp.webwallet.budget;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public final class BudgetDtos {

    private BudgetDtos() {}

    /** @param assigned money for the envelope this month, in the base currency; 0 removes the assignment */
    public record AssignRequest(
            @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal assigned) {}

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
     * @param assigned  money put into the envelope this month
     * @param activity  spending (negative) and refunds this month
     * @param available what is left: the positive rest of previous months plus assigned plus activity; negative is
     *                  overspending, which is taken from the money ready to assign next month
     */
    public record CategoryRow(
            Long categoryId,
            String name,
            Long parentId,
            boolean archived,
            BigDecimal assigned,
            BigDecimal activity,
            BigDecimal available,
            List<CategoryRow> children) {}
}

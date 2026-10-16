package com.andrewcorp.webwallet.goal;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import com.andrewcorp.webwallet.ledger.PaletteColor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class GoalDtos {

    private GoalDtos() {}

    /**
     * A savings goal; {@code PUT} replaces all fields.
     *
     * @param currency   of the target; by default that of the account, or the base currency
     * @param targetDate when the money is needed; none means no deadline
     * @param accountId  the account that holds the money, e.g. a Monobank jar; its balance is the progress
     * @param saved      the progress of a goal without an account, kept by hand
     * @param icon       a Lucide icon name in kebab case
     * @param achieved   marks the goal as achieved (or not any more)
     */
    public record GoalRequest(
            @NotBlank @Size(max = 100) String name,

            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal targetAmount,

            @CurrencyCode String currency,
            LocalDate targetDate,
            Long accountId,

            @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal saved,

            @Pattern(regexp = "[a-z0-9-]{1,50}") String icon,
            PaletteColor color,
            Boolean achieved,
            Integer sortOrder) {}

    /** @param amount added to the progress of a goal without an account; negative takes money out */
    public record ContributionRequest(
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {}

    /**
     * The goals with totals in the base currency.
     *
     * @param monthlyNeeded what has to be saved per month to reach every goal with a deadline in time
     */
    public record GoalsResponse(
            String currency,
            BigDecimal saved,
            BigDecimal target,
            BigDecimal monthlyNeeded,
            List<GoalResponse> goals,
            List<String> unconvertedCurrencies) {}

    /**
     * @param saved         the progress: the balance of the account in the goal currency at today's rate, or the
     *                      amount kept by hand
     * @param progress      saved / target, 0..1
     * @param startAmount   the progress when the goal was set, from which the pace is counted
     * @param expected      what should be saved by today to keep the pace to the deadline; null without one
     * @param monthsLeft    whole months to the deadline, at least 1 before it; null without one
     * @param monthlyNeeded what is still missing divided by the months left; null without a deadline
     * @param status        {@code ACHIEVED}, {@code NO_DATE}, {@code ON_TRACK}, {@code BEHIND} or {@code OVERDUE}
     *                      (the deadline has passed)
     */
    public record GoalResponse(
            Long id,
            String name,
            BigDecimal targetAmount,
            String currency,
            @Schema(nullable = true) LocalDate targetDate,
            @Schema(nullable = true) Long accountId,
            BigDecimal saved,
            BigDecimal remaining,
            BigDecimal progress,
            LocalDate startDate,
            BigDecimal startAmount,
            @Schema(nullable = true) BigDecimal expected,
            @Schema(nullable = true) Integer monthsLeft,
            @Schema(nullable = true) BigDecimal monthlyNeeded,
            GoalStatus status,
            @Schema(nullable = true) LocalDate achievedOn,
            @Schema(nullable = true) String icon,
            @Schema(nullable = true) PaletteColor color,
            int sortOrder) {}

    public enum GoalStatus {
        ACHIEVED,
        NO_DATE,
        ON_TRACK,
        BEHIND,
        OVERDUE
    }
}

package com.andrewcorp.webwallet.planning;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ForecastDtos {

    private ForecastDtos() {}

    /**
     * Forecast of the money of cash and cards in the base currency.
     *
     * @param balance       the balance at the end of today
     * @param dailySpending the expected everyday spending per day: the average of the last 90 days without the
     *                      payments of recurring payments
     * @param days          every day from the first of the current month: actual balances up to today, then forecast
     * @param events        pending occurrences of recurring payments that change the balance, by date
     * @param lowest        the lowest forecast balance; null when the forecast has no days ahead
     */
    public record ForecastResponse(
            String currency,
            LocalDate today,
            BigDecimal balance,
            BigDecimal dailySpending,
            List<ForecastDay> days,
            List<ForecastEvent> events,
            @Schema(nullable = true) ForecastDay lowest,
            List<String> unconvertedCurrencies) {}

    /**
     * @param actual     whether the balance is the actual one (up to today) or forecast
     * @param operations transactions of the day that changed the money of cash and cards; of a day ahead, the ones
     *                   entered ahead of time
     */
    public record ForecastDay(LocalDate date, BigDecimal balance, boolean actual, List<ForecastOperation> operations) {}

    /**
     * @param name   the payee, otherwise the comment; null when the transaction has neither
     * @param amount the change of the balance in the base currency, negative for money leaving
     */
    public record ForecastOperation(
            Long id,
            @Schema(nullable = true) String name,
            @Schema(nullable = true) Long categoryId,
            BigDecimal amount) {}

    /**
     * @param date    the scheduled date; an overdue occurrence is expected tomorrow
     * @param amount  the change of the balance, negative for money leaving
     */
    public record ForecastEvent(
            LocalDate date,
            Long recurringId,
            String name,
            BigDecimal amount,
            boolean overdue,
            @Schema(nullable = true) Long categoryId) {}
}

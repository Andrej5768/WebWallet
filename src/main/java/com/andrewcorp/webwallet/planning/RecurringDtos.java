package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.ledger.NamedResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class RecurringDtos {

    private RecurringDtos() {}

    /**
     * A recurring payment; {@code PUT} replaces all fields. The fields of the transaction follow the rules of a
     * transaction: a transfer has {@code toAccountId} and no category, {@code toAmount} is required between currencies.
     *
     * @param frequency  unit of the schedule
     * @param interval   every how many units, 1 by default
     * @param startDate  the first occurrence; the following ones are counted from it, so a monthly payment on the 31st
     *                   falls on the last day of shorter months
     * @param endDate    the last possible occurrence; none means no end
     * @param autoCreate create the transaction on the due date; otherwise the payment waits to be matched with a bank
     *                   operation or confirmed by the user
     * @param variableAmount the amount is an estimate, e.g. of a utility bill
     * @param tolerance  how much a matching transaction may differ from the amount, in percent; by default 10, or
     *                   25 for a variable amount
     */
    public record RecurringRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull TransactionType type,
            @NotNull Long accountId,

            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,

            Long toAccountId,
            @Positive @Digits(integer = 15, fraction = 4) BigDecimal toAmount,
            Long categoryId,
            Long payeeId,
            @Size(max = 200) String payeeName,
            @Size(max = 1000) String comment,
            @NotNull Frequency frequency,
            @Min(1) @Max(365) Integer interval,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            Boolean autoCreate,
            Boolean variableAmount,
            @Min(0) @Max(50) Integer tolerance) {}

    /**
     * @param nextDate the earliest occurrence that is neither paid nor skipped, overdue ones of the last
     *                 {@value RecurringService#LOOKBACK_DAYS} days included; null when the schedule has ended
     */
    public record RecurringResponse(
            Long id,
            String name,
            TransactionType type,
            Long accountId,
            BigDecimal amount,
            String currency,
            @Schema(nullable = true) Long toAccountId,
            @Schema(nullable = true) BigDecimal toAmount,
            @Schema(nullable = true) String toCurrency,
            @Schema(nullable = true) Long categoryId,
            @Schema(nullable = true) NamedResponse payee,
            @Schema(nullable = true) String comment,
            Frequency frequency,
            int interval,
            LocalDate startDate,
            @Schema(nullable = true) LocalDate endDate,
            boolean autoCreate,
            boolean variableAmount,
            int tolerance,
            @Schema(nullable = true) LocalDate nextDate) {}

    /**
     * One scheduled date of a recurring payment, with the planned values of the payment.
     *
     * @param date          the scheduled date
     * @param overdue       pending and the date has passed
     * @param transactionId the transaction that paid it
     */
    public record OccurrenceResponse(
            Long recurringId,
            String name,
            LocalDate date,
            OccurrenceStatus status,
            boolean overdue,
            TransactionType type,
            Long accountId,
            BigDecimal amount,
            String currency,
            @Schema(nullable = true) Long toAccountId,
            @Schema(nullable = true) BigDecimal toAmount,
            @Schema(nullable = true) Long categoryId,
            @Schema(nullable = true) NamedResponse payee,
            boolean autoCreate,
            boolean variableAmount,
            @Schema(nullable = true) Long transactionId) {}

    /**
     * Creates the transaction of an occurrence; omitted values are those of the recurring payment.
     *
     * @param date     the date of the transaction; by default the scheduled date, or today when that is still ahead
     * @param amount   the actual amount, e.g. of a utility bill
     * @param toAmount the amount received by a transfer between currencies
     */
    public record OccurrenceConfirmRequest(
            LocalDate date,
            @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @Positive @Digits(integer = 15, fraction = 4) BigDecimal toAmount) {}

    /** @param transactionId an existing transaction that pays the occurrence */
    public record OccurrenceLinkRequest(@NotNull Long transactionId) {}
}

package com.andrewcorp.webwallet.ledger.transaction.dto;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * @param amount           positive amount in the currency of the account
 * @param toAccountId      target account of a transfer
 * @param toAmount         amount received by the target account, in its currency; defaults to {@code amount} when
 *                         both accounts have the same currency. A smaller amount in the same currency means a fee.
 * @param originalAmount   amount charged by the merchant in a foreign currency
 * @param categoryId       category of the whole expense or income, when it is not split
 * @param splits           breakdown of an expense or income by category; the amounts must sum up to {@code amount}
 * @param payeeId          existing payee
 * @param payeeName        payee by name, created if it does not exist; used when {@code payeeId} is empty
 * @param adjustment       a balance correction, e.g. to match the balance reported by a bank: an expense or income
 *                         without a category, counted in the balance of the account but left out of the reports, the
 *                         budget, the forecast and the taxes
 */
public record TransactionRequest(
        @NotNull TransactionType type,
        @NotNull LocalDate date,
        @NotNull Long accountId,

        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,

        Long toAccountId,
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal toAmount,
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal originalAmount,
        @CurrencyCode String originalCurrency,
        Long categoryId,
        @Size(max = 100) List<@Valid SplitRequest> splits,
        Long payeeId,
        @Size(max = 200) String payeeName,
        @Size(max = 1000) String comment,
        @Size(max = 50) List<Long> tagIds,
        Boolean adjustment) {

    public record SplitRequest(
            Long categoryId,

            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,

            @Size(max = 500) String comment) {}
}

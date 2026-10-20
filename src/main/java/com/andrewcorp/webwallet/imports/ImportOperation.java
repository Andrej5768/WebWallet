package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An operation of an import preview as it would be imported.
 *
 * @param key          identifies the operation in the edits of the confirmation
 * @param payee        name of the payee, null when unknown
 * @param amount       positive, in {@code currency}, the currency of {@code account}
 * @param account      the account of the operation, the source of a transfer
 * @param toAccount    the target of a transfer, otherwise null
 * @param toAmount     the amount arriving to {@code toAccount}, in its currency
 * @param type         EXPENSE, INCOME or TRANSFER
 * @param categoryId   the suggested category; null when there is none or when the import creates it
 * @param categoryName name of the suggested category, also of one the import creates
 * @param tagIds       always empty in a preview; tags are given in the edits of the confirmation
 * @param duplicate    the operation is already in the ledger (imported before, entered manually or the other half of
 *                     a transfer) and is not imported again
 * @param transferLike without a category or marked as a transfer by the bank: it may be a half of a transfer
 * @param transferSuggestions the other half of a transfer this expense or income may be, best first: an operation of
 *                     the same import or a transaction of the ledger, the same day, within 10 %
 */
public record ImportOperation(
        String key,
        LocalDate date,
        @Schema(nullable = true) String payee,
        BigDecimal amount,
        String currency,
        ImportAccount account,
        @Schema(nullable = true) ImportAccount toAccount,
        @Schema(nullable = true) BigDecimal toAmount,
        TransactionType type,
        @Schema(nullable = true) Long categoryId,
        @Schema(nullable = true) String categoryName,
        List<Long> tagIds,
        @Schema(nullable = true) String comment,
        boolean duplicate,
        boolean transferLike,
        @Schema(nullable = true) List<TransferSuggestion> transferSuggestions) {

    public ImportOperation withSuggestions(List<TransferSuggestion> suggestions) {
        return new ImportOperation(
                key,
                date,
                payee,
                amount,
                currency,
                account,
                toAccount,
                toAmount,
                type,
                categoryId,
                categoryName,
                tagIds,
                comment,
                duplicate,
                transferLike,
                suggestions.isEmpty() ? null : suggestions);
    }

    /**
     * A possible other half of a transfer.
     *
     * @param operationKey  an operation of the same import, or null
     * @param transactionId a transaction already in the ledger, or null
     * @param description   payee or comment
     * @param exact         the same amount
     * @param difference    how much more (positive) or less (negative) arrived than was sent, as a share of the sent
     *                      amount
     */
    public record TransferSuggestion(
            @Schema(nullable = true) String operationKey,
            @Schema(nullable = true) Long transactionId,
            LocalDate date,
            String accountName,
            BigDecimal amount,
            String currency,
            @Schema(nullable = true) String description,
            boolean exact,
            BigDecimal difference) {}

    /**
     * An account of a previewed operation.
     *
     * @param id      null when the import creates the account
     * @param created whether the import creates the account
     */
    public record ImportAccount(@Schema(nullable = true) Long id, String name, String currency, boolean created) {}
}

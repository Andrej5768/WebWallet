package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
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
 */
public record ImportOperation(
        String key,
        LocalDate date,
        String payee,
        BigDecimal amount,
        String currency,
        ImportAccount account,
        ImportAccount toAccount,
        BigDecimal toAmount,
        TransactionType type,
        Long categoryId,
        String categoryName,
        List<Long> tagIds,
        String comment,
        boolean duplicate) {

    /**
     * An account of a previewed operation.
     *
     * @param id      null when the import creates the account
     * @param created whether the import creates the account
     */
    public record ImportAccount(Long id, String name, String currency, boolean created) {}
}

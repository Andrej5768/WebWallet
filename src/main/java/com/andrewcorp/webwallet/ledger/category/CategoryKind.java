package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.ledger.transaction.TransactionType;

public enum CategoryKind {
    EXPENSE,
    INCOME;

    /** The kind of categories of an expense or income; a transfer has no category and counts as an expense. */
    public static CategoryKind of(TransactionType type) {
        return type == TransactionType.INCOME ? INCOME : EXPENSE;
    }
}

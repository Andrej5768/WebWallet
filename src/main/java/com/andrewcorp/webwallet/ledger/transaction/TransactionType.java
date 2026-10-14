package com.andrewcorp.webwallet.ledger.transaction;

public enum TransactionType {
    EXPENSE,
    INCOME,
    /** Money moved between two own accounts, possibly in different currencies or with a fee. */
    TRANSFER
}

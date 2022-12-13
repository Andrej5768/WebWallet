package com.andrewCorp.webWallet.persistence.enums;

public enum TransactionType {
    TRANSFER("Transfer"),
    INCOME("Income"),
    EXPENSE("Expense");

    private String value;

    TransactionType(String value) {
    }

    public String getValue() {
        return value;
    }
}

package com.andrewcorp.webwallet.ledger.account;

public enum AccountType {
    CASH(true),
    CARD(true),
    CREDIT_CARD(true),
    LOAN(false),
    DEPOSIT(false),
    SAVINGS(false),
    /** Money lent to or borrowed from people. */
    DEBT(false),
    /** Brokerage or crypto account, managed by the investment module. */
    INVESTMENT(false);

    private final boolean inBudgetByDefault;

    AccountType(boolean inBudgetByDefault) {
        this.inBudgetByDefault = inBudgetByDefault;
    }

    public boolean inBudgetByDefault() {
        return inBudgetByDefault;
    }
}

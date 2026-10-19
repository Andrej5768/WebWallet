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
    INVESTMENT(false),
    /** An account of a sole proprietor (ФОП): its income is the income of the business for the taxes. */
    FOP(true);

    private final boolean inBudgetByDefault;

    AccountType(boolean inBudgetByDefault) {
        this.inBudgetByDefault = inBudgetByDefault;
    }

    public boolean inBudgetByDefault() {
        return inBudgetByDefault;
    }
}

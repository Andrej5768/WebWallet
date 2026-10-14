package com.andrewcorp.webwallet.ledger.account.dto;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import java.math.BigDecimal;

/**
 * @param balance current balance in the account currency; negative on a credit card means debt
 */
public record AccountResponse(
        Long id,
        String name,
        AccountType type,
        String currency,
        boolean inBudget,
        boolean archived,
        BigDecimal openingBalance,
        BigDecimal balance,
        BigDecimal creditLimit,
        BigDecimal interestRate,
        int sortOrder) {

    public static AccountResponse from(Account account, BigDecimal balance) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                account.isInBudget(),
                account.isArchived(),
                account.getOpeningBalance(),
                balance,
                account.getCreditLimit(),
                account.getInterestRate(),
                account.getSortOrder());
    }
}

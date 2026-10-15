package com.andrewcorp.webwallet.ledger.account.dto;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import java.math.BigDecimal;

/**
 * @param balance         current balance in the account currency; negative on a credit card means debt
 * @param balanceInBase   the balance in the base currency of the user at today's NBU rate; null when there is no rate
 * @param availableCredit of a credit card with a limit: the limit plus the balance, in the account currency; may be
 *                        negative when the limit is exceeded
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
        BigDecimal balanceInBase,
        BigDecimal creditLimit,
        BigDecimal availableCredit,
        BigDecimal interestRate,
        int sortOrder) {

    public static AccountResponse from(Account account, BigDecimal balance, BigDecimal balanceInBase) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                account.isInBudget(),
                account.isArchived(),
                account.getOpeningBalance(),
                balance,
                balanceInBase,
                account.getCreditLimit(),
                availableCredit(account, balance),
                account.getInterestRate(),
                account.getSortOrder());
    }

    /** The limit plus the balance of a credit card with a limit, null for other accounts. */
    public static BigDecimal availableCredit(Account account, BigDecimal balance) {
        return account.getType() == AccountType.CREDIT_CARD && account.getCreditLimit() != null
                ? account.getCreditLimit().add(balance)
                : null;
    }
}

package com.andrewcorp.webwallet.ledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Totals of the accounts that are not archived, in the base currency of the user at today's NBU rates. Balances in
 * a currency without a rate are left out and the currency is listed in {@code unconvertedCurrencies}.
 *
 * @param netWorth            all balances: assets minus debts
 * @param available           balances of cash and card accounts
 * @param availableWithCredit {@code available} plus what can still be spent on credit cards: the limit plus the
 *                            balance, at least zero
 * @param readyToAssign       money of the budget accounts not assigned to envelopes in the current month, as in the
 *                            budget of the month
 */
public record AccountSummaryResponse(
        String currency,
        BigDecimal netWorth,
        BigDecimal available,
        BigDecimal availableWithCredit,
        BigDecimal readyToAssign,
        List<String> unconvertedCurrencies) {}

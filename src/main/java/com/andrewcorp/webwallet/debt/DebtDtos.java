package com.andrewcorp.webwallet.debt;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response bodies of the debts with people. */
public final class DebtDtos {

    private DebtDtos() {}

    /**
     * Debts with people in the base currency of the user. A person is the payee of the transfers with the debt
     * accounts: money sent to a debt account was lent (or repaid), money received from it was borrowed (or returned),
     * so the balance of a person is everything sent to them minus everything received from them.
     *
     * @param accountId the debt account new debt operations go to; null when the user has no debt account yet
     * @param people    everyone with a debt operation, the biggest balances first; a positive balance means the
     *                  person owes the user, a negative one that the user owes the person
     */
    public record DebtsResponse(
            String currency,
            @Schema(nullable = true) Long accountId,
            List<Person> people,
            List<String> unconvertedCurrencies) {}

    /**
     * One person with debt operations.
     *
     * @param payeeId    the payee; null for debt operations without one, which are grouped together
     * @param name       the payee's name, null without a payee
     * @param balance    sent minus received, in the base currency
     * @param lastDate   the date of the latest debt operation with the person
     * @param operations the number of debt operations with the person
     */
    public record Person(
            @Schema(nullable = true) Long payeeId,
            @Schema(nullable = true) String name,
            BigDecimal balance,
            LocalDate lastDate,
            int operations) {}
}

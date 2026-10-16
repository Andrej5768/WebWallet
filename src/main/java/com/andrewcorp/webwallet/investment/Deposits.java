package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.investment.HistoryRepository.Movement;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The money brought to brokerage accounts and taken away: their opening balances and the transfers from and to other
 * accounts. Transfers between two of the accounts are neither. The same definition for the portfolio and the history.
 */
@Component
public class Deposits {

    private final HistoryRepository history;

    public Deposits(HistoryRepository history) {
        this.history = history;
    }

    /** The movements of the ledger of the accounts, oldest first. */
    public List<Movement> movements(Map<Long, Account> scope) {
        return scope.isEmpty() ? List.of() : history.findMovements(scope.keySet());
    }

    /**
     * The deposits (negative: withdrawals) of the accounts, oldest first; the opening balances count on {@code first},
     * the first day with money on the accounts.
     */
    public static List<Deposit> of(Map<Long, Account> scope, List<Movement> movements, LocalDate first) {
        List<Deposit> deposits = new ArrayList<>();
        for (Account account : scope.values()) {
            if (account.getOpeningBalance().signum() != 0) {
                deposits.add(new Deposit(first, account.getCurrency(), account.getOpeningBalance()));
            }
        }
        for (Movement movement : movements) {
            Deposit deposit = deposit(movement, scope);
            if (deposit != null) {
                deposits.add(deposit);
            }
        }
        return deposits;
    }

    /** The deposit of a transfer between one of the accounts and another account, else null. */
    static Deposit deposit(Movement movement, Map<Long, Account> scope) {
        if (movement.getType() != TransactionType.TRANSFER) {
            return null;
        }
        Account source = scope.get(movement.getAccountId());
        Account target = movement.getToAccountId() != null ? scope.get(movement.getToAccountId()) : null;
        if (source != null && target == null) {
            return new Deposit(
                    movement.getDate(),
                    source.getCurrency(),
                    movement.getAmount().negate());
        }
        if (source == null && target != null) {
            return new Deposit(movement.getDate(), target.getCurrency(), movement.getToAmount());
        }
        return null;
    }

    /** Money brought to an account (negative: taken away), in its currency. */
    public record Deposit(LocalDate date, String currency, BigDecimal amount) {}
}

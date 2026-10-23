package com.andrewcorp.webwallet.debt;

import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.debt.DebtDtos.DebtsResponse;
import com.andrewcorp.webwallet.debt.DebtDtos.Person;
import com.andrewcorp.webwallet.debt.DebtRepository.DebtRow;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Debts with people, as in Zen Money: one debt account, and the person is the payee of the transfer. A transfer to a
 * debt account is money given to the person (lent, or a borrowed amount repaid), a transfer from it is money received
 * (borrowed, or a loan returned), so a person's balance is everything given minus everything received, in the base
 * currency at the rate of the day.
 */
@Service
public class DebtService {

    private final Converters converters;
    private final DebtRepository debts;
    private final AccountRepository accounts;

    public DebtService(Converters converters, DebtRepository debts, AccountRepository accounts) {
        this.converters = converters;
        this.debts = debts;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public DebtsResponse debts(Long userId) {
        CurrencyConverter converter = converters.base(userId);
        Map<Long, Totals> byPayee = new HashMap<>();
        for (DebtRow row : debts.findDebtTransfers(userId, TransactionType.TRANSFER, AccountType.DEBT)) {
            boolean toDebt = row.getToAccountType() == AccountType.DEBT;
            boolean fromDebt = row.getAccountType() == AccountType.DEBT;
            if (toDebt == fromDebt) {
                // Between two debt accounts: nothing changes with anyone
                continue;
            }
            BigDecimal amount = toDebt
                    ? converter.convert(
                            Optional.ofNullable(row.getToAmount()).orElse(row.getAmount()),
                            row.getToCurrency(),
                            row.getDate())
                    : converter
                            .convert(row.getAmount(), row.getCurrency(), row.getDate())
                            .negate();
            byPayee.computeIfAbsent(row.getPayeeId(), id -> new Totals(row.getPayeeName()))
                    .add(amount, row.getDate());
        }
        List<Person> people = new ArrayList<>();
        byPayee.forEach((payeeId, totals) ->
                people.add(new Person(payeeId, totals.name, totals.balance, totals.lastDate, totals.operations)));
        people.sort(Comparator.comparing((Person person) -> person.balance().abs())
                .reversed()
                .thenComparing(person -> Objects.requireNonNullElse(person.name(), "")));
        return new DebtsResponse(converter.target(), debtAccountId(userId), people, converter.unconverted());
    }

    /** The debt account new operations go to: the first one that is not archived. */
    private Long debtAccountId(Long userId) {
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> account.getType() == AccountType.DEBT && !account.isArchived())
                .map(Account::getId)
                .findFirst()
                .orElse(null);
    }

    private static final class Totals {
        private final String name;
        private BigDecimal balance = BigDecimal.ZERO;
        private LocalDate lastDate;
        private int operations;

        private Totals(String name) {
            this.name = name;
        }

        private void add(BigDecimal amount, LocalDate date) {
            balance = balance.add(amount);
            lastDate = lastDate == null || date.isAfter(lastDate) ? date : lastDate;
            operations++;
        }
    }
}

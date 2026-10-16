package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastDay;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastEvent;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastResponse;
import com.andrewcorp.webwallet.planning.PlanningRepository.DayChange;
import com.andrewcorp.webwallet.planning.PlanningRepository.Expense;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forecast of the money available now: the balance of cash and cards (not archived) in the base currency at today's
 * rates, the same as {@code available} of the accounts summary. Ahead of today it goes down by the everyday spending
 * and changes by the pending occurrences of recurring payments.
 */
@Service
public class ForecastService {

    /** The everyday spending is the average of this many last days. */
    static final int SPENDING_DAYS = 90;

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final PlanningRepository planning;
    private final RecurringService recurringService;
    private final RecurringOccurrenceRepository occurrences;
    private final UserService users;
    private final ExchangeRateService exchangeRates;
    private final Clock clock;

    public ForecastService(
            AccountRepository accounts,
            AccountService accountService,
            PlanningRepository planning,
            RecurringService recurringService,
            RecurringOccurrenceRepository occurrences,
            UserService users,
            ExchangeRateService exchangeRates,
            Clock clock) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.planning = planning;
        this.recurringService = recurringService;
        this.occurrences = occurrences;
        this.users = users;
        this.exchangeRates = exchangeRates;
        this.clock = clock;
    }

    /**
     * Balances from the first day of the current month to the date: the actual ones up to today, then the forecast.
     * Pending occurrences due today or overdue are expected tomorrow.
     */
    @Transactional
    public ForecastResponse forecast(Long userId, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.withDayOfMonth(1);
        CurrencyConverter converter =
                exchangeRates.converterTo(users.get(userId).getBaseCurrency());
        Map<Long, Account> scope = new HashMap<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            if (!account.isArchived()
                    && (account.getType() == AccountType.CASH || account.getType() == AccountType.CARD)) {
                scope.put(account.getId(), account);
            }
        }
        // Pending occurrences first: matching them may link transactions the everyday spending must leave out
        List<OccurrenceResponse> pending = recurringService.pending(userId, to);

        // Today's balance, then back to the first day by taking away the changes of the following days
        Map<Long, BigDecimal> balances = accountService.balances(userId);
        TreeMap<LocalDate, BigDecimal> changes = new TreeMap<>();
        Stream.concat(
                        planning.findSourceChanges(userId, first).stream(),
                        planning.findTargetChanges(userId, first).stream())
                .filter(change -> scope.containsKey(change.getAccountId()))
                .forEach(change ->
                        changes.merge(change.getDate(), converted(change, scope, converter, today), BigDecimal::add));
        BigDecimal balance = BigDecimal.ZERO;
        for (Account account : scope.values()) {
            BigDecimal accountBalance = balances.getOrDefault(account.getId(), account.getOpeningBalance());
            balance = balance.add(converter.convert(accountBalance, account.getCurrency(), today));
        }
        // Transactions entered ahead of time are part of the forecast, not of today's balance
        for (BigDecimal later : changes.tailMap(today, false).values()) {
            balance = balance.subtract(later);
        }
        BigDecimal todayBalance = balance;
        List<ForecastDay> actual = new ArrayList<>();
        for (LocalDate date = today; !date.isBefore(first); date = date.minusDays(1)) {
            actual.add(0, new ForecastDay(date, balance, true));
            balance = balance.subtract(changes.getOrDefault(date, BigDecimal.ZERO));
        }

        BigDecimal daily = dailySpending(userId, scope, converter, today);
        List<ForecastEvent> events = new ArrayList<>();
        Map<LocalDate, BigDecimal> eventsByDay = new HashMap<>();
        for (OccurrenceResponse occurrence : pending) {
            BigDecimal amount = effect(occurrence, scope, converter, today);
            if (amount.signum() == 0 || occurrence.date().isAfter(to)) {
                continue;
            }
            events.add(new ForecastEvent(
                    occurrence.date(),
                    occurrence.recurringId(),
                    occurrence.name(),
                    amount,
                    occurrence.overdue(),
                    occurrence.categoryId()));
            LocalDate expected = occurrence.date().isAfter(today) ? occurrence.date() : today.plusDays(1);
            eventsByDay.merge(expected, amount, BigDecimal::add);
        }

        List<ForecastDay> days = new ArrayList<>(actual);
        ForecastDay lowest = null;
        balance = todayBalance;
        for (LocalDate date = today.plusDays(1); !date.isAfter(to); date = date.plusDays(1)) {
            balance = balance.subtract(daily)
                    .add(eventsByDay.getOrDefault(date, BigDecimal.ZERO))
                    .add(changes.getOrDefault(date, BigDecimal.ZERO));
            ForecastDay day = new ForecastDay(date, balance, false);
            days.add(day);
            if (lowest == null || balance.compareTo(lowest.balance()) < 0) {
                lowest = day;
            }
        }
        return new ForecastResponse(
                converter.target(), today, todayBalance, daily, days, events, lowest, converter.unconverted());
    }

    /**
     * Average daily expenses of the accounts over the last {@value #SPENDING_DAYS} days (or since the first
     * transaction), without the payments of recurring payments: those are forecast by their schedule.
     */
    private BigDecimal dailySpending(
            Long userId, Map<Long, Account> scope, CurrencyConverter converter, LocalDate today) {
        LocalDate firstDate = planning.findFirstDate(userId);
        if (firstDate == null || firstDate.isAfter(today)) {
            return BigDecimal.ZERO;
        }
        LocalDate from = today.minusDays(SPENDING_DAYS - 1);
        if (firstDate.isAfter(from)) {
            from = firstDate;
        }
        Set<Long> recurring = occurrences.findLinkedTransactionIds(userId);
        BigDecimal total = BigDecimal.ZERO;
        for (Expense expense : planning.findExpenses(userId, from, today)) {
            Account account = scope.get(expense.getAccountId());
            if (account != null && !recurring.contains(expense.getId())) {
                total = total.add(converter.convert(expense.getAmount(), account.getCurrency(), expense.getDate()));
            }
        }
        long days = ChronoUnit.DAYS.between(from, today) + 1;
        return total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    /** How the occurrence changes the money of the accounts, in the base currency; 0 between two of them. */
    private static BigDecimal effect(
            OccurrenceResponse occurrence, Map<Long, Account> scope, CurrencyConverter converter, LocalDate today) {
        boolean fromScope = scope.containsKey(occurrence.accountId());
        BigDecimal amount = converter.convert(occurrence.amount(), occurrence.currency(), today);
        if (occurrence.type() != TransactionType.TRANSFER) {
            if (!fromScope) {
                return BigDecimal.ZERO;
            }
            return occurrence.type() == TransactionType.INCOME ? amount : amount.negate();
        }
        Account target = scope.get(occurrence.toAccountId());
        if (fromScope == (target != null)) {
            return BigDecimal.ZERO;
        }
        return fromScope ? amount.negate() : converter.convert(occurrence.toAmount(), target.getCurrency(), today);
    }

    private static BigDecimal converted(
            DayChange change, Map<Long, Account> scope, CurrencyConverter converter, LocalDate today) {
        return converter.convert(
                change.getChange(), scope.get(change.getAccountId()).getCurrency(), today);
    }
}

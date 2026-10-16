package com.andrewcorp.webwallet.report;

import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.report.ReportDtos.CategorySpending;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.report.ReportDtos.MonthIncomeExpense;
import com.andrewcorp.webwallet.report.ReportDtos.MonthNetWorth;
import com.andrewcorp.webwallet.report.ReportDtos.NetWorthResponse;
import com.andrewcorp.webwallet.report.ReportDtos.PayeeSpending;
import com.andrewcorp.webwallet.report.ReportDtos.PayeesResponse;
import com.andrewcorp.webwallet.report.ReportDtos.SpendingResponse;
import com.andrewcorp.webwallet.report.ReportRepository.MonthChange;
import com.andrewcorp.webwallet.report.ReportRepository.PayeeRow;
import com.andrewcorp.webwallet.report.ReportRepository.SplitRow;
import com.andrewcorp.webwallet.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports in the base currency of the user; amounts in other currencies are converted at the NBU rate of the
 * operation date. Transfers between own accounts are neither income nor expense.
 */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final UserService users;
    private final ExchangeRateService exchangeRates;
    private final Clock clock;

    public ReportService(
            ReportRepository reports,
            CategoryRepository categories,
            AccountRepository accounts,
            UserService users,
            ExchangeRateService exchangeRates,
            Clock clock) {
        this.reports = reports;
        this.categories = categories;
        this.accounts = accounts;
        this.users = users;
        this.exchangeRates = exchangeRates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SpendingResponse spending(Long userId, LocalDate from, LocalDate to) {
        CurrencyConverter converter = converter(userId);
        Map<Long, BigDecimal> byCategory = new HashMap<>();
        BigDecimal uncategorized = BigDecimal.ZERO;
        for (SplitRow row : reports.findSplits(userId, from, to, TransactionType.TRANSFER)) {
            if (kind(row) != CategoryKind.EXPENSE) {
                continue;
            }
            BigDecimal amount = signed(row, converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
            if (row.getCategoryId() == null) {
                uncategorized = uncategorized.add(amount);
            } else {
                byCategory.merge(row.getCategoryId(), amount, BigDecimal::add);
            }
        }
        BigDecimal total = byCategory.values().stream().reduce(uncategorized, BigDecimal::add);

        List<Category> expenseCategories = categories.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(category -> category.getKind() == CategoryKind.EXPENSE)
                .toList();
        List<CategorySpending> rows = new ArrayList<>();
        for (Category parent : expenseCategories) {
            if (parent.getParentId() != null) {
                continue;
            }
            List<CategorySpending> children = expenseCategories.stream()
                    .filter(child -> parent.getId().equals(child.getParentId()))
                    .filter(child -> byCategory.containsKey(child.getId()))
                    .map(child -> spending(child, byCategory.get(child.getId()), total, List.of()))
                    .sorted(Comparator.comparing(CategorySpending::amount).reversed())
                    .toList();
            BigDecimal amount = children.stream()
                    .map(CategorySpending::amount)
                    .reduce(byCategory.getOrDefault(parent.getId(), BigDecimal.ZERO), BigDecimal::add);
            if (amount.signum() != 0) {
                rows.add(spending(parent, amount, total, children));
            }
        }
        rows.sort(Comparator.comparing(CategorySpending::amount).reversed());
        return new SpendingResponse(from, to, converter.target(), total, uncategorized, rows, converter.unconverted());
    }

    @Transactional(readOnly = true)
    public PayeesResponse payees(Long userId, LocalDate from, LocalDate to, int limit) {
        CurrencyConverter converter = converter(userId);
        Map<Long, String> names = new HashMap<>();
        Map<Long, BigDecimal> amounts = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (PayeeRow row : reports.findPayeeExpenses(userId, from, to)) {
            names.put(row.getPayeeId(), row.getPayeeName());
            amounts.merge(
                    row.getPayeeId(),
                    converter.convert(row.getAmount(), row.getCurrency(), row.getDate()),
                    BigDecimal::add);
            counts.merge(row.getPayeeId(), (int) row.getTransactions(), Integer::sum);
        }
        List<PayeeSpending> payees = amounts.entrySet().stream()
                .map(entry -> new PayeeSpending(
                        entry.getKey(), names.get(entry.getKey()), entry.getValue(), counts.get(entry.getKey())))
                .sorted(Comparator.comparing(PayeeSpending::amount).reversed().thenComparing(PayeeSpending::name))
                .limit(limit)
                .toList();
        return new PayeesResponse(from, to, converter.target(), payees, converter.unconverted());
    }

    @Transactional(readOnly = true)
    public IncomeExpenseResponse incomeExpense(Long userId, LocalDate from, LocalDate to) {
        CurrencyConverter converter = converter(userId);
        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            byMonth.put(month, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (SplitRow row : reports.findSplits(userId, from, to, TransactionType.TRANSFER)) {
            BigDecimal amount = signed(row, converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
            BigDecimal[] totals = byMonth.get(YearMonth.from(row.getDate()));
            int index = kind(row) == CategoryKind.INCOME ? 0 : 1;
            totals[index] = totals[index].add(amount);
        }
        List<MonthIncomeExpense> months = byMonth.entrySet().stream()
                .map(entry -> monthIncomeExpense(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
        BigDecimal income = months.stream().map(MonthIncomeExpense::income).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = months.stream().map(MonthIncomeExpense::expense).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new IncomeExpenseResponse(
                from,
                to,
                converter.target(),
                income,
                expense,
                income.subtract(expense),
                savingsRate(income, expense),
                months,
                converter.unconverted());
    }

    /** Balances of all accounts at the end of every month of the period. */
    @Transactional(readOnly = true)
    public NetWorthResponse netWorth(Long userId, LocalDate from, LocalDate to) {
        CurrencyConverter converter = converter(userId);
        LocalDate today = LocalDate.now(clock);
        Map<Long, Account> accountsById = new HashMap<>();
        Map<Long, BigDecimal> balances = new HashMap<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            accountsById.put(account.getId(), account);
            balances.put(account.getId(), account.getOpeningBalance());
        }
        TreeMap<YearMonth, List<MonthChange>> pending = new TreeMap<>();
        Stream.concat(reports.findSourceChanges(userId, to).stream(), reports.findTargetChanges(userId, to).stream())
                .forEach(change -> pending.computeIfAbsent(
                                YearMonth.of(change.getYear(), change.getMonth()), m -> new ArrayList<>())
                        .add(change));

        List<MonthNetWorth> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            LocalDate end = month.atEndOfMonth();
            SortedMap<YearMonth, List<MonthChange>> due = pending.headMap(month, true);
            due.values()
                    .forEach(changes -> changes.forEach(
                            change -> balances.merge(change.getAccountId(), change.getChange(), BigDecimal::add)));
            due.clear();
            LocalDate rateDate = end.isAfter(today) ? today : end;
            BigDecimal assets = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> balance : balances.entrySet()) {
                BigDecimal converted = converter.convert(
                        balance.getValue(), accountsById.get(balance.getKey()).getCurrency(), rateDate);
                if (converted.signum() >= 0) {
                    assets = assets.add(converted);
                } else {
                    liabilities = liabilities.add(converted);
                }
            }
            months.add(new MonthNetWorth(month, assets, liabilities, assets.add(liabilities)));
        }
        return new NetWorthResponse(converter.target(), months, converter.unconverted());
    }

    /**
     * Whether a part counts as spending or income: by its category, so that a refund (an income in an expense
     * category) reduces the spending; without a category, by the transaction type.
     */
    public static CategoryKind kind(SplitRow row) {
        if (row.getKind() != null) {
            return row.getKind();
        }
        return CategoryKind.of(row.getType());
    }

    /** Positive when the type agrees with the kind, negative for a refund or a returned income. */
    public static BigDecimal signed(SplitRow row, BigDecimal amount) {
        boolean income = row.getType() == TransactionType.INCOME;
        return income == (kind(row) == CategoryKind.INCOME) ? amount : amount.negate();
    }

    private CurrencyConverter converter(Long userId) {
        return exchangeRates.converterTo(users.get(userId).getBaseCurrency());
    }

    private static CategorySpending spending(
            Category category, BigDecimal amount, BigDecimal total, List<CategorySpending> children) {
        BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO : amount.divide(total, 4, RoundingMode.HALF_UP);
        return new CategorySpending(category.getId(), category.getName(), amount, share, children);
    }

    private static MonthIncomeExpense monthIncomeExpense(YearMonth month, BigDecimal income, BigDecimal expense) {
        return new MonthIncomeExpense(month, income, expense, income.subtract(expense), savingsRate(income, expense));
    }

    private static BigDecimal savingsRate(BigDecimal income, BigDecimal expense) {
        return income.signum() <= 0 ? null : income.subtract(expense).divide(income, 4, RoundingMode.HALF_UP);
    }
}

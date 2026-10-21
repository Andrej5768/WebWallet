package com.andrewcorp.webwallet.analytics;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.Anomaly;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryChange;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryTrend;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareDay;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.Destination;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.MonthSavings;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.MonthTotal;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.NeedsWantsResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.Part;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.PlanResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.PlanRow;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.SavingsResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.TrendsResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.CategoryRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.common.util.Money;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.category.CategoryTree;
import com.andrewcorp.webwallet.ledger.category.Necessity;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.planning.OccurrenceStatus;
import com.andrewcorp.webwallet.planning.PlanningRepository;
import com.andrewcorp.webwallet.planning.PlanningRepository.CategoryAmount;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringService;
import com.andrewcorp.webwallet.report.ReportRepository;
import com.andrewcorp.webwallet.report.ReportRepository.BoundaryTransferRow;
import com.andrewcorp.webwallet.report.ReportRepository.SplitRow;
import com.andrewcorp.webwallet.report.ReportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analytics for the reports in the base currency of the user. Like the reports, a refund (an income in an expense
 * category) reduces the spending, and transfers between own accounts are neither income nor expense.
 */
@Service
public class AnalyticsService {

    private static final BigDecimal NEEDS_TARGET = new BigDecimal("0.5");
    private static final BigDecimal WANTS_TARGET = new BigDecimal("0.3");
    private static final BigDecimal SAVINGS_TARGET = new BigDecimal("0.2");
    /** A month is an anomaly of a category when it spent this many times the average of the other months. */
    private static final BigDecimal ANOMALY_RATIO = new BigDecimal("1.7");
    /**
     * ...and at least this part of the average monthly total, so that small categories, where a few purchases double
     * the spending, are not reported.
     */
    private static final BigDecimal ANOMALY_MIN_SHARE = new BigDecimal("0.02");

    private final Converters converters;
    private final ReportRepository reports;
    private final CategoryRepository categories;
    private final BudgetService budgetService;
    private final RecurringService recurringService;
    private final PlanningRepository planning;
    private final AccountRepository accounts;
    private final Clock clock;

    public AnalyticsService(
            Converters converters,
            ReportRepository reports,
            CategoryRepository categories,
            BudgetService budgetService,
            RecurringService recurringService,
            PlanningRepository planning,
            AccountRepository accounts,
            Clock clock) {
        this.converters = converters;
        this.reports = reports;
        this.categories = categories;
        this.budgetService = budgetService;
        this.recurringService = recurringService;
        this.planning = planning;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Plan vs actual of the envelopes, with a forecast of the spending at the end of the month: the everyday spending
     * continued at its pace so far, the payments of recurring payments already made, and those still due.
     */
    @Transactional
    public PlanResponse plan(Long userId, YearMonth month) {
        MonthResponse budget = budgetService.month(userId, month);
        YearMonth current = YearMonth.now(clock);
        int daysInMonth = month.lengthOfMonth();
        int daysElapsed = month.isBefore(current)
                ? daysInMonth
                : month.isAfter(current) ? 0 : LocalDate.now(clock).getDayOfMonth();
        Map<Long, BigDecimal> plannedRemaining = new HashMap<>();
        Map<Long, BigDecimal> recurringActual = new HashMap<>();
        if (month.equals(current)) {
            CurrencyConverter converter = converter(userId);
            LocalDate today = LocalDate.now(clock);
            Set<Long> budgetAccounts = accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                    .filter(Account::isInBudget)
                    .map(Account::getId)
                    .collect(Collectors.toSet());
            for (OccurrenceResponse occurrence :
                    recurringService.occurrences(userId, month.atDay(1), month.atEndOfMonth())) {
                // Only payments of the budget accounts count against the envelopes
                if (occurrence.status() == OccurrenceStatus.PENDING
                        && occurrence.type() == TransactionType.EXPENSE
                        && occurrence.categoryId() != null
                        && budgetAccounts.contains(occurrence.accountId())) {
                    plannedRemaining.merge(
                            occurrence.categoryId(),
                            converter.convert(occurrence.amount(), occurrence.currency(), today),
                            BigDecimal::add);
                }
            }
            for (CategoryAmount paid : planning.findRecurringExpenses(userId, month.atDay(1), today)) {
                recurringActual.merge(
                        paid.getCategoryId(),
                        converter.convert(paid.getAmount(), paid.getCurrency(), paid.getDate()),
                        BigDecimal::add);
            }
        }
        PlanForecast forecast =
                new PlanForecast(month, current, daysElapsed, daysInMonth, plannedRemaining, recurringActual);
        List<PlanRow> rows = budget.categories().stream()
                .map(forecast::row)
                .filter(AnalyticsService::hasValues)
                .toList();
        BigDecimal actual = budget.activity().negate();
        return new PlanResponse(
                month,
                budget.currency(),
                daysElapsed,
                daysInMonth,
                budget.assigned(),
                actual,
                budget.assigned().subtract(actual),
                sum(rows, PlanRow::forecast),
                rows,
                budget.unconvertedCurrencies());
    }

    /** Spending of two periods day by day and per top-level category. */
    @Transactional(readOnly = true)
    public CompareResponse compare(Long userId, LocalDate fromA, LocalDate toA, LocalDate fromB, LocalDate toB) {
        CurrencyConverter converter = converter(userId);
        CategoryTree tree = categories.tree(userId);
        PeriodSpending a = spending(userId, fromA, toA, converter, tree);
        PeriodSpending b = spending(userId, fromB, toB, converter, tree);

        List<CompareDay> days = new ArrayList<>();
        for (int day = 0; day < Math.max(a.daily.length, b.daily.length); day++) {
            days.add(new CompareDay(day + 1, a.cumulative(day), b.cumulative(day)));
        }
        Set<Long> categoryIds = new HashSet<>(a.byCategory.keySet());
        categoryIds.addAll(b.byCategory.keySet());
        List<CategoryChange> changes = categoryIds.stream()
                .map(id -> {
                    BigDecimal amountA = a.byCategory.getOrDefault(id, BigDecimal.ZERO);
                    BigDecimal amountB = b.byCategory.getOrDefault(id, BigDecimal.ZERO);
                    BigDecimal change = amountA.subtract(amountB);
                    return new CategoryChange(
                            id, tree.name(id), amountA, amountB, change, Money.share(change, amountB));
                })
                .filter(change -> change.a().signum() != 0 || change.b().signum() != 0)
                .sorted(Comparator.comparing(
                                (CategoryChange change) -> change.change().abs())
                        .reversed())
                .toList();
        BigDecimal totalA = a.total();
        BigDecimal totalB = b.total();
        BigDecimal change = totalA.subtract(totalB);
        return new CompareResponse(
                fromA,
                toA,
                fromB,
                toB,
                converter.target(),
                totalA,
                totalB,
                change,
                Money.share(change, totalB),
                days,
                changes,
                converter.unconverted());
    }

    /** Spending per top-level category and month, with the months far above the average of a category. */
    @Transactional(readOnly = true)
    public TrendsResponse trends(Long userId, YearMonth from, YearMonth to) {
        CurrencyConverter converter = converter(userId);
        CategoryTree tree = categories.tree(userId);
        List<YearMonth> months = months(from, to);
        int count = months.size();
        Map<Long, BigDecimal[]> byCategory = new HashMap<>();
        for (SplitRow row : reports.findSplits(userId, from.atDay(1), to.atEndOfMonth(), TransactionType.TRANSFER)) {
            if (ReportService.kind(row) != CategoryKind.EXPENSE) {
                continue;
            }
            BigDecimal amount = expense(row, converter);
            int index = (int) ChronoUnit.MONTHS.between(from, YearMonth.from(row.getDate()));
            BigDecimal[] amounts = byCategory.computeIfAbsent(tree.topLevel(row.getCategoryId()), id -> zeros(count));
            amounts[index] = amounts[index].add(amount);
        }

        List<MonthTotal> totals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            totals.add(new MonthTotal(
                    months.get(i),
                    byCategory.values().stream()
                            .map(amounts -> amounts[index])
                            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        BigDecimal average =
                divide(totals.stream().map(MonthTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add), count);
        BigDecimal minAnomaly = average.multiply(ANOMALY_MIN_SHARE);

        List<CategoryTrend> trends = byCategory.entrySet().stream()
                .map(entry -> trend(entry.getKey(), tree.name(entry.getKey()), entry.getValue(), months, minAnomaly))
                .filter(trend -> trend.total().signum() != 0)
                .sorted(Comparator.comparing(CategoryTrend::total).reversed())
                .toList();
        return new TrendsResponse(from, to, converter.target(), totals, average, trends, converter.unconverted());
    }

    /** Income minus expenses per month, the savings rate, and where the saved money was moved. */
    @Transactional(readOnly = true)
    public SavingsResponse savings(Long userId, YearMonth from, YearMonth to, BigDecimal target) {
        CurrencyConverter converter = converter(userId);
        LocalDate start = from.atDay(1);
        LocalDate end = to.atEndOfMonth();
        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        Map<YearMonth, Map<Long, Destination>> destinations = new HashMap<>();
        for (YearMonth month : months(from, to)) {
            byMonth.put(month, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            destinations.put(month, new HashMap<>());
        }
        for (SplitRow row : reports.findSplits(userId, start, end, TransactionType.TRANSFER)) {
            BigDecimal amount =
                    ReportService.signed(row, converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
            BigDecimal[] totals = byMonth.get(YearMonth.from(row.getDate()));
            int index = ReportService.kind(row) == CategoryKind.INCOME ? 0 : 1;
            totals[index] = totals[index].add(amount);
        }
        for (BoundaryTransferRow row :
                reports.findBudgetBoundaryTransfers(userId, start, end, TransactionType.TRANSFER)) {
            BigDecimal amount = converter.convert(row.getAmount(), row.getCurrency(), row.getDate());
            Long accountId = row.getFromBudget() ? row.getToAccountId() : row.getAccountId();
            String name = row.getFromBudget() ? row.getToAccountName() : row.getAccountName();
            BigDecimal moved = row.getFromBudget() ? amount : amount.negate();
            destinations
                    .get(YearMonth.from(row.getDate()))
                    .merge(
                            accountId,
                            new Destination(accountId, name, moved),
                            (x, y) ->
                                    new Destination(accountId, name, x.amount().add(y.amount())));
        }

        YearMonth current = YearMonth.now(clock);
        List<MonthSavings> months = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal completeIncome = BigDecimal.ZERO;
        BigDecimal completeNet = BigDecimal.ZERO;
        MonthSavings best = null;
        for (Map.Entry<YearMonth, BigDecimal[]> entry : byMonth.entrySet()) {
            BigDecimal income = entry.getValue()[0];
            BigDecimal expense = entry.getValue()[1];
            BigDecimal net = income.subtract(expense);
            cumulative = cumulative.add(net);
            boolean complete = entry.getKey().isBefore(current);
            if (complete) {
                completeIncome = completeIncome.add(income);
                completeNet = completeNet.add(net);
            }
            List<Destination> moved = destinations.get(entry.getKey()).values().stream()
                    .filter(destination -> destination.amount().signum() != 0)
                    .sorted(Comparator.comparing(Destination::amount).reversed())
                    .toList();
            MonthSavings month = new MonthSavings(
                    entry.getKey(),
                    income,
                    expense,
                    net,
                    Money.positiveShare(net, income),
                    cumulative,
                    complete,
                    moved);
            months.add(month);
            if (net.signum() > 0 && (best == null || net.compareTo(best.net()) > 0)) {
                best = month;
            }
        }
        return new SavingsResponse(
                from,
                to,
                converter.target(),
                target,
                Money.positiveShare(completeNet, completeIncome),
                cumulative,
                best != null ? best.month() : null,
                months,
                converter.unconverted());
    }

    /** 50/30/20: needs, wants and savings as parts of the income of the period. */
    @Transactional(readOnly = true)
    public NeedsWantsResponse needsWants(Long userId, LocalDate from, LocalDate to) {
        CurrencyConverter converter = converter(userId);
        CategoryTree tree = categories.tree(userId);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal needs = BigDecimal.ZERO;
        BigDecimal wants = BigDecimal.ZERO;
        BigDecimal unclassified = BigDecimal.ZERO;
        for (SplitRow row : reports.findSplits(userId, from, to, TransactionType.TRANSFER)) {
            BigDecimal amount =
                    ReportService.signed(row, converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
            if (ReportService.kind(row) == CategoryKind.INCOME) {
                income = income.add(amount);
                continue;
            }
            Necessity necessity = tree.necessity(row.getCategoryId());
            if (necessity == Necessity.NEEDS) {
                needs = needs.add(amount);
            } else if (necessity == Necessity.WANTS) {
                wants = wants.add(amount);
            } else {
                unclassified = unclassified.add(amount);
            }
        }
        BigDecimal savings = income.subtract(needs).subtract(wants).subtract(unclassified);
        return new NeedsWantsResponse(
                from,
                to,
                converter.target(),
                income,
                new Part(needs, Money.positiveShare(needs, income), NEEDS_TARGET),
                new Part(wants, Money.positiveShare(wants, income), WANTS_TARGET),
                new Part(savings, Money.positiveShare(savings, income), SAVINGS_TARGET),
                new Part(unclassified, Money.positiveShare(unclassified, income), null),
                converter.unconverted());
    }

    private record PlanForecast(
            YearMonth month,
            YearMonth current,
            int daysElapsed,
            int daysInMonth,
            Map<Long, BigDecimal> plannedRemaining,
            Map<Long, BigDecimal> recurringActual) {

        PlanRow row(CategoryRow row) {
            List<PlanRow> children = row.children().stream()
                    .map(this::row)
                    .filter(AnalyticsService::hasValues)
                    .toList();
            BigDecimal planned = row.assigned();
            BigDecimal actual = row.activity().negate();
            BigDecimal remaining = children.stream()
                    .map(PlanRow::plannedRemaining)
                    .reduce(plannedRemaining.getOrDefault(row.categoryId(), BigDecimal.ZERO), BigDecimal::add);
            return new PlanRow(
                    row.categoryId(),
                    row.name(),
                    planned,
                    actual,
                    planned.subtract(actual),
                    Money.share(actual, planned),
                    remaining,
                    forecast(planned, actual, recurring(row), remaining),
                    children);
        }

        /** Spending of the category and its subcategories on recurring payments this month. */
        private BigDecimal recurring(CategoryRow row) {
            return row.children().stream()
                    .map(this::recurring)
                    .reduce(recurringActual.getOrDefault(row.categoryId(), BigDecimal.ZERO), BigDecimal::add);
        }

        /** Only the everyday spending goes on at its pace; recurring payments come once, by their schedule. */
        private BigDecimal forecast(BigDecimal planned, BigDecimal actual, BigDecimal recurring, BigDecimal remaining) {
            if (month.isBefore(current)) {
                return actual;
            }
            if (month.isAfter(current)) {
                return planned;
            }
            BigDecimal everyday = actual.subtract(recurring).max(BigDecimal.ZERO);
            BigDecimal pace = everyday.multiply(BigDecimal.valueOf(daysInMonth - daysElapsed))
                    .divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP);
            return actual.add(pace).add(remaining);
        }
    }

    private static boolean hasValues(PlanRow row) {
        return row.planned().signum() != 0
                || row.actual().signum() != 0
                || row.plannedRemaining().signum() != 0;
    }

    /** Expense categories of a period: totals per top-level category (null key: no category) and per day. */
    private record PeriodSpending(Map<Long, BigDecimal> byCategory, BigDecimal[] daily) {

        BigDecimal total() {
            return Arrays.stream(daily).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The spending from the first day up to the day, null after the end of the period. */
        BigDecimal cumulative(int day) {
            if (day >= daily.length) {
                return null;
            }
            return Arrays.stream(daily, 0, day + 1).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private PeriodSpending spending(
            Long userId, LocalDate from, LocalDate to, CurrencyConverter converter, CategoryTree tree) {
        Map<Long, BigDecimal> byCategory = new HashMap<>();
        BigDecimal[] daily = zeros((int) ChronoUnit.DAYS.between(from, to) + 1);
        for (SplitRow row : reports.findSplits(userId, from, to, TransactionType.TRANSFER)) {
            if (ReportService.kind(row) != CategoryKind.EXPENSE) {
                continue;
            }
            BigDecimal amount = expense(row, converter);
            byCategory.merge(tree.topLevel(row.getCategoryId()), amount, BigDecimal::add);
            int day = (int) ChronoUnit.DAYS.between(from, row.getDate());
            daily[day] = daily[day].add(amount);
        }
        return new PeriodSpending(byCategory, daily);
    }

    private static CategoryTrend trend(
            Long categoryId, String name, BigDecimal[] amounts, List<YearMonth> months, BigDecimal minAnomaly) {
        BigDecimal total = Arrays.stream(amounts).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Anomaly> anomalies = new ArrayList<>();
        if (amounts.length > 1) {
            for (int i = 0; i < amounts.length; i++) {
                BigDecimal amount = amounts[i];
                BigDecimal others = divide(total.subtract(amount), amounts.length - 1);
                if (others.signum() > 0
                        && amount.compareTo(minAnomaly) >= 0
                        && amount.compareTo(others.multiply(ANOMALY_RATIO)) > 0) {
                    anomalies.add(
                            new Anomaly(months.get(i), amount, others, amount.divide(others, 2, RoundingMode.HALF_UP)));
                }
            }
        }
        return new CategoryTrend(categoryId, name, List.of(amounts), total, divide(total, amounts.length), anomalies);
    }

    /** Spending of an expense-kind part in the base currency; negative for a refund. */
    private static BigDecimal expense(SplitRow row, CurrencyConverter converter) {
        return ReportService.signed(row, converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
    }

    /** The expense categories of the user: the top level of each, names and necessities. */
    private CurrencyConverter converter(Long userId) {
        return converters.base(userId);
    }

    private static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static BigDecimal[] zeros(int length) {
        BigDecimal[] values = new BigDecimal[length];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static BigDecimal divide(BigDecimal amount, int count) {
        return amount.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /** {@code part / whole}, null when the whole is zero. */
    private static BigDecimal sum(List<PlanRow> rows, Function<PlanRow, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.CategoryRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.MoveRequest;
import com.andrewcorp.webwallet.budget.BudgetRepository.SplitRow;
import com.andrewcorp.webwallet.budget.BudgetRepository.TransferRow;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.category.CategoryTree;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envelope budget (similar to YNAB) in the base currency of the user. Only the money of budget accounts is
 * distributed. The positive rest of an envelope moves to the next month; overspending is taken from the money ready
 * to assign of the next month. Credit cards are treated like cash: spending on them uses the envelopes, and paying
 * the card back is a transfer within the budget.
 */
@Service
public class BudgetService {

    private final Converters converters;
    private final BudgetRepository budget;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final Clock clock;

    public BudgetService(
            Converters converters,
            BudgetRepository budget,
            CategoryRepository categories,
            AccountRepository accounts,
            Clock clock) {
        this.converters = converters;
        this.budget = budget;
        this.categories = categories;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MonthResponse month(Long userId, YearMonth month) {
        CurrencyConverter converter = converters.base(userId);
        return new Calculation(userId, month, converter).result();
    }

    @Transactional
    public MonthResponse assign(Long userId, YearMonth month, Long categoryId, BigDecimal amount) {
        Category category = categories.findByIdAndUserId(categoryId, userId).orElseThrow(ApiException::notFound);
        if (category.getKind() != CategoryKind.EXPENSE) {
            throw ApiException.invalid("categoryId", "error.budget.expenseCategory");
        }
        setAssigned(userId, month.atDay(1), category, amount);
        budget.flush();
        return month(userId, month);
    }

    /** Lowers the own assignment of one envelope and raises that of another by the amount, both or neither. */
    @Transactional
    public MonthResponse move(Long userId, YearMonth month, MoveRequest request) {
        Category from = expenseCategory(userId, request.fromCategoryId(), "fromCategoryId");
        Category to = expenseCategory(userId, request.toCategoryId(), "toCategoryId");
        if (from.getId().equals(to.getId())) {
            throw ApiException.invalid("toCategoryId", "error.budget.sameCategory");
        }
        LocalDate first = month.atDay(1);
        setAssigned(userId, first, from, assigned(userId, first, from).subtract(request.amount()));
        setAssigned(userId, first, to, assigned(userId, first, to).add(request.amount()));
        budget.flush();
        return month(userId, month);
    }

    /**
     * Assigns to every envelope without an own assignment this month what it had assigned the previous month.
     * Envelopes already assigned this month and archived categories are left as they are.
     */
    @Transactional
    public MonthResponse copyPrevious(Long userId, YearMonth month) {
        LocalDate first = month.atDay(1);
        Map<Long, BigDecimal> current = new HashMap<>();
        for (BudgetAssignment assignment : budget.findByMonth(userId, first)) {
            current.put(assignment.getCategory().getId(), assignment.getAmount());
        }
        for (BudgetAssignment previous :
                budget.findByMonth(userId, month.minusMonths(1).atDay(1))) {
            Category category = previous.getCategory();
            if (category.getKind() == CategoryKind.EXPENSE
                    && !category.isArchived()
                    && current.getOrDefault(category.getId(), BigDecimal.ZERO).signum() == 0) {
                setAssigned(userId, first, category, previous.getAmount());
            }
        }
        budget.flush();
        return month(userId, month);
    }

    private Category expenseCategory(Long userId, Long categoryId, String field) {
        Category category = categories
                .findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
        if (category.getKind() != CategoryKind.EXPENSE) {
            throw ApiException.invalid(field, "error.budget.expenseCategory");
        }
        return category;
    }

    /** The own assignment of the envelope in the month, zero when there is none. */
    private BigDecimal assigned(Long userId, LocalDate month, Category category) {
        return budget.findByUserIdAndMonthAndCategory(userId, month, category)
                .map(BudgetAssignment::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    private void setAssigned(Long userId, LocalDate month, Category category, BigDecimal amount) {
        budget.findByUserIdAndMonthAndCategory(userId, month, category)
                .ifPresentOrElse(
                        assignment -> {
                            if (amount.signum() == 0) {
                                budget.delete(assignment);
                            } else {
                                assignment.setAmount(amount);
                            }
                        },
                        () -> {
                            if (amount.signum() != 0) {
                                budget.save(new BudgetAssignment(userId, month, category, amount));
                            }
                        });
    }

    /** Computes the envelopes month by month from the first month with data up to the requested one. */
    private final class Calculation {

        private final Long userId;
        private final YearMonth month;
        private final CurrencyConverter converter;
        private final Map<YearMonth, Map<Long, BigDecimal>> assignedByMonth = new HashMap<>();
        private final Map<YearMonth, Map<Long, BigDecimal>> activityByMonth = new HashMap<>();
        /** Money that became available to assign: income, inflows from outside the budget, opening balances. */
        private BigDecimal inflow = BigDecimal.ZERO;
        /** Spending without a category, taken directly from the money ready to assign. */
        private BigDecimal uncategorized = BigDecimal.ZERO;

        Calculation(Long userId, YearMonth month, CurrencyConverter converter) {
            this.userId = userId;
            this.month = month;
            this.converter = converter;
        }

        MonthResponse result() {
            LocalDate end = month.atEndOfMonth();
            loadAssignments();
            loadSplits(end);
            loadTransfers(end);
            loadOpeningBalances(end);

            TreeSet<YearMonth> months = new TreeSet<>();
            months.addAll(assignedByMonth.keySet());
            months.addAll(activityByMonth.keySet());
            months.add(month);

            Map<Long, BigDecimal> available = new HashMap<>();
            BigDecimal assignedTotal = BigDecimal.ZERO;
            BigDecimal overspent = BigDecimal.ZERO;
            boolean first = true;
            for (YearMonth current : months.headSet(month, true)) {
                if (!first) {
                    // Overspending of a finished month is covered by the money ready to assign
                    for (Map.Entry<Long, BigDecimal> entry : available.entrySet()) {
                        if (entry.getValue().signum() < 0) {
                            overspent = overspent.add(entry.getValue());
                            entry.setValue(BigDecimal.ZERO);
                        }
                    }
                }
                first = false;
                Map<Long, BigDecimal> assigned = assignedByMonth.getOrDefault(current, Map.of());
                Map<Long, BigDecimal> activity = activityByMonth.getOrDefault(current, Map.of());
                assigned.forEach((category, amount) -> available.merge(category, amount, BigDecimal::add));
                activity.forEach((category, amount) -> available.merge(category, amount, BigDecimal::add));
                assignedTotal = assignedTotal.add(sum(assigned));
            }
            BigDecimal readyToAssign =
                    inflow.add(uncategorized).subtract(assignedTotal).add(overspent);
            return response(readyToAssign, available);
        }

        private void loadAssignments() {
            for (BudgetAssignment assignment : budget.findUpTo(userId, month.atDay(1))) {
                assignedByMonth
                        .computeIfAbsent(YearMonth.from(assignment.getMonth()), m -> new HashMap<>())
                        .merge(assignment.getCategory().getId(), assignment.getAmount(), BigDecimal::add);
            }
        }

        private void loadSplits(LocalDate end) {
            for (SplitRow row : budget.findBudgetSplits(userId, end, TransactionType.TRANSFER)) {
                BigDecimal amount = converter.convert(row.getAmount(), row.getCurrency(), row.getDate());
                boolean income = row.getType() == TransactionType.INCOME;
                if (row.getCategoryId() != null && row.getKind() == CategoryKind.EXPENSE) {
                    activityByMonth
                            .computeIfAbsent(YearMonth.from(row.getDate()), m -> new HashMap<>())
                            .merge(row.getCategoryId(), income ? amount : amount.negate(), BigDecimal::add);
                } else if (income) {
                    inflow = inflow.add(amount);
                } else {
                    uncategorized = uncategorized.subtract(amount);
                }
            }
        }

        private void loadTransfers(LocalDate end) {
            for (TransferRow row : budget.findBudgetBoundaryTransfers(userId, end, TransactionType.TRANSFER)) {
                if (row.getFromBudget()) {
                    inflow = inflow.subtract(converter.convert(row.getAmount(), row.getCurrency(), row.getDate()));
                } else {
                    inflow = inflow.add(converter.convert(row.getToAmount(), row.getToCurrency(), row.getDate()));
                }
            }
        }

        private void loadOpeningBalances(LocalDate end) {
            LocalDate today = LocalDate.now(clock);
            LocalDate rateDate = end.isAfter(today) ? today : end;
            for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
                if (account.isInBudget()) {
                    inflow =
                            inflow.add(converter.convert(account.getOpeningBalance(), account.getCurrency(), rateDate));
                }
            }
        }

        private MonthResponse response(BigDecimal readyToAssign, Map<Long, BigDecimal> available) {
            Map<Long, BigDecimal> assigned = assignedByMonth.getOrDefault(month, Map.of());
            Map<Long, BigDecimal> activity = activityByMonth.getOrDefault(month, Map.of());
            CategoryTree tree = categories.tree(userId);
            List<CategoryRow> rows = new ArrayList<>();
            for (Category parent : tree.topLevel(CategoryKind.EXPENSE)) {
                List<CategoryRow> children = tree.children(parent.getId()).stream()
                        .map(child -> row(child, assigned, activity, available, List.of()))
                        .filter(row -> !row.archived() || hasMoney(row))
                        .toList();
                CategoryRow row = row(parent, assigned, activity, available, children);
                if (!row.archived() || hasMoney(row)) {
                    rows.add(row);
                }
            }
            return new MonthResponse(
                    month,
                    converter.target(),
                    readyToAssign,
                    sum(rows, CategoryRow::assigned),
                    sum(rows, CategoryRow::activity),
                    sum(rows, CategoryRow::available),
                    rows,
                    converter.unconverted());
        }

        private CategoryRow row(
                Category category,
                Map<Long, BigDecimal> assigned,
                Map<Long, BigDecimal> activity,
                Map<Long, BigDecimal> available,
                List<CategoryRow> children) {
            BigDecimal ownAssigned = assigned.getOrDefault(category.getId(), BigDecimal.ZERO);
            BigDecimal ownActivity = activity.getOrDefault(category.getId(), BigDecimal.ZERO);
            BigDecimal ownAvailable = available.getOrDefault(category.getId(), BigDecimal.ZERO);
            return new CategoryRow(
                    category.getId(),
                    category.getName(),
                    category.getParentId(),
                    category.isArchived(),
                    ownAssigned.add(sum(children, CategoryRow::assigned)),
                    ownActivity.add(sum(children, CategoryRow::activity)),
                    ownAvailable.add(sum(children, CategoryRow::available)),
                    ownAssigned,
                    ownActivity,
                    ownAvailable,
                    children);
        }
    }

    private static boolean hasMoney(CategoryRow row) {
        return row.assigned().signum() != 0
                || row.activity().signum() != 0
                || row.available().signum() != 0;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<CategoryRow> rows, Function<CategoryRow, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

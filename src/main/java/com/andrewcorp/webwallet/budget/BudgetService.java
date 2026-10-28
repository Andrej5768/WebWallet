package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.CategoryRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.IncomeRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.MoveRequest;
import com.andrewcorp.webwallet.budget.BudgetDtos.PlanEntry;
import com.andrewcorp.webwallet.budget.BudgetRepository.SplitRow;
import com.andrewcorp.webwallet.budget.BudgetRepository.TransferRow;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.category.CategoryTree;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budget of a month in the base currency of the user: a plan per expense category, compared with the spending of the
 * month, as in Zen Money. Only the money of budget accounts counts. By default a plan is for its month alone: what is
 * left or overspent expires with the month. A category with carry-over is an envelope as in YNAB: its positive rest
 * moves to the next month and its overspending is taken from the free money of the next month. Credit cards are
 * treated like cash: spending on them uses the plan, and paying the card back is a transfer within the budget.
 * Income categories have a plan too, the income expected in the month; it is compared with the income received and
 * does not change the free money, which counts only the income actually received.
 */
@Service
public class BudgetService {

    private final Converters converters;
    private final BudgetRepository budget;
    private final CategoryRepository categories;

    public BudgetService(Converters converters, BudgetRepository budget, CategoryRepository categories) {
        this.converters = converters;
        this.budget = budget;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public MonthResponse month(Long userId, YearMonth month) {
        CurrencyConverter converter = converters.base(userId);
        return new Calculation(userId, month, converter).result();
    }

    @Transactional
    public MonthResponse assign(Long userId, YearMonth month, Long categoryId, BigDecimal amount) {
        Category category = categories.findByIdAndUserId(categoryId, userId).orElseThrow(ApiException::notFound);
        setAssigned(userId, month.atDay(1), category, amount);
        budget.flush();
        return month(userId, month);
    }

    /** Sets the plans of several categories of the month; an unknown or repeated category changes nothing. */
    @Transactional
    public MonthResponse assignMany(Long userId, YearMonth month, List<PlanEntry> plans) {
        Map<Long, Category> owned = new HashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            Long categoryId = plans.get(i).categoryId();
            String field = "plans[" + i + "].categoryId";
            if (owned.containsKey(categoryId)) {
                throw ApiException.invalid(field, "error.budget.repeatedCategory");
            }
            owned.put(
                    categoryId,
                    categories
                            .findByIdAndUserId(categoryId, userId)
                            .orElseThrow(() -> ApiException.invalid(field, "error.notFound")));
        }
        LocalDate first = month.atDay(1);
        for (PlanEntry plan : plans) {
            setAssigned(userId, first, owned.get(plan.categoryId()), plan.assigned());
        }
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
     * Gives every category without an own plan this month, expense or income, its plan of the previous month.
     * Categories already planned this month and archived categories are left as they are.
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
            if (!category.isArchived()
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

    /** Computes the month from the first month with data up to the requested one, for the carry-over. */
    private final class Calculation {

        private final Long userId;
        private final YearMonth month;
        private final CurrencyConverter converter;
        private final Map<YearMonth, Map<Long, BigDecimal>> assignedByMonth = new HashMap<>();
        private final Map<YearMonth, Map<Long, BigDecimal>> activityByMonth = new HashMap<>();
        /** Income and transfers into the budget (minus those out of it), by month. */
        private final Map<YearMonth, BigDecimal> inflowByMonth = new HashMap<>();
        /** Spending without a category, by month: taken directly from the free money of the month. */
        private final Map<YearMonth, BigDecimal> uncategorizedByMonth = new HashMap<>();
        /** Plan of each income category in the requested month. */
        private final Map<Long, BigDecimal> plannedIncome = new HashMap<>();
        /** Income received in each income category in the requested month. */
        private final Map<Long, BigDecimal> receivedIncome = new HashMap<>();
        /** Income without a category in the requested month. */
        private BigDecimal uncategorizedIncome = BigDecimal.ZERO;

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
            CategoryTree tree = categories.tree(userId);
            Set<Long> carryOver = carryOverCategories(tree);

            TreeSet<YearMonth> months = new TreeSet<>();
            months.addAll(assignedByMonth.keySet());
            months.addAll(activityByMonth.keySet());
            months.add(month);

            Map<Long, BigDecimal> available = new HashMap<>();
            // Overspending of the envelopes at the end of the previous month, charged to this month's free money
            BigDecimal overspent = BigDecimal.ZERO;
            for (YearMonth current = months.first(); !current.isAfter(month); current = current.plusMonths(1)) {
                if (!current.equals(months.first())) {
                    overspent = BigDecimal.ZERO;
                    for (Map.Entry<Long, BigDecimal> entry : available.entrySet()) {
                        if (!carryOver.contains(entry.getKey())) {
                            // A plan is for its month only
                            entry.setValue(BigDecimal.ZERO);
                        } else if (entry.getValue().signum() < 0) {
                            overspent = overspent.add(entry.getValue());
                            entry.setValue(BigDecimal.ZERO);
                        }
                    }
                }
                Map<Long, BigDecimal> assigned = assignedByMonth.getOrDefault(current, Map.of());
                Map<Long, BigDecimal> activity = activityByMonth.getOrDefault(current, Map.of());
                assigned.forEach((category, amount) -> available.merge(category, amount, BigDecimal::add));
                activity.forEach((category, amount) -> available.merge(category, amount, BigDecimal::add));
            }
            BigDecimal inflow = inflowByMonth.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal readyToAssign = inflow.add(uncategorizedByMonth.getOrDefault(month, BigDecimal.ZERO))
                    .subtract(sum(assignedByMonth.getOrDefault(month, Map.of())))
                    .add(overspent);
            return response(tree, readyToAssign, inflow, available);
        }

        private Set<Long> carryOverCategories(CategoryTree tree) {
            Set<Long> ids = new HashSet<>();
            for (Category parent : tree.topLevel(CategoryKind.EXPENSE)) {
                if (parent.isCarryOver()) {
                    ids.add(parent.getId());
                }
                for (Category child : tree.children(parent.getId())) {
                    if (child.isCarryOver()) {
                        ids.add(child.getId());
                    }
                }
            }
            return ids;
        }

        private void loadAssignments() {
            for (BudgetAssignment assignment : budget.findUpTo(userId, month.atDay(1))) {
                if (assignment.getCategory().getKind() == CategoryKind.INCOME) {
                    if (YearMonth.from(assignment.getMonth()).equals(month)) {
                        plannedIncome.merge(assignment.getCategory().getId(), assignment.getAmount(), BigDecimal::add);
                    }
                    continue;
                }
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
                    inflowByMonth.merge(YearMonth.from(row.getDate()), amount, BigDecimal::add);
                    if (YearMonth.from(row.getDate()).equals(month)) {
                        if (row.getCategoryId() == null) {
                            uncategorizedIncome = uncategorizedIncome.add(amount);
                        } else {
                            receivedIncome.merge(row.getCategoryId(), amount, BigDecimal::add);
                        }
                    }
                } else {
                    uncategorizedByMonth.merge(YearMonth.from(row.getDate()), amount.negate(), BigDecimal::add);
                }
            }
        }

        private void loadTransfers(LocalDate end) {
            for (TransferRow row : budget.findBudgetBoundaryTransfers(userId, end, TransactionType.TRANSFER)) {
                BigDecimal amount = row.getFromBudget()
                        ? converter
                                .convert(row.getAmount(), row.getCurrency(), row.getDate())
                                .negate()
                        : converter.convert(row.getToAmount(), row.getToCurrency(), row.getDate());
                inflowByMonth.merge(YearMonth.from(row.getDate()), amount, BigDecimal::add);
            }
        }

        private MonthResponse response(
                CategoryTree tree, BigDecimal readyToAssign, BigDecimal inflow, Map<Long, BigDecimal> available) {
            Map<Long, BigDecimal> assigned = assignedByMonth.getOrDefault(month, Map.of());
            Map<Long, BigDecimal> activity = activityByMonth.getOrDefault(month, Map.of());
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
            List<IncomeRow> incomeRows = incomeRows(tree);
            return new MonthResponse(
                    month,
                    converter.target(),
                    readyToAssign,
                    inflow,
                    sum(rows, CategoryRow::assigned),
                    sum(rows, CategoryRow::activity),
                    sum(rows, CategoryRow::available),
                    rows,
                    sum(incomeRows, IncomeRow::planned),
                    sum(incomeRows, IncomeRow::received).add(uncategorizedIncome),
                    uncategorizedIncome,
                    incomeRows,
                    converter.unconverted());
        }

        private List<IncomeRow> incomeRows(CategoryTree tree) {
            List<IncomeRow> rows = new ArrayList<>();
            for (Category parent : tree.topLevel(CategoryKind.INCOME)) {
                List<IncomeRow> children = tree.children(parent.getId()).stream()
                        .map(child -> incomeRow(child, List.of()))
                        .filter(row -> !row.archived() || hasMoney(row))
                        .toList();
                IncomeRow row = incomeRow(parent, children);
                if (!row.archived() || hasMoney(row)) {
                    rows.add(row);
                }
            }
            return rows;
        }

        private IncomeRow incomeRow(Category category, List<IncomeRow> children) {
            BigDecimal ownPlanned = plannedIncome.getOrDefault(category.getId(), BigDecimal.ZERO);
            BigDecimal ownReceived = receivedIncome.getOrDefault(category.getId(), BigDecimal.ZERO);
            return new IncomeRow(
                    category.getId(),
                    category.getName(),
                    category.getParentId(),
                    category.isArchived(),
                    ownPlanned.add(sum(children, IncomeRow::planned)),
                    ownReceived.add(sum(children, IncomeRow::received)),
                    ownPlanned,
                    ownReceived,
                    children);
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

    private static boolean hasMoney(IncomeRow row) {
        return row.planned().signum() != 0 || row.received().signum() != 0;
    }

    private static BigDecimal sum(Map<Long, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

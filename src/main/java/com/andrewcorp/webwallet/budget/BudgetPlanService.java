package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.BudgetPlanCell;
import com.andrewcorp.webwallet.budget.BudgetDtos.BudgetPlanResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.BudgetPlanRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.BudgetPlanTotals;
import com.andrewcorp.webwallet.budget.BudgetRepository.SplitRow;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.category.CategoryTree;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan and actual values of the budget over several months side by side: the expected income of the income
 * categories, the plan of the expense categories, what was received and spent on the budget accounts, and the
 * average of the last full months as a hint for the next plans.
 */
@Service
public class BudgetPlanService {

    /** Longest range of one request: two years around the current month, with room to spare. */
    static final int MAX_MONTHS = 36;

    /** Full months before the current one that the average is taken from. */
    static final int AVERAGE_MONTHS = 3;

    private final Converters converters;
    private final BudgetRepository budget;
    private final CategoryRepository categories;
    private final Clock clock;

    public BudgetPlanService(
            Converters converters, BudgetRepository budget, CategoryRepository categories, Clock clock) {
        this.converters = converters;
        this.budget = budget;
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BudgetPlanResponse plan(Long userId, YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw ApiException.invalid("to", "error.budget.rangeOrder");
        }
        if (from.plusMonths(MAX_MONTHS).isBefore(to.plusMonths(1))) {
            throw ApiException.invalid("to", "error.budget.rangeTooLong", MAX_MONTHS);
        }
        return new Table(userId, from, to).result();
    }

    private final class Table {

        private final Long userId;
        private final CurrencyConverter converter;
        private final List<YearMonth> months = new ArrayList<>();
        private final List<YearMonth> averageMonths = new ArrayList<>();
        /** Own plan and own actual value per month and category; spending is positive. */
        private final Map<YearMonth, Map<Long, BigDecimal>> plans = new HashMap<>();

        private final Map<YearMonth, Map<Long, BigDecimal>> actuals = new HashMap<>();
        private final Map<YearMonth, BigDecimal> uncategorizedIncome = new HashMap<>();
        private final Map<YearMonth, BigDecimal> uncategorizedExpenses = new HashMap<>();

        Table(Long userId, YearMonth from, YearMonth to) {
            this.userId = userId;
            this.converter = converters.base(userId);
            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                months.add(month);
            }
            YearMonth current = YearMonth.now(clock);
            for (int i = AVERAGE_MONTHS; i > 0; i--) {
                averageMonths.add(current.minusMonths(i));
            }
        }

        BudgetPlanResponse result() {
            YearMonth first = min(months.getFirst(), averageMonths.getFirst());
            YearMonth last = max(months.getLast(), averageMonths.getLast());
            for (BudgetAssignment assignment : budget.findBetween(userId, first.atDay(1), last.atDay(1))) {
                plans.computeIfAbsent(YearMonth.from(assignment.getMonth()), m -> new HashMap<>())
                        .merge(assignment.getCategory().getId(), assignment.getAmount(), BigDecimal::add);
            }
            for (SplitRow row : budget.findBudgetSplitsBetween(
                    userId, first.atDay(1), last.atEndOfMonth(), TransactionType.TRANSFER)) {
                addActual(row);
            }
            CategoryTree tree = categories.tree(userId);
            List<BudgetPlanRow> income = rows(tree, CategoryKind.INCOME);
            List<BudgetPlanRow> expenses = rows(tree, CategoryKind.EXPENSE);
            List<BudgetPlanTotals> totals = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                YearMonth month = months.get(i);
                BigDecimal otherIncome = uncategorizedIncome.getOrDefault(month, BigDecimal.ZERO);
                BigDecimal otherExpenses = uncategorizedExpenses.getOrDefault(month, BigDecimal.ZERO);
                totals.add(new BudgetPlanTotals(
                        month,
                        sumCells(income, i, BudgetPlanCell::plan),
                        sumCells(income, i, BudgetPlanCell::actual).add(otherIncome),
                        sumCells(expenses, i, BudgetPlanCell::plan),
                        sumCells(expenses, i, BudgetPlanCell::actual).add(otherExpenses),
                        otherIncome,
                        otherExpenses));
            }
            return new BudgetPlanResponse(
                    converter.target(), months, averageMonths, income, expenses, totals, converter.unconverted());
        }

        /**
         * Income of an income category is income received; income in an expense category is a refund and lowers its
         * spending; spending in an income category, which a client should not create, lowers its income.
         */
        private void addActual(SplitRow row) {
            YearMonth month = YearMonth.from(row.getDate());
            BigDecimal amount = converter.convert(row.getAmount(), row.getCurrency(), row.getDate());
            boolean income = row.getType() == TransactionType.INCOME;
            if (row.getCategoryId() == null) {
                Map<YearMonth, BigDecimal> target = income ? uncategorizedIncome : uncategorizedExpenses;
                target.merge(month, amount, BigDecimal::add);
                return;
            }
            boolean incomeCategory = row.getKind() == CategoryKind.INCOME;
            BigDecimal signed = income == incomeCategory ? amount : amount.negate();
            actuals.computeIfAbsent(month, m -> new HashMap<>()).merge(row.getCategoryId(), signed, BigDecimal::add);
        }

        private List<BudgetPlanRow> rows(CategoryTree tree, CategoryKind kind) {
            List<BudgetPlanRow> rows = new ArrayList<>();
            for (Category parent : tree.topLevel(kind)) {
                List<BudgetPlanRow> children = tree.children(parent.getId()).stream()
                        .map(child -> row(child, List.of()))
                        .filter(row -> !row.archived() || hasValues(row))
                        .toList();
                BudgetPlanRow row = row(parent, children);
                if (!row.archived() || hasValues(row)) {
                    rows.add(row);
                }
            }
            return rows;
        }

        private BudgetPlanRow row(Category category, List<BudgetPlanRow> children) {
            Long id = category.getId();
            List<BudgetPlanCell> cells = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                YearMonth month = months.get(i);
                BigDecimal ownPlan = value(plans, month, id);
                BigDecimal ownActual = value(actuals, month, id);
                cells.add(new BudgetPlanCell(
                        month,
                        ownPlan.add(sumCells(children, i, BudgetPlanCell::plan)),
                        ownActual.add(sumCells(children, i, BudgetPlanCell::actual)),
                        ownPlan,
                        ownActual));
            }
            BigDecimal ownTotal = averageTotal(id);
            // From the sum of the actual values, so the rounded averages of the children do not add up their errors
            BigDecimal total = children.stream()
                    .map(child -> averageTotal(child.categoryId()))
                    .reduce(ownTotal, BigDecimal::add);
            return new BudgetPlanRow(
                    id,
                    category.getName(),
                    category.getParentId(),
                    category.isArchived(),
                    category.isCarryOver(),
                    average(total),
                    average(ownTotal),
                    cells,
                    children);
        }

        /** Actual value of the category over the months of the average. */
        private BigDecimal averageTotal(Long categoryId) {
            return averageMonths.stream()
                    .map(month -> value(actuals, month, categoryId))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private static BigDecimal average(BigDecimal total) {
            return total.divide(BigDecimal.valueOf(AVERAGE_MONTHS), 2, RoundingMode.HALF_UP);
        }

        private boolean hasValues(BudgetPlanRow row) {
            return row.average().signum() != 0
                    || row.months().stream()
                            .anyMatch(cell ->
                                    cell.plan().signum() != 0 || cell.actual().signum() != 0);
        }
    }

    private static BigDecimal value(Map<YearMonth, Map<Long, BigDecimal>> values, YearMonth month, Long categoryId) {
        return values.getOrDefault(month, Map.of()).getOrDefault(categoryId, BigDecimal.ZERO);
    }

    private static BigDecimal sumCells(
            List<BudgetPlanRow> rows, int index, Function<BudgetPlanCell, BigDecimal> field) {
        return rows.stream().map(row -> field.apply(row.months().get(index))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static YearMonth min(YearMonth a, YearMonth b) {
        return a.isBefore(b) ? a : b;
    }

    private static YearMonth max(YearMonth a, YearMonth b) {
        return a.isAfter(b) ? a : b;
    }
}
